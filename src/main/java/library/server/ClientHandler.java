package library.server;

import library.common.EventType;
import library.common.Message;
import library.common.Protocol;
import library.server.session.Session;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Phục vụ MỘT client trên một luồng của pool "client-handler".
 * Vòng lặp: đọc yêu cầu → giao cho {@link RequestHandler} → gửi phản hồi.
 * Việc ghi ra socket được đồng bộ (synchronized) vì có 2 nguồn ghi: luồng này (phản hồi)
 * và luồng event-broadcaster (sự kiện đẩy).
 */
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final LibraryServer server;
    private final long connectionId;
    private final String remoteIp;
    private final long connectedAt = System.currentTimeMillis();
    private ObjectOutputStream out;
    private volatile Session session;
    private volatile boolean closed;

    public ClientHandler(Socket socket, LibraryServer server, long connectionId) {
        this.socket = socket;
        this.server = server;
        this.connectionId = connectionId;
        this.remoteIp = socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        Thread current = Thread.currentThread();
        String originalName = current.getName();
        current.setName(originalName + " #" + connectionId);
        Log.info("Kết nối mới #%d từ %s:%d", connectionId, remoteIp, socket.getPort());
        try {
            socket.setSoTimeout(Protocol.SERVER_READ_TIMEOUT_SECONDS * 1000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            synchronized (this) {
                out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                out.flush(); // gửi header trước để phía client tạo được ObjectInputStream
            }
            ObjectInputStream in = Protocol.newNetworkInput(new BufferedInputStream(socket.getInputStream()));
            server.registerClient(this);

            while (!closed) {
                Object obj = in.readObject();
                if (!(obj instanceof Message)) {
                    Log.warn("Gói tin không hợp lệ từ %s - đóng kết nối", remoteIp);
                    break;
                }
                Message request = (Message) obj;
                Message response = server.getRequestHandler().handle(this, request);
                send(response);
            }
        } catch (SocketTimeoutException e) {
            Log.info("Client #%d (%s) không phản hồi quá %d giây - đóng kết nối", connectionId, remoteIp,
                    Protocol.SERVER_READ_TIMEOUT_SECONDS);
        } catch (EOFException | SocketException e) {
            // client đóng kết nối - bình thường
        } catch (InvalidClassException e) {
            Log.warn("Chặn dữ liệu không được phép từ %s: %s", remoteIp, e.getMessage());
        } catch (IOException | ClassNotFoundException e) {
            if (!closed) {
                Log.warn("Lỗi kết nối với %s: %s", remoteIp, e);
            }
        } finally {
            close();
            server.unregisterClient(this);
            server.getSessions().detach(this);
            Log.info("Đóng kết nối #%d (%s)", connectionId, remoteIp);
            current.setName(originalName);
        }
    }

    /** Gửi một gói tin. An toàn khi gọi đồng thời từ nhiều luồng. */
    public synchronized boolean send(Message message) {
        if (closed || out == null) {
            return false;
        }
        try {
            out.writeObject(message);
            // reset() xóa bộ nhớ đệm tham chiếu của ObjectOutputStream: nếu không, đối tượng
            // đã gửi trước đó sẽ không được gửi lại nội dung mới (client nhận dữ liệu cũ) và gây rò rỉ bộ nhớ.
            out.reset();
            out.flush();
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    /** Gửi thông báo "server đầy" rồi đóng - gọi trên luồng accept khi pool đã hết chỗ. */
    void rejectBusy(int maxClients) {
        try {
            socket.setSoTimeout(2000);
            synchronized (this) {
                out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            }
            send(Message.event(EventType.SERVER_BUSY,
                    "Máy chủ đang phục vụ tối đa " + maxClients + " người dùng. Vui lòng thử lại sau."));
        } catch (IOException ignored) {
            // bỏ qua
        } finally {
            close();
        }
    }

    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // bỏ qua
        }
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public String getRemoteIp() {
        return remoteIp;
    }

    public long getConnectionId() {
        return connectionId;
    }

    public long getConnectedAt() {
        return connectedAt;
    }
}
