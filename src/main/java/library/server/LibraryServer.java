package library.server;

import library.common.EventType;
import library.common.Fmt;
import library.common.Message;
import library.server.auth.GoogleAuthService;
import library.server.auth.GoogleConfig;
import library.server.db.Database;
import library.server.db.DbConfig;
import library.server.db.DbException;
import library.server.net.DiscoveryResponder;
import library.server.service.BookingService;
import library.server.service.ContentService;
import library.server.service.ReputationService;
import library.server.service.UserService;
import library.server.session.SessionManager;
import library.server.thread.EventBroadcaster;
import library.server.thread.ServerThreadPools;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Máy chủ: lắp ráp các thành phần, nhận kết nối và phân phối cho pool luồng. */
public class LibraryServer {
    private final ServerConfig config;
    private final ServerThreadPools pools;
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final Database database;
    private final EventBroadcaster events;
    private final UserService users;
    private final BookingService bookings;
    private final ContentService content;
    private final ReputationService reputation;
    private final GoogleAuthService google;
    private final SessionManager sessions;
    private final RequestHandler requestHandler;
    private final AtomicLong connectionSeq = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();

    private DiscoveryResponder discovery;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private long startedAt;

    public LibraryServer(ServerConfig config) throws IOException {
        this.config = config;
        this.pools = new ServerThreadPools(config.getMaxClients());
        this.database = new Database(DbConfig.load(config.getDbConfigFile()));
        this.events = new EventBroadcaster(clients, pools.getEventExecutor());
        this.users = new UserService(database);
        this.bookings = new BookingService(database, events);
        this.content = new ContentService(database, events, bookings);
        this.reputation = new ReputationService(database, events);
        this.google = new GoogleAuthService(loadGoogleConfig(config));
        this.sessions = new SessionManager(config.getSessionTimeoutMinutes() * 60_000L,
                config.getReconnectGraceSeconds() * 1000L, pools.getEventExecutor());
        this.requestHandler = new RequestHandler(this);
    }

    public synchronized void start() throws IOException {
        try {
            database.start(); // kết nối MySQL, tạo bảng + dữ liệu mẫu nếu chưa có
        } catch (DbException e) {
            database.close();
            throw new IOException(e.getMessage(), e);
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.getPort()), 100); // lắng nghe trên mọi card mạng
        running = true;
        startedAt = System.currentTimeMillis();

        Thread acceptThread = new Thread(this::acceptLoop, "accept-loop");
        acceptThread.start();

        if (config.isDiscoveryEnabled()) {
            discovery = new DiscoveryResponder(config.getDiscoveryPort(), config.getPort(), config.getServerName());
            Thread t = new Thread(discovery, "lan-discovery");
            t.setDaemon(true);
            t.start();
        }

        // Dọn phiên: chỉ duyệt danh sách trong bộ nhớ, không truy vấn CSDL
        pools.scheduleRepeating("session-cleanup", sessions::expireSessions, 30, 30);
        // Lượt đặt: hẹn giờ theo mốc ca học (10 lần/ngày) thay vì quét CSDL mỗi 30 giây
        pools.scheduleAtMilestones("kiểm tra lượt đặt quá giờ check-in / hết ca", bookings::processTimeouts,
                BookingService::nextDeadline);
        // Khóa đặt chỗ tính theo ngày → chỉ cần mở khóa 1 lần ngay sau 0 giờ
        pools.scheduleAtMilestones("mở khóa đặt chỗ hết hạn (điểm uy tín)", reputation::releaseExpiredLocks,
                now -> now.toLocalDate().plusDays(1).atTime(0, 0, 5));

