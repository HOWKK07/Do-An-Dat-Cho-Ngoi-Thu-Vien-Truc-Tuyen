package library.server.session;

import library.common.SessionInfo;
import library.common.UserInfo;
import library.server.ClientHandler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Một phiên đăng nhập.
 * <ul>
 *   <li>{@code token}: chuỗi ngẫu nhiên bí mật, chỉ client sở hữu biết - dùng để kết nối lại (resume).</li>
 *   <li>{@code id}: mã công khai (S0001) để quản trị viên xem/ngắt phiên mà không lộ token.</li>
 *   <li>Phiên tách rời khỏi kết nối TCP: khi rớt mạng, phiên vẫn được giữ một khoảng thời gian
 *       (grace period) để client tự kết nối lại mà không phải đăng nhập lại.</li>
 * </ul>
 */
public class Session {
    private final String id;
    private final String token;
    private final UserInfo user;
    private final long createdAt;
    private volatile long lastActive;
    private volatile long disconnectedAt;
    private volatile ClientHandler handler;
    private volatile String ip;

    Session(String id, String token, UserInfo user) {
        this.id = id;
        this.token = token;
        this.user = user;
        this.createdAt = System.currentTimeMillis();
        this.lastActive = createdAt;
    }

    /** Ghi nhận người dùng vừa thao tác (PING không tính là thao tác). */
    public void touch() {
        lastActive = System.currentTimeMillis();
    }

    void attach(ClientHandler h) {
        handler = h;
        ip = h.getRemoteIp();
        disconnectedAt = 0;
    }

    void detach() {
        handler = null;
        disconnectedAt = System.currentTimeMillis();
    }

    boolean isExpired(long now, long idleTimeoutMs, long reconnectGraceMs) {
        if (now - lastActive > idleTimeoutMs) {
            return true;
        }
        return handler == null && now - disconnectedAt > reconnectGraceMs;
    }

    public SessionInfo toInfo() {
        return new SessionInfo(id, user.getUsername(), user.getFullName(), user.getRole(), ip,
                handler != null, toLocal(createdAt), toLocal(lastActive));
    }

    private static LocalDateTime toLocal(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    public String getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public UserInfo getUser() {
        return user;
    }

    public ClientHandler getHandler() {
        return handler;
    }

    public boolean isOnline() {
        return handler != null;
    }

    public long getLastActive() {
        return lastActive;
    }
}
