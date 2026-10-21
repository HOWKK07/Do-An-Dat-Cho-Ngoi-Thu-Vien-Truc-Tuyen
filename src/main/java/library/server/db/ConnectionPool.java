package library.server.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * POOL KẾT NỐI MySQL tự viết (không cần thư viện ngoài).
 * <ul>
 *   <li>Mở kết nối JDBC rất tốn thời gian (bắt tay TCP, xác thực) → dùng lại kết nối thay vì mở mới mỗi yêu cầu.</li>
 *   <li>Nhiều luồng client-handler mượn/trả kết nối đồng thời qua {@link BlockingQueue} (an toàn đa luồng).</li>
 *   <li>Tối đa {@code poolSize} kết nối; hết kết nối thì luồng phải CHỜ (tối đa 10 giây) chứ không mở thêm
 *       → bảo vệ MySQL khỏi bị quá tải khi có nhiều client.</li>
 *   <li>Kết nối để lâu không dùng được kiểm tra lại (isValid) trước khi giao, tự thay thế kết nối hỏng.</li>
 * </ul>
 */
class ConnectionPool {
    private static final long VALIDATE_AFTER_IDLE_MS = 30_000;
    private static final long BORROW_TIMEOUT_MS = 10_000;

    /** Kết nối kèm thời điểm dùng gần nhất. */
    static final class Pooled {
        final Connection conn;
        long lastUsed = System.currentTimeMillis();

        Pooled(Connection conn) {
            this.conn = conn;
        }
    }

    private final DbConfig config;
    private final int maxSize;
    private final BlockingQueue<Pooled> idle;
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger inUse = new AtomicInteger();
    private final AtomicLong borrowed = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();
    private volatile boolean closed;

    ConnectionPool(DbConfig config) {
        this.config = config;
        this.maxSize = config.getPoolSize();
        this.idle = new ArrayBlockingQueue<>(maxSize);
    }

    Pooled borrow() throws SQLException {
        if (closed) {
            throw new SQLException("Pool kết nối đã đóng");
        }
        borrowed.incrementAndGet();
        while (true) {
            Pooled p = idle.poll();
            if (p == null) {
                if (tryReserveSlot()) {
                    try {
                        p = new Pooled(open());
                    } catch (SQLException e) {
                        total.decrementAndGet();
                        throw e;
                    }
                } else {
                    waited.incrementAndGet(); // pool đầy → chờ luồng khác trả kết nối
                    try {
                        p = idle.poll(BORROW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Bị gián đoạn khi chờ kết nối CSDL", e);
                    }
                    if (p == null) {
                        throw new SQLTransientConnectionException("Tất cả " + maxSize
                                + " kết nối CSDL đang bận, máy chủ quá tải. Vui lòng thử lại.");
                    }
                }
            }
            if (System.currentTimeMillis() - p.lastUsed > VALIDATE_AFTER_IDLE_MS && !isValid(p.conn)) {
                discard(p); // kết nối chết (MySQL khởi động lại, wait_timeout...) → bỏ, lấy cái khác
                continue;
            }
            inUse.incrementAndGet();
            return p;
        }
    }

    void release(Pooled p, boolean broken) {
        inUse.decrementAndGet();
        if (broken || closed || !idle.offer(p)) {
            discard(p);
            return;
        }
        p.lastUsed = System.currentTimeMillis();
    }

    Connection open() throws SQLException {
        return DriverManager.getConnection(config.jdbcUrl(), config.getUser(), config.getPassword());
    }

    private boolean tryReserveSlot() {
        while (true) {
            int n = total.get();
            if (n >= maxSize) {
                return false;
            }
            if (total.compareAndSet(n, n + 1)) {
                return true;
            }
        }
    }

    private static boolean isValid(Connection c) {
        try {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private void discard(Pooled p) {
        total.decrementAndGet();
        try {
            p.conn.close();
        } catch (SQLException ignored) {
            // bỏ qua
        }
    }

    void close() {
        closed = true;
        Pooled p;
        while ((p = idle.poll()) != null) {
            discard(p);
        }
    }

    LinkedHashMap<String, String> stats() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("Pool MySQL: đang dùng / đã mở / tối đa", inUse.get() + " / " + total.get() + " / " + maxSize);
        m.put("Pool MySQL: lượt mượn / phải chờ", borrowed.get() + " / " + waited.get());
        return m;
    }
}
