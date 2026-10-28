package library.server.auth;

import library.server.Log;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Cấu hình "Đăng nhập bằng Google" đọc từ file google.properties (cạnh db.properties).
 * Để trống Client ID / Client secret = tắt chức năng. Client secret chỉ nằm ở server, không gửi cho client.
 */
public final class GoogleConfig {
    public static final String DEFAULT_FILE = "google.properties";
    public static final String DEFAULT_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";

    private static final String TEMPLATE = ""
            + "# ================================================================\n"
            + "#  Đăng nhập bằng Google - " + library.common.Protocol.LIBRARY_NAME + "\n"
            + "#  1. Vào https://console.cloud.google.com, tạo (hoặc chọn) một project.\n"
            + "#  2. APIs & Services > OAuth consent screen: chọn External, điền tên ứng dụng và email;\n"
            + "#     ở mục Test users thêm Gmail của các thành viên được phép đăng nhập.\n"
            + "#  3. APIs & Services > Credentials > Create credentials > OAuth client ID,\n"
            + "#     Application type = Desktop app.\n"
            + "#  4. Dán Client ID và Client secret vào bên dưới rồi chạy lại server.\n"
            + "#  Để trống = tắt đăng nhập Google. KHÔNG đưa file này lên GitHub (có khóa bí mật).\n"
            + "# ================================================================\n"
            + "google.clientId=\n"
            + "google.clientSecret=\n"
            + "# (Tùy chọn) chỉ cho email thuộc tên miền này đăng nhập, VD: hutech.edu.vn\n"
            + "google.allowedDomain=\n";

    private String clientId = "";
    private String clientSecret = "";
    private String allowedDomain = "";
    private String authUrl = DEFAULT_AUTH_URL;
    private String tokenUrl = DEFAULT_TOKEN_URL;
    private Path source;

    public static GoogleConfig load(Path file) throws IOException {
        GoogleConfig c = new GoogleConfig();
        c.source = file.toAbsolutePath();
        if (!Files.exists(file)) {
            Files.write(file, TEMPLATE.getBytes(StandardCharsets.UTF_8));
            Log.info("Chưa có file cấu hình đăng nhập Google - đã tạo file mẫu %s", c.source);
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        c.clientId = p.getProperty("google.clientId", "").trim();
        c.clientSecret = p.getProperty("google.clientSecret", "").trim();
        c.allowedDomain = p.getProperty("google.allowedDomain", "").trim().toLowerCase();
        // Chỉ dùng khi kiểm thử (máy chủ Google giả lập)
        c.authUrl = p.getProperty("google.authUrl", DEFAULT_AUTH_URL).trim();
        c.tokenUrl = p.getProperty("google.tokenUrl", DEFAULT_TOKEN_URL).trim();
        return c;
    }

    /** Cấu hình rỗng (tắt) - dùng khi không đọc được file. */
    public static GoogleConfig disabled() {
        return new GoogleConfig();
    }

    public boolean isEnabled() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    public String getClientId() {
        return clientId;
    }

    String getClientSecret() {
        return clientSecret;
    }

    public String getAllowedDomain() {
        return allowedDomain;
    }

    public String getAuthUrl() {
        return authUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public Path getSource() {
        return source;
    }
}
