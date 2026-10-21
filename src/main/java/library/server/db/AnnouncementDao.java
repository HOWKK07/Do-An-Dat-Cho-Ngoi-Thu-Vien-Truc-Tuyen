package library.server.db;

import library.common.Announcement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Truy vấn bảng {@code announcements} (thông báo). Mã hiển thị dạng TB0001. */
public final class AnnouncementDao {

    private AnnouncementDao() {
    }

    public static String formatId(int id) {
        return String.format("TB%04d", id);
    }

    public static int parseId(String id) {
        try {
            return Integer.parseInt(id.trim().replaceFirst("(?i)^TB", ""));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static List<Announcement> list(Connection c) throws SQLException {
        List<Announcement> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, title, content, author, created_at FROM announcements ORDER BY created_at DESC, id DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Announcement(formatId(rs.getInt("id")), rs.getString("title"), rs.getString("content"),
                        rs.getString("author"), rs.getObject("created_at", LocalDateTime.class)));
            }
        }
        return list;
    }

    public static Announcement insert(Connection c, String title, String content, String author) throws SQLException {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO announcements (title, content, author, created_at) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            Sql.bind(ps, title, content, author, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Announcement(formatId(keys.getInt(1)), title, content, author, now);
            }
        }
    }

    public static int delete(Connection c, String id) throws SQLException {
        return Sql.update(c, "DELETE FROM announcements WHERE id = ?", parseId(id));
    }
}
