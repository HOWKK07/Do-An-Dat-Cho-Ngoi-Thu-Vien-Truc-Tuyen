package library.server.db;

import library.server.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;

/**
 * Điểm truy cập MySQL của server.
 * <ul>
 *   <li>{@link #query}: chạy câu lệnh đọc (auto-commit).</li>
 *   <li>{@link #tx}: chạy trong TRANSACTION - lỗi ở bất kỳ bước nào sẽ ROLLBACK toàn bộ;
 *       nếu MySQL báo deadlock thì tự thử lại (tối đa 3 lần).</li>
 * </ul>
 * Mọi kết nối được mượn từ {@link ConnectionPool} và luôn được trả lại trong khối finally.
 */
public class Database implements AutoCloseable {

    /** Một đơn vị công việc dùng một kết nối JDBC. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    private static final int MAX_ATTEMPTS = 3;

    private final DbConfig config;
    private final ConnectionPool pool;

    public Database(DbConfig config) {
        this.config = config;
        this.pool = new ConnectionPool(config);
    }

    /** Kết nối MySQL, tạo database + bảng nếu chưa có, thêm dữ liệu mẫu nếu bảng trống. */
    public void start() {
        try {
            if (config.isAutoCreateDatabase()) {
                createDatabaseIfMissing();
            }
            try (Connection c = pool.open()) {
                Log.info("Đã kết nối MySQL %s (%s %s)", config.describe(),
                        c.getMetaData().getDatabaseProductName(), c.getMetaData().getDatabaseProductVersion());
            }
        } catch (SQLException e) {
            throw DbException.wrap(e, config);
        }
        boolean migrated = tx(c -> {
            Schema.create(c);
            return Schema.migrate(c);
        });
        if (migrated) {
            Log.info("Đã nâng cấp cấu trúc CSDL lên phiên bản mới (đặt chỗ tại quầy, điểm uy tín, ngồi ghép, đăng nhập Google) - dữ liệu cũ giữ nguyên");
        }
        int rows = tx(DataSeeder::seedIfEmpty);
        if (rows > 0) {
            Log.info("CSDL trống - đã thêm %d dòng dữ liệu mẫu (tài khoản, khu vực, chỗ ngồi, thông báo)", rows);
        }
        for (String update : tx(DataSeeder::applyDataUpdates)) {
            Log.info("Cập nhật dữ liệu: %s", update);
        }
        int renamed = tx(DataSeeder::renameLegacyLibrary);
        if (renamed > 0) {
            Log.info("Đã đổi tên \"Thư viện Fix Bug\" thành \"%s\" trong dữ liệu cũ (%d dòng)",
                    library.common.Protocol.LIBRARY_NAME, renamed);
        }
    }

    private void createDatabaseIfMissing() throws SQLException {
        try (Connection c = DriverManager.getConnection(config.serverUrl(), config.getUser(), config.getPassword());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + config.getName()
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    /** Chạy câu lệnh không cần transaction (chủ yếu là đọc). */
    public <T> T query(SqlWork<T> work) {
        ConnectionPool.Pooled p = borrow();
        boolean broken = false;
        try {
            return work.run(p.conn);
        } catch (SQLException e) {
            broken = isConnectionError(e);
            throw DbException.wrap(e, config);
        } finally {
            pool.release(p, broken);
        }
    }

    /** Chạy trong một transaction: commit khi thành công, rollback khi có lỗi (kể cả lỗi nghiệp vụ). */
    public <T> T tx(SqlWork<T> work) {
        for (int attempt = 1; ; attempt++) {
            ConnectionPool.Pooled p = borrow();
            Connection c = p.conn;
            boolean broken = false;
            try {
                c.setAutoCommit(false);
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException e) {
                rollbackQuietly(c);
                broken = isConnectionError(e);
                if ("40001".equals(e.getSQLState()) && attempt < MAX_ATTEMPTS) {
                    Log.warn("MySQL báo deadlock, thử lại transaction lần %d", attempt + 1);
                    continue;
                }
                throw DbException.wrap(e, config);
            } catch (RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException e) {
                    broken = true;
                }
                pool.release(p, broken);
            }
        }
    }

    private ConnectionPool.Pooled borrow() {
        try {
            return pool.borrow();
        } catch (SQLException e) {
            throw DbException.wrap(e, config);
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // kết nối có thể đã đứt
        }
    }

    private static boolean isConnectionError(SQLException e) {
        String s = e.getSQLState();
        return s != null && s.startsWith("08");
    }

    /** Kiểm tra nhanh MySQL còn hoạt động không. */
    public String ping() {
        return query(c -> {
            try (Statement st = c.createStatement()) {
                st.executeQuery("SELECT 1").close();
            }
            return "Kết nối MySQL bình thường (" + config.describe() + ")";
        });
    }

    public LinkedHashMap<String, String> stats() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("Cơ sở dữ liệu MySQL", config.describe());
        m.putAll(pool.stats());
        return m;
    }

    public DbConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        pool.close();
    }
}
