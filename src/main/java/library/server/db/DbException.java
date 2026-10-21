package library.server.db;

import java.sql.SQLException;

/** Lỗi truy cập cơ sở dữ liệu, kèm thông điệp tiếng Việt dễ hiểu. */
public class DbException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DbException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Chuyển lỗi JDBC thành thông điệp hướng dẫn cách khắc phục. */
    public static DbException wrap(SQLException e, DbConfig cfg) {
        return new DbException(describe(e, cfg), e);
    }

    static String describe(SQLException e, DbConfig cfg) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        String file = cfg.getSource() != null ? cfg.getSource().getFileName().toString() : DbConfig.DEFAULT_FILE;
        if (msg.contains("No suitable driver")) {
            return "Thiếu thư viện MySQL Connector/J (mysql-connector-j-8.4.0.jar). "
                    + "Chạy build.bat để tải về, hoặc trong NetBeans chuột phải project → Build with Dependencies.";
        }
        if (e.getErrorCode() == 1045 || state.equals("28000")) {
            return "MySQL từ chối đăng nhập với tài khoản '" + cfg.getUser()
                    + "'. Hãy sửa db.user / db.password trong " + file + ".";
        }
        if (e.getErrorCode() == 1049) {
            return "Chưa có database '" + cfg.getName() + "' và không tự tạo được. "
                    + "Hãy import file database/thuvien_fixbug.sql bằng phpMyAdmin hoặc MySQL Workbench.";
        }
        if (state.startsWith("08")) {
            return "Không kết nối được MySQL tại " + cfg.getHost() + ":" + cfg.getPort()
                    + ". Hãy bật MySQL (XAMPP → Start MySQL, hoặc dịch vụ MySQL80) và kiểm tra " + file + ".";
        }
        return msg;
    }
}
