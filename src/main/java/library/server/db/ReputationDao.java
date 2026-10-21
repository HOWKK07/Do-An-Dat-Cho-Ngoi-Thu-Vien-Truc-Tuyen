package library.server.db;

import library.common.ReputationEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Truy vấn bảng {@code reputation_log} (lịch sử cộng / trừ điểm uy tín). */
public final class ReputationDao {

    private ReputationDao() {
    }

    public static void insert(Connection c, String username, int delta, int scoreAfter, String reason,
                              String bookingId, LocalDateTime at) throws SQLException {
        Integer id = bookingId == null ? null : BookingDao.parseId(bookingId);
        Sql.update(c, "INSERT INTO reputation_log (username, delta, score_after, reason, booking_id, created_at) "
                        + "VALUES (?,?,?,?,?,?)", username, delta, scoreAfter,
                reason.length() > 255 ? reason.substring(0, 255) : reason, id != null && id > 0 ? id : null, at);
    }

    public static List<ReputationEntry> history(Connection c, String username, int limit) throws SQLException {
        List<ReputationEntry> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT delta, score_after, reason, booking_id, created_at "
                + "FROM reputation_log WHERE username = ? ORDER BY created_at DESC, id DESC LIMIT " + limit)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int bookingId = rs.getInt("booking_id");
                    list.add(new ReputationEntry(rs.getInt("delta"), rs.getInt("score_after"), rs.getString("reason"),
                            rs.wasNull() ? null : BookingDao.formatId(bookingId),
                            rs.getObject("created_at", LocalDateTime.class)));
                }
            }
        }
        return list;
    }
}
