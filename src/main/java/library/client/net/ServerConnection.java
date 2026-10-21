package library.client.net;

import library.common.Command;
import library.common.EventType;
import library.common.Message;
import library.common.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kết nối tới server phía client.
 * <ul>
 *   <li>Luồng "client-reader": đọc liên tục từ socket; phản hồi được ghép với yêu cầu qua requestId,
 *       sự kiện push được chuyển cho các listener. Nhờ vậy giao diện không bao giờ bị treo khi chờ mạng.</li>
 *   <li>Luồng "client-heartbeat": gửi PING định kỳ (giữ kết nối, đo độ trễ) và thực hiện tự kết nối lại.</li>
 *   <li>Rớt mạng → tự kết nối lại và gửi RESUME_SESSION bằng token, người dùng không phải đăng nhập lại.</li>
 * </ul>
 */
public class ServerConnection {

    /** Các callback được gọi trên luồng nền - giao diện phải tự chuyển về luồng Swing (EDT). */
    public interface Listener {
        default void onEvent(Message event) {
        }

        default void onConnectionLost() {
        }

        default void onReconnecting(int attempt) {
        }

        default void onReconnected() {
        }

        default void onSessionLost(String reason) {
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int REQUEST_TIMEOUT_SECONDS = 15;
    private static final int RECONNECT_DELAY_SECONDS = 3;
    private static final int MAX_RECONNECT_ATTEMPTS = 40; // ~2 phút

    private final String host;
    private final int port;
    private final AtomicLong requestSeq = new AtomicLong();
    private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "client-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile Socket socket;
    private volatile ObjectOutputStream out;
    private volatile String sessionToken;
    private volatile boolean closedByUser;
    private volatile boolean sessionEnded;
    private volatile String serverBusyReason;
    private volatile long lastPingMillis = -1;
    private volatile boolean heartbeatStarted;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    public ServerConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // ------------------------------------------------------------------ Kết nối

    public synchronized void connect() throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            ObjectOutputStream o = new ObjectOutputStream(new BufferedOutputStream(s.getOutputStream()));
            o.flush();
            s.setSoTimeout(CONNECT_TIMEOUT_MS); // chỉ để chờ header, sau đó bỏ giới hạn
            ObjectInputStream in = Protocol.newNetworkInput(new BufferedInputStream(s.getInputStream()));
            s.setSoTimeout(0);
            socket = s;
            out = o;
            serverBusyReason = null;
            Thread reader = new Thread(() -> readLoop(s, in), "client-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // bỏ qua
            }
            throw friendly(e);
        }
        if (!heartbeatStarted) {
            heartbeatStarted = true;
            background.scheduleAtFixedRate(this::heartbeat, Protocol.HEARTBEAT_SECONDS,
                    Protocol.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private IOException friendly(IOException e) {
        String where = host + ":" + port;
        if (e instanceof UnknownHostException) {
            return new IOException("Không tìm thấy máy chủ \"" + host + "\". Kiểm tra lại địa chỉ.", e);
        }
        if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
            return new IOException("Không kết nối được tới " + where
                    + ".\nHãy kiểm tra server đã chạy, cùng mạng LAN và tường lửa cho phép cổng " + port + ".", e);
        }
        if (e instanceof SocketTimeoutException) {
            return new IOException("Hết thời gian chờ kết nối tới " + where + ".", e);
        }
        return e;
    }

    private void readLoop(Socket s, ObjectInputStream in) {
        try {
            while (true) {
                Object obj = in.readObject();
                if (!(obj instanceof Message)) {
                    continue;
                }
                Message m = (Message) obj;
                if (m.getKind() == Message.Kind.RESPONSE) {
                    CompletableFuture<Message> f = pending.remove(m.getRequestId());
                    if (f != null) {
                        f.complete(m);
                    }
                } else if (m.getKind() == Message.Kind.EVENT) {
                    handleEvent(m);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            // kết nối đóng
        } finally {
            onSocketClosed(s);
        }
    }

    private void handleEvent(Message m) {
        EventType type = m.getEvent();
        if (type == EventType.SERVER_BUSY) {
            serverBusyReason = m.getText();
            return;
        }
        if (type == EventType.FORCE_LOGOUT || type == EventType.SESSION_EXPIRED) {
            sessionEnded = true; // không tự kết nối lại khi bị đăng xuất
            sessionToken = null;
        }
        for (Listener l : listeners) {
            l.onEvent(m);
        }
    }

    private void onSocketClosed(Socket s) {
        synchronized (this) {
            if (socket != s) {
                return; // socket cũ, đã được thay thế
            }
            socket = null;
            out = null;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // bỏ qua
        }
        String reason = serverBusyReason != null ? serverBusyReason : "Mất kết nối tới máy chủ.";
        failPending(new IOException(reason));
        if (closedByUser || sessionEnded) {
            return;
        }
        if (sessionToken == null) {
            return; // chưa đăng nhập (đang ở màn hình đăng nhập)
        }
        if (!reconnecting.compareAndSet(false, true)) {
            return; // đang trong chuỗi thử kết nối lại
        }
        for (Listener l : listeners) {
            l.onConnectionLost();
        }
        scheduleReconnect(1);
    }

    private void scheduleReconnect(int attempt) {
        if (closedByUser) {
            return;
        }
        background.schedule(() -> tryReconnect(attempt), RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void tryReconnect(int attempt) {
        if (closedByUser || sessionEnded) {
            return;
        }
        for (Listener l : listeners) {
            l.onReconnecting(attempt);
        }
        try {
            connect();
            Message r = sendAndWait(Message.request(Command.RESUME_SESSION).put("token", sessionToken));
            if (r.isSuccess()) {
                reconnecting.set(false);
                for (Listener l : listeners) {
                    l.onReconnected();
                }
            } else {
                sessionToken = null;
                sessionEnded = true;
                closeSocketQuietly();
                for (Listener l : listeners) {
                    l.onSessionLost(r.getText());
                }
            }
        } catch (Exception e) {
            closeSocketQuietly();
            if (attempt < MAX_RECONNECT_ATTEMPTS) {
                scheduleReconnect(attempt + 1);
            } else {
                sessionEnded = true;
                for (Listener l : listeners) {
                    l.onSessionLost("Không thể kết nối lại tới máy chủ sau nhiều lần thử.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ Gửi yêu cầu

    /** Gửi bất đồng bộ - trả về Future hoàn thành khi có phản hồi (hoặc lỗi/hết giờ). */
    public CompletableFuture<Message> send(Message request) {
        long id = requestSeq.incrementAndGet();
        request.setRequestId(id);
        CompletableFuture<Message> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            synchronized (writeLock) {
                ObjectOutputStream o = out;
                if (o == null) {
                    throw new IOException("Chưa kết nối tới máy chủ.");
                }
                o.writeObject(request);
                o.reset();
                o.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
            return future;
        }
        future.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((r, ex) -> pending.remove(id));
        return future;
    }

    /** Gửi và chờ phản hồi (KHÔNG gọi trên luồng giao diện). */
    public Message sendAndWait(Message request) throws IOException {
        try {
            return send(request).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bị gián đoạn", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof TimeoutException) {
                throw new IOException("Máy chủ không phản hồi (quá " + REQUEST_TIMEOUT_SECONDS + " giây).", c);
            }
            if (c instanceof IOException) {
                throw (IOException) c;
            }
            throw new IOException(c != null ? c.getMessage() : e.getMessage(), c);
        }
    }

    private void heartbeat() {
        if (out == null) {
            return;
        }
        long t0 = System.nanoTime();
        send(Message.request(Command.PING)).thenAccept(r -> lastPingMillis = (System.nanoTime() - t0) / 1_000_000);
    }

    private void failPending(IOException e) {
        for (Long id : pending.keySet()) {
            CompletableFuture<Message> f = pending.remove(id);
            if (f != null) {
                f.completeExceptionally(e);
            }
        }
    }

    // ------------------------------------------------------------------ Đóng

    /** Đăng xuất (báo server hủy phiên) rồi đóng kết nối. */
    public void logout() {
        if (out != null && sessionToken != null) {
            try {
                sendAndWait(Message.request(Command.LOGOUT));
            } catch (IOException ignored) {
                // vẫn đóng kết nối
            }
        }
        close();
    }

    public void close() {
        closedByUser = true;
        sessionToken = null;
        background.shutdownNow();
        closeSocketQuietly();
    }

    private void closeSocketQuietly() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // bỏ qua
            }
        }
    }

    // ------------------------------------------------------------------ Getter / setter

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void setSessionToken(String token) {
        this.sessionToken = token;
    }

    public boolean isConnected() {
        return out != null;
    }

    public long getLastPingMillis() {
        return lastPingMillis;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
