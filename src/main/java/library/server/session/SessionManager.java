package library.server.session;

import library.common.EventType;
import library.common.Message;
import library.common.SessionInfo;
import library.common.UserInfo;
import library.server.ClientHandler;
import library.server.Log;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * QUẢN LÝ PHIÊN ĐĂNG NHẬP.
 * <ul>
 *   <li>Token 256-bit sinh bằng SecureRandom, tra cứu O(1) bằng ConcurrentHashMap (theo token và theo tài khoản).</li>
 *   <li>Mỗi tài khoản chỉ có 1 phiên: đăng nhập ở máy mới → máy cũ bị đăng xuất và được thông báo.</li>
 *   <li>Hết hạn khi không thao tác quá {@code idleTimeout} (PING giữ kết nối không tính là thao tác).</li>
 *   <li>Rớt mạng: phiên được giữ {@code reconnectGrace} để client tự RESUME, không mất trạng thái.</li>
 *   <li>Việc gửi thông báo + đóng kết nối cũ chạy bất đồng bộ, không giữ khóa khi làm I/O mạng.</li>
 * </ul>
 */
public class SessionManager {
    private final Map<String, Session> byToken = new ConcurrentHashMap<>();
    private final Map<String, Session> byUser = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private final long idleTimeoutMs;
    private final long reconnectGraceMs;
    private final Executor notifier;

    public SessionManager(long idleTimeoutMs, long reconnectGraceMs, Executor notifier) {
        this.idleTimeoutMs = idleTimeoutMs;
        this.reconnectGraceMs = reconnectGraceMs;
        this.notifier = notifier;
    }

    /** Tạo phiên mới cho người dùng vừa đăng nhập thành công trên kết nối {@code handler}. */
    public synchronized Session login(UserInfo user, ClientHandler handler) {
        Session current = handler.getSession();
        if (current != null) { // đăng nhập lại trên cùng kết nối mà chưa đăng xuất
            remove(current);
        }
        Session old = byUser.get(user.getUsername());
        if (old != null) {
            remove(old);
            ClientHandler oldHandler = old.getHandler();
            if (oldHandler != null && oldHandler != handler) {
                oldHandler.setSession(null);
                notifyAndClose(oldHandler, Message.event(EventType.FORCE_LOGOUT,
                        "Tài khoản của bạn vừa được đăng nhập từ máy khác (" + handler.getRemoteIp() + ")."));
            }
            Log.info("Phiên cũ %s của %s bị thay thế do đăng nhập từ %s", old.getId(), user.getUsername(),
                    handler.getRemoteIp());
        }
        Session s = new Session(String.format("S%04d", sequence.incrementAndGet()), newToken(), user);
        s.attach(handler);
        handler.setSession(s);
        byToken.put(s.getToken(), s);
        byUser.put(user.getUsername(), s);
        return s;
    }

    /** Gắn lại phiên cũ vào kết nối mới (sau khi rớt mạng). Trả về null nếu token không còn hiệu lực. */
    public synchronized Session resume(String token, ClientHandler handler) {
        if (token == null) {
            return null;
        }
        Session s = byToken.get(token);
        if (s == null) {
            return null;
        }
        if (s.isExpired(System.currentTimeMillis(), idleTimeoutMs, reconnectGraceMs)) {
            remove(s);
            return null;
        }
        ClientHandler old = s.getHandler();
        if (old != null && old != handler) { // kết nối cũ "treo" (half-open) → đóng luôn
            old.setSession(null);
            old.close();
        }
        s.attach(handler);
        s.touch();
        handler.setSession(s);
        return s;
    }

    /** Kết nối TCP bị đóng: phiên chuyển sang trạng thái chờ kết nối lại. */
    public synchronized void detach(ClientHandler handler) {
        Session s = handler.getSession();
        if (s != null && s.getHandler() == handler) {
            s.detach();
            Log.info("Phiên %s (%s) mất kết nối - giữ %d giây chờ kết nối lại", s.getId(),
                    s.getUser().getUsername(), reconnectGraceMs / 1000);
        }
    }

    public synchronized void logout(Session s) {
        remove(s);
        ClientHandler h = s.getHandler();
        if (h != null) {
            h.setSession(null);
        }
    }

    /** Quản trị viên ngắt phiên theo mã công khai. */
    public synchronized boolean kick(String sessionId, String reason) {
        Session target = null;
        for (Session s : byToken.values()) {
            if (s.getId().equals(sessionId)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        remove(target);
        ClientHandler h = target.getHandler();
        if (h != null) {
            h.setSession(null);
            notifyAndClose(h, Message.event(EventType.FORCE_LOGOUT, reason));
        }
        Log.info("Ngắt phiên %s của %s", target.getId(), target.getUser().getUsername());
        return true;
    }

    /** Chạy định kỳ trên luồng scheduler: dọn các phiên hết hạn. */
    public int expireSessions() {
        List<Session> expired = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (Session s : byToken.values()) {
                if (s.isExpired(now, idleTimeoutMs, reconnectGraceMs)) {
                    remove(s);
                    expired.add(s);
                }
            }
            for (Session s : expired) {
                ClientHandler h = s.getHandler();
                if (h != null) {
                    h.setSession(null);
                    notifyAndClose(h, Message.event(EventType.SESSION_EXPIRED,
                            "Phiên đăng nhập đã hết hạn do bạn không thao tác quá "
                                    + idleTimeoutMs / 60000 + " phút. Vui lòng đăng nhập lại."));
                }
            }
        }
        for (Session s : expired) {
            Log.info("Phiên %s của %s hết hạn (%s)", s.getId(), s.getUser().getUsername(),
                    s.isOnline() ? "không thao tác" : "không kết nối lại");
        }
        return expired.size();
    }

    public boolean isActive(Session s) {
        return s != null && byToken.get(s.getToken()) == s;
    }

    public List<SessionInfo> snapshot() {
        return byToken.values().stream()
                .map(Session::toInfo)
                .sorted(Comparator.comparing(SessionInfo::getId))
                .collect(Collectors.toList());
    }

    public int countOnline() {
        return (int) byToken.values().stream().filter(Session::isOnline).count();
    }

    public int countWaitingReconnect() {
        return (int) byToken.values().stream().filter(s -> !s.isOnline()).count();
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    private void remove(Session s) {
        byToken.remove(s.getToken(), s);
        byUser.remove(s.getUser().getUsername(), s);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void notifyAndClose(ClientHandler h, Message event) {
        Runnable task = () -> {
            h.send(event);
            h.close();
        };
        try {
            notifier.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();
        }
    }
}