        Log.info("Server '%s' đang chạy tại cổng TCP %d", config.getServerName(), config.getPort());
        for (String ip : getLanAddresses()) {
            Log.info("  → Client trong LAN kết nối tới: %s:%d", ip, config.getPort());
        }
        Log.info("Tối đa %d client, phiên hết hạn sau %d phút không thao tác", config.getMaxClients(),
                config.getSessionTimeoutMinutes());
        GoogleConfig g = google.getConfig();
        if (g.isEnabled()) {
            Log.info("Đăng nhập bằng Google: ĐÃ BẬT (Client ID %s...%s)%s",
                    g.getClientId().substring(0, Math.min(8, g.getClientId().length())),
                    g.getClientId().contains(".apps.") ? ".apps.googleusercontent.com" : "",
                    g.getAllowedDomain().isEmpty() ? "" : ", chỉ email @" + g.getAllowedDomain());
        } else {
            Log.info("Đăng nhập bằng Google: chưa bật - điền Client ID / Client secret vào %s rồi chạy lại server",
                    g.getSource() != null ? g.getSource() : GoogleConfig.DEFAULT_FILE);
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    Log.error("Lỗi khi nhận kết nối", e);
                }
                continue;
            }
            ClientHandler handler = new ClientHandler(socket, this, connectionSeq.incrementAndGet());
            try {
                pools.getClientPool().execute(handler);
            } catch (RejectedExecutionException e) {
                rejectedConnections.incrementAndGet();
                Log.warn("Từ chối kết nối từ %s: đã đạt tối đa %d client", handler.getRemoteIp(),
                        config.getMaxClients());
                handler.rejectBusy(config.getMaxClients());
            }
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        Log.info("Đang dừng server...");
        Message bye = Message.event(EventType.SERVER_SHUTDOWN, "Máy chủ đang tạm dừng. Hệ thống sẽ tự kết nối lại khi máy chủ hoạt động.");
        for (ClientHandler c : clients) {
            c.send(bye);
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // bỏ qua
        }
        if (discovery != null) {
            discovery.stop();
        }
        for (ClientHandler c : clients) {
            c.close();
        }
        pools.shutdown();
        database.close();
        Log.info("Đã đóng kết nối MySQL. Server đã dừng.");
    }

    // ------------------------------------------------------------------ Quản lý client

    void registerClient(ClientHandler c) {
        clients.add(c);
    }

    void unregisterClient(ClientHandler c) {
        clients.remove(c);
    }

    public int getConnectedClients() {
        return clients.size();
    }

    // ------------------------------------------------------------------ Thống kê

    public LinkedHashMap<String, String> getStats() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        m.put("Tên server", config.getServerName());
        m.put("Thời gian hoạt động", Fmt.duration(System.currentTimeMillis() - startedAt));
        m.put("Kết nối TCP đang mở", String.valueOf(clients.size()));
        m.put("Phiên trực tuyến / chờ kết nối lại", sessions.countOnline() + " / " + sessions.countWaitingReconnect());
        m.put("Tổng kết nối đã nhận", String.valueOf(connectionSeq.get()));
        m.put("Kết nối bị từ chối (quá tải)", String.valueOf(rejectedConnections.get()));
        m.putAll(pools.stats());
        m.put("Tổng số luồng của JVM", String.valueOf(ManagementFactory.getThreadMXBean().getThreadCount()));
        m.put("Bộ nhớ đang dùng", usedMb + " MB / " + rt.maxMemory() / (1024 * 1024) + " MB");
        m.putAll(database.stats());
        try {
            m.put("Số tài khoản", String.valueOf(users.countUsers()));
            m.putAll(bookings.todayStats());
        } catch (DbException e) {
            m.put("Truy vấn thống kê", "LỖI: " + e.getMessage());
        }
        return m;
    }

    /** Danh sách địa chỉ IPv4 trong LAN của máy chủ (để hiển thị cho người dùng). */
    public static List<String> getLanAddresses() {
        List<String> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        list.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // bỏ qua
        }
        if (list.isEmpty()) {
            list.add("127.0.0.1");
        }
        return list;
    }

    // ------------------------------------------------------------------ Getter

    public ServerConfig getConfig() {
        return config;
    }

    public SessionManager getSessions() {
        return sessions;
    }

    public UserService getUsers() {
        return users;
    }

    public GoogleAuthService getGoogle() {
        return google;
    }

    /** google.properties nằm cùng thư mục với db.properties. */
    private static GoogleConfig loadGoogleConfig(ServerConfig config) {
        try {
            return GoogleConfig.load(config.getDbConfigFile().toAbsolutePath().resolveSibling(GoogleConfig.DEFAULT_FILE));
        } catch (IOException e) {
            Log.warn("Không đọc được cấu hình đăng nhập Google: %s", e.getMessage());
            return GoogleConfig.disabled();
        }
    }

    public ContentService getContent() {
        return content;
    }

    public BookingService getBookings() {
        return bookings;
    }

    public ReputationService getReputation() {
        return reputation;
    }

    public RequestHandler getRequestHandler() {
        return requestHandler;
    }

    public Database getDatabase() {
        return database;
    }

    public boolean isRunning() {
        return running;
    }
}
