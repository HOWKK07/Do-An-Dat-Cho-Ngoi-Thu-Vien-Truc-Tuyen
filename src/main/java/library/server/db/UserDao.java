package library.server.db;

import library.common.Reputation;
import library.common.Role;
import library.common.StudentInfo;
import library.common.UserInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Truy vấn bảng {@code users}. */
public final class UserDao {

    /** Một dòng của bảng users (có cả salt + mật khẩu đã băm - không bao giờ gửi cho client). */
    public static final class UserRecord {
        public final String username;
        public final String fullName;
        public final String studentId;
        public final Role role;
        public final String salt;
        public final String passwordHash;

        UserRecord(String username, String fullName, String studentId, Role role, String salt, String passwordHash) {
            this.username = username;
            this.fullName = fullName;
            this.studentId = studentId;
            this.role = role;
            this.salt = salt;
            this.passwordHash = passwordHash;
        }

        public UserInfo toInfo() {
            return new UserInfo(username, fullName, studentId, role);
        }
    }

    /** Vai trò + điểm uy tín của một tài khoản. */
    public static final class Standing {
        public final String username;
        public final String fullName;
        public final Role role;
        public final int score;
        public final LocalDate lockedUntil;

        Standing(String username, String fullName, Role role, int score, LocalDate lockedUntil) {
            this.username = username;
            this.fullName = fullName;
            this.role = role;
            this.score = score;
            this.lockedUntil = lockedUntil;
        }

        public Reputation.Level level(LocalDate today) {
            return role == Role.ADMIN ? Reputation.Level.EXCELLENT : Reputation.levelOf(score, lockedUntil, today);
        }
    }

    private UserDao() {
    }

    /** Đọc điểm uy tín (không khóa). */
    public static Standing standing(Connection c, String username) throws SQLException {
        return readStanding(c, username, "");
    }

    /** Đọc điểm uy tín và KHÓA dòng tới hết transaction (các thao tác của cùng một người được xử lý tuần tự). */
    public static Standing standingForUpdate(Connection c, String username) throws SQLException {
        return readStanding(c, username, " FOR UPDATE");
    }

    private static Standing readStanding(Connection c, String username, String suffix) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT username, full_name, role, reputation, locked_until "
                + "FROM users WHERE username = ?" + suffix)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Standing(rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getInt(4),
                        rs.getObject(5, LocalDate.class));
            }
        }
    }

    public static void setReputation(Connection c, String username, int score, LocalDate lockedUntil)
            throws SQLException {
        Sql.update(c, "UPDATE users SET reputation = ?, locked_until = ? WHERE username = ?",
                score, lockedUntil, username);
    }

    /** Tài khoản đã hết thời gian tạm khóa (để mở lại). */
    public static List<String> withExpiredLock(Connection c, LocalDate today) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT username FROM users WHERE locked_until IS NOT NULL AND locked_until <= ?")) {
            ps.setObject(1, today);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
            }
        }
        return list;
    }

    /** Danh sách sinh viên kèm điểm uy tín, số lần không đến, tổng lượt tự đặt. */
    public static List<StudentInfo> listStudents(Connection c, LocalDate today) throws SQLException {
        List<StudentInfo> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT u.username, u.full_name, u.student_id, u.reputation, "
                + "u.locked_until, "
                + "(SELECT COUNT(*) FROM bookings b WHERE b.username = u.username AND b.walk_in = FALSE "
                + "AND b.status = 'EXPIRED') AS no_shows, "
                + "(SELECT COUNT(*) FROM bookings b WHERE b.username = u.username AND b.walk_in = FALSE) AS total "
                + "FROM users u WHERE u.role = 'STUDENT' ORDER BY u.reputation, u.username");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int score = rs.getInt("reputation");
                LocalDate locked = rs.getObject("locked_until", LocalDate.class);
                list.add(new StudentInfo(rs.getString("username"), rs.getString("full_name"),
                        rs.getString("student_id"), score, Reputation.levelOf(score, locked, today), locked,
                        rs.getInt("no_shows"), rs.getInt("total")));
            }
        }
        return list;
    }

    public static UserRecord find(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT username, full_name, student_id, role, salt, password_hash FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new UserRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        Role.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6));
            }
        }
    }

    /** Tài khoản đã liên kết với tài khoản Google có mã {@code sub}, hoặc null. */
    public static UserRecord findByGoogleSub(Connection c, String sub) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT username, full_name, student_id, role, salt, password_hash FROM users WHERE google_sub = ?")) {
            ps.setString(1, sub);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new UserRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        Role.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6));
            }
        }
    }

    /** Mã Google đang liên kết với tài khoản (null nếu chưa liên kết). */
    public static String googleSubOf(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT google_sub FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static void linkGoogle(Connection c, String username, String sub, String email) throws SQLException {
        Sql.update(c, "UPDATE users SET google_sub = ?, email = ? WHERE username = ?", sub, email, username);
    }

    public static boolean studentIdExists(Connection c, String studentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE student_id = ?")) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void insert(Connection c, String username, String fullName, String studentId, Role role,
                              String salt, String hash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO users "
                + "(username, full_name, student_id, role, salt, password_hash, created_at, reputation) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, username);
            ps.setString(2, fullName);
            ps.setString(3, studentId == null || studentId.isEmpty() ? null : studentId);
            ps.setString(4, role.name());
            ps.setString(5, salt);
            ps.setString(6, hash);
            ps.setObject(7, LocalDateTime.now().withNano(0));
            ps.setInt(8, Reputation.START); // tài khoản mới bắt đầu với 80 điểm uy tín
            ps.executeUpdate();
        }
    }

    public static int count(Connection c) throws SQLException {
        return Sql.count(c, "SELECT COUNT(*) FROM users");
    }
}
