package library.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Thông tin một phiên đăng nhập, gửi cho quản trị viên xem.
 * Không chứa token bí mật - chỉ có mã phiên công khai (S0001...) để thao tác.
 */
public class SessionInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String username;
    private final String fullName;
    private final Role role;
    private final String ip;
    private final boolean online;
    private final LocalDateTime loginAt;
    private final LocalDateTime lastActive;

    public SessionInfo(String id, String username, String fullName, Role role, String ip, boolean online,
                       LocalDateTime loginAt, LocalDateTime lastActive) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.ip = ip;
        this.online = online;
        this.loginAt = loginAt;
        this.lastActive = lastActive;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public String getIp() {
        return ip;
    }

    public boolean isOnline() {
        return online;
    }

    public String getStatusLabel() {
        return online ? "Trực tuyến" : "Chờ kết nối lại";
    }

    public LocalDateTime getLoginAt() {
        return loginAt;
    }

    public LocalDateTime getLastActive() {
        return lastActive;
    }
}
