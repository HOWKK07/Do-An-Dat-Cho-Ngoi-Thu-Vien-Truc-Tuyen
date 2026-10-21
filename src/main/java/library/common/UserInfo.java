package library.common;

import java.io.Serializable;

/** Thông tin người dùng gửi cho client (không chứa mật khẩu). */
public class UserInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String fullName;
    private final String studentId;
    private final Role role;

    public UserInfo(String username, String fullName, String studentId, Role role) {
        this.username = username;
        this.fullName = fullName;
        this.studentId = studentId;
        this.role = role;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public String getStudentId() {
        return studentId;
    }

    public Role getRole() {
        return role;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    @Override
    public String toString() {
        return fullName + " (" + username + ")";
    }
}
