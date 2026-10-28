package library.client.net;

import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Phía client của "Đăng nhập bằng Google" (OAuth 2.0 cho ứng dụng máy tính, luồng loopback + PKCE):
 * <ol>
 *   <li>Mở một cổng tạm trên 127.0.0.1 (chỉ máy này truy cập được).</li>
 *   <li>Mở trình duyệt tới trang đăng nhập Google, kèm {@code code_challenge} (PKCE) và {@code state} ngẫu nhiên.</li>
 *   <li>Google chuyển trình duyệt về http://127.0.0.1:PORT/?code=...&amp;state=... → nhận mã xác thực.</li>
 * </ol>
 * Client KHÔNG giữ Client secret: mã xác thực + {@code code_verifier} được gửi lên server để server đổi lấy thông tin
 * tài khoản tại Google. Kẻ khác có lấy được mã cũng không dùng được vì không có {@code code_verifier}.
 */
public final class GoogleSignIn {

    /** Kết quả nhận được từ trình duyệt. */
    public static final class Result {
        public final String code;
        public final String codeVerifier;
        public final String redirectUri;

        Result(String code, String codeVerifier, String redirectUri) {
            this.code = code;
            this.codeVerifier = codeVerifier;
            this.redirectUri = redirectUri;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

    /** Người dùng bấm "Hủy" trong lúc đang chờ trình duyệt. */
    public void cancel() {
        callback.completeExceptionally(new CancellationException());
    }

    /**
     * Chạy toàn bộ bước ở trình duyệt (chặn tới khi xong) - KHÔNG gọi trên luồng giao diện.
     *
     * @param opener cách mở trình duyệt (mặc định {@link #openBrowser})
     */
    public Result run(String authUrl, String clientId, Consumer<URI> opener, long timeoutSeconds) throws IOException {
        String verifier = randomToken(48);
        String challenge = base64Url(sha256(verifier));
        String state = randomToken(24);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            boolean relevant = q.containsKey("code") || q.containsKey("error");
            boolean ok = q.containsKey("code") && state.equals(q.get("state"));
            byte[] page = page(ok, q.get("error")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(relevant ? 200 : 404, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
            if (relevant) {
                callback.complete(q);
            }
        });
        server.start();
        try {
            URI url = URI.create(authUrl + "?client_id=" + enc(clientId)
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + enc("openid email profile")
                    + "&code_challenge=" + challenge
                    + "&code_challenge_method=S256"
                    + "&state=" + state
                    + "&prompt=select_account");
            opener.accept(url);
            Map<String, String> q = callback.get(timeoutSeconds, TimeUnit.SECONDS);
            String error = q.get("error");
            if (error != null) {
                throw new IOException("access_denied".equals(error) ? "Bạn đã hủy đăng nhập Google."
                        : "Google báo lỗi khi đăng nhập: " + error);
            }
            if (!state.equals(q.get("state"))) {
                throw new IOException("Phản hồi đăng nhập Google không hợp lệ (mã state không khớp).");
            }
            return new Result(q.get("code"), verifier, redirectUri);
        } catch (TimeoutException e) {
            throw new IOException("Hết thời gian chờ đăng nhập Google (" + timeoutSeconds / 60 + " phút).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Đăng nhập Google bị gián đoạn.");
        } catch (ExecutionException | CancellationException e) {
            throw new IOException("Đã hủy đăng nhập Google.");
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            server.stop(0);
        }
    }

    /** Mở trình duyệt mặc định của máy. */
    public static void openBrowser(URI uri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return;
            }
        } catch (IOException | RuntimeException e) {
            // rơi xuống thông báo bên dưới
        }
        throw new UncheckedIOException(new IOException(
                "Không tự mở được trình duyệt trên máy này. Hãy dán đường dẫn sau vào trình duyệt: " + uri));
    }

    // ------------------------------------------------------------------ Tiện ích

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return base64Url(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static String page(boolean ok, String error) {
        String title = ok ? "Đăng nhập Google thành công" : "Đăng nhập Google chưa thành công";
        String text = ok ? "Bạn có thể đóng tab này và quay lại ứng dụng Thư viện."
                : "access_denied".equals(error) ? "Bạn đã hủy đăng nhập. Có thể đóng tab này."
                : "Có lỗi xảy ra. Hãy đóng tab này và thử lại trong ứng dụng.";
        return "<!doctype html><html lang='vi'><head><meta charset='utf-8'><title>" + title + "</title></head>"
                + "<body style='font-family:system-ui,sans-serif;background:#F3F5F8;display:flex;align-items:center;"
                + "justify-content:center;height:90vh;margin:0'><div style='background:#fff;padding:32px 40px;"
                + "border-radius:12px;border:1px solid #DDE2E8;text-align:center'><h2 style='color:"
                + (ok ? "#1E8E4F" : "#C93C3C") + "'>" + title + "</h2><p style='color:#4B5563'>" + text
                + "</p></div></body></html>";
    }
}
