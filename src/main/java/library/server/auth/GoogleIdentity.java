package library.server.auth;

/** Danh tính Google đã được xác minh (lấy từ ID token). */
public final class GoogleIdentity {
    /** Mã định danh cố định của tài khoản Google (claim "sub") - dùng để liên kết, không đổi kể cả khi đổi email. */
    public final String sub;
    public final String email;
    public final String name;

    public GoogleIdentity(String sub, String email, String name) {
        this.sub = sub;
        this.email = email;
        this.name = name;
    }

    /** Gợi ý tên đăng nhập từ email: "nguyen.van.a@gmail.com" → "nguyen.van.a". */
    public String suggestedUsername() {
        String local = email == null ? "" : email.split("@")[0].toLowerCase();
        local = local.replaceAll("[^a-z0-9_.]", "");
        if (local.length() > 20) {
            local = local.substring(0, 20);
        }
        return local.length() >= 3 ? local : "sv" + Math.abs(sub.hashCode() % 100000);
    }
}
