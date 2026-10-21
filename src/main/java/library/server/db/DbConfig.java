package library.server.db;

import library.server.Log;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Cấu hình kết nối MySQL, đọc từ file {@code db.properties} cạnh nơi chạy server.
 * Lần đầu chạy nếu chưa có file, chương trình tự tạo file mẫu (XAMPP: root, mật khẩu trống).
 */
public class DbConfig {
    public static final String DEFAULT_FILE = "db.properties";
    private static final String DEFAULT_PARAMS =
            "useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String TEMPLATE = ""
            + "# ================================================================\n"
            + "#  Cấu hình kết nối MySQL - Thư viện Fix Bug\n"
            + "#  Sửa cho đúng với MySQL trên máy chạy server rồi chạy lại server.\n"
            + "#  XAMPP mặc định: user = root, mật khẩu để trống.\n"
            + "#  MySQL Installer: điền mật khẩu root đã đặt khi cài.\n"
            + "#  Database chưa có sẽ được tự tạo cùng các bảng và dữ liệu mẫu.\n"
            + "# ================================================================\n"
            + "db.host=localhost\n"
            + "db.port=3306\n"
            + "db.name=thuvien_fixbug\n"
            + "db.user=root\n"
            + "db.password=\n"
            + "# Số kết nối tối đa trong pool\n"
            + "db.poolSize=10\n"
            + "# (Nâng cao) Chuỗi JDBC đầy đủ. Nếu đặt thì bỏ qua host/port/name và KHÔNG tự tạo database.\n"
            + "#db.url=jdbc:mysql://localhost:3306/thuvien_fixbug?useSSL=false&allowPublicKeyRetrieval=true\n";

    private String host = "localhost";
    private int port = 3306;
    private String name = "thuvien_fixbug";
    private String user = "root";
    private String password = "";
    private int poolSize = 10;
    private String url;
    private Path source;

    public static DbConfig load(Path file) throws IOException {
        DbConfig c = new DbConfig();
        c.source = file.toAbsolutePath();
        if (!Files.exists(file)) {
            Files.write(file, TEMPLATE.getBytes(StandardCharsets.UTF_8));
            Log.info("Chưa có file cấu hình CSDL - đã tạo file mẫu %s", c.source);
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        c.host = p.getProperty("db.host", c.host).trim();
        c.port = Integer.parseInt(p.getProperty("db.port", String.valueOf(c.port)).trim());
        c.name = p.getProperty("db.name", c.name).trim();
        c.user = p.getProperty("db.user", c.user).trim();
        c.password = p.getProperty("db.password", c.password);
        c.poolSize = Math.max(1, Integer.parseInt(p.getProperty("db.poolSize", String.valueOf(c.poolSize)).trim()));
        String u = p.getProperty("db.url", "").trim();
        c.url = u.isEmpty() ? null : u;
        if (c.url == null && !c.name.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IOException("db.name chỉ được gồm chữ, số và dấu _ (đang là: " + c.name + ")");
        }
        return c;
    }

    /** Chuỗi kết nối tới database của ứng dụng. */
    public String jdbcUrl() {
        return url != null ? url : "jdbc:mysql://" + host + ":" + port + "/" + name + "?" + DEFAULT_PARAMS;
    }

    /** Chuỗi kết nối tới máy chủ MySQL (chưa chọn database) - dùng để tự tạo database. */
    public String serverUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/?" + DEFAULT_PARAMS;
    }

    public boolean isAutoCreateDatabase() {
        return url == null;
    }

    public String describe() {
        return url != null ? url.replaceAll("password=[^&;]*", "password=***") : user + "@" + host + ":" + port + "/" + name;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getName() {
        return name;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public Path getSource() {
        return source;
    }
}
