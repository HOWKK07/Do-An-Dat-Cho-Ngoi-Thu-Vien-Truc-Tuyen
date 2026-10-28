package library.server.auth;

import library.server.Log;
import library.server.service.ServiceException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * ĐĂNG NHẬP BẰNG GOOGLE (OAuth 2.0 / OpenID Connect cho ứng dụng máy tính, có PKCE).
 *
 * <pre>
 *  Client                         Trình duyệt / Google                  Server (lớp này)
 *  1. GOOGLE_CONFIG  ─────────────────────────────────────────────────► trả Client ID (công khai)
 *  2. mở trình duyệt ─► Google: chọn tài khoản, đồng ý
 *                       Google chuyển về http://127.0.0.1:PORT/?code=...  (client nghe cổng tạm)
 *  3. GOOGLE_LOGIN {code, code_verifier, redirect_uri} ───────────────► đổi code lấy ID token tại Google
 *                                                                       (dùng Client secret - chỉ server biết),
 *                                                                       kiểm tra iss / aud / exp / email
 *  4. ◄──────── đăng nhập luôn nếu tài khoản Google đã liên kết; nếu chưa → trả "vé" để tạo tài khoản
 *               mới hoặc liên kết với tài khoản đã có (GOOGLE_COMPLETE)
 * </pre>
 */
public class GoogleAuthService {
    private static final long TICKET_TTL_MS = 10 * 60_000L;
    private static final Pattern LOOPBACK = Pattern.compile("^http://(127\\.0\\.0\\.1|localhost):\\d{1,5}(/[^?#]*)?$");

    private final GoogleConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private static final class Pending {
        final GoogleIdentity identity;
        final long expiresAt;

        Pending(GoogleIdentity identity, long expiresAt) {
            this.identity = identity;
            this.expiresAt = expiresAt;
        }
    }

    public GoogleAuthService(GoogleConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public GoogleConfig getConfig() {
        return config;
    }

    /** Bước 3: đổi authorization code lấy ID token tại Google rồi xác minh. */
    public GoogleIdentity exchange(String code, String codeVerifier, String redirectUri) {
        if (!config.isEnabled()) {
            throw new ServiceException("Máy chủ chưa bật đăng nhập bằng Google.");
        }
        if (isBlank(code) || isBlank(codeVerifier) || isBlank(redirectUri)) {
            throw new ServiceException("Thiếu thông tin đăng nhập Google.");
        }
        if (!LOOPBACK.matcher(redirectUri).matches()) {
            throw new ServiceException("Địa chỉ chuyển hướng không hợp lệ.");
        }
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&code_verifier=" + enc(codeVerifier)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(config.getClientId())
                + "&client_secret=" + enc(config.getClientSecret());
        HttpRequest req = HttpRequest.newBuilder(URI.create(config.getTokenUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("Không gọi được máy chủ Google: %s", e.toString());
            throw new ServiceException("Máy chủ thư viện không kết nối được tới Google để xác minh đăng nhập. "
                    + "Hãy kiểm tra Internet của máy chạy server.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Đăng nhập Google bị gián đoạn.");
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(resp.body());
        } catch (RuntimeException e) {
            throw new ServiceException("Phản hồi từ Google không hợp lệ (HTTP " + resp.statusCode() + ").");
        }
        if (resp.statusCode() != 200) {
            String error = str(body.get("error"));
            String desc = str(body.get("error_description"));
            Log.warn("Google từ chối đổi mã đăng nhập: %s - %s", error, desc);
            throw new ServiceException("Google từ chối đăng nhập" + (desc != null ? ": " + desc
                    : error != null ? " (" + error + ")" : "") + ". Vui lòng thử lại.");
        }
        String idToken = str(body.get("id_token"));
        if (idToken == null) {
            throw new ServiceException("Google không trả về thông tin tài khoản (thiếu id_token).");
        }
        return verifyIdToken(idToken);
    }

    /**
     * Kiểm tra các thông tin (claim) trong ID token. Token được nhận TRỰC TIẾP từ máy chủ token của Google qua HTTPS,
     * kèm Client secret, nên theo chuẩn OpenID Connect (Core 1.0, mục 3.1.3.7) có thể dựa vào HTTPS thay cho việc
     * kiểm chữ ký; vẫn bắt buộc kiểm: nơi phát hành (iss), ứng dụng nhận (aud), hạn dùng (exp), email đã xác minh.
     */
    public GoogleIdentity verifyIdToken(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new ServiceException("Thông tin tài khoản Google không hợp lệ.");
        }
        Map<String, Object> claims;
        try {
            claims = Json.parseObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ServiceException("Thông tin tài khoản Google không đọc được.");
        }
        String iss = str(claims.get("iss"));
        if (!"https://accounts.google.com".equals(iss) && !"accounts.google.com".equals(iss)) {
            throw new ServiceException("Thông tin đăng nhập không phải do Google phát hành.");
        }
        Object aud = claims.get("aud");
        boolean audOk = config.getClientId().equals(aud)
                || aud instanceof List && ((List<?>) aud).contains(config.getClientId());
        if (!audOk) {
            throw new ServiceException("Thông tin đăng nhập Google không dành cho ứng dụng này.");
        }
        Object exp = claims.get("exp");
        if (!(exp instanceof Number) || ((Number) exp).longValue() * 1000 < System.currentTimeMillis() - 60_000) {
            throw new ServiceException("Phiên đăng nhập Google đã hết hạn, vui lòng thử lại.");
        }
        String sub = str(claims.get("sub"));
        String email = str(claims.get("email"));
        Object verified = claims.get("email_verified");
        if (isBlank(sub) || isBlank(email)) {
            throw new ServiceException("Google không cung cấp email của tài khoản.");
        }
        if (!Boolean.TRUE.equals(verified) && !"true".equals(verified)) {
            throw new ServiceException("Email Google " + email + " chưa được xác minh.");
        }
        email = email.toLowerCase();
        String domain = config.getAllowedDomain();
        if (!domain.isEmpty() && !email.endsWith("@" + domain) && !domain.equals(str(claims.get("hd")))) {
            throw new ServiceException("Thư viện chỉ cho đăng nhập bằng email @" + domain + ".");
        }
        String name = str(claims.get("name"));
        return new GoogleIdentity(sub, email, isBlank(name) ? email : name.trim());
    }

    // ------------------------------------------------------------------ Vé chờ hoàn tất (tài khoản Google chưa liên kết)

    public String createTicket(GoogleIdentity identity) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expiresAt < now);
        byte[] b = new byte[24];
        random.nextBytes(b);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        pending.put(ticket, new Pending(identity, now + TICKET_TTL_MS));
        return ticket;
    }

    /** Lấy danh tính đang chờ (chưa xóa vé - nếu tạo tài khoản lỗi thì người dùng sửa và thử lại được). */
    public GoogleIdentity peekTicket(String ticket) {
        Pending p = ticket == null ? null : pending.get(ticket);
        if (p == null || p.expiresAt < System.currentTimeMillis()) {
            throw new ServiceException("Phiên đăng nhập Google đã hết hạn, vui lòng bấm \"Đăng nhập bằng Google\" lại.");
        }
        return p.identity;
    }

    public void consumeTicket(String ticket) {
        if (ticket != null) {
            pending.remove(ticket);
        }
    }

    // ------------------------------------------------------------------ Tiện ích

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
