package library.server.db;

import library.common.Seat;
import library.common.SeatType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Truy vấn bảng {@code seats} (chỗ ngồi). */
public final class SeatDao {
    private static final String COLUMNS = "id, zone_id, type, capacity, has_outlet, active, note";

    private SeatDao() {
    }

    private static Seat map(ResultSet rs) throws SQLException {
        Seat s = new Seat(rs.getString("id"), rs.getString("zone_id"), SeatType.valueOf(rs.getString("type")),
                rs.getInt("capacity"), rs.getBoolean("has_outlet"));
        s.setActive(rs.getBoolean("active"));
        s.setNote(rs.getString("note"));
        return s;
    }

    public static List<Seat> list(Connection c) throws SQLException {
        List<Seat> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM seats ORDER BY zone_id, id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    /**
     * Đọc và KHÓA dòng chỗ ngồi tới hết transaction: các lượt đặt vào cùng một chỗ / bàn được xử lý lần lượt
     * (cần cho ngồi ghép: tránh vừa đặt cả bàn vừa đặt lẻ cùng lúc).
     */
    public static Seat findForUpdate(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM seats WHERE id = ? FOR UPDATE")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public static Seat find(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM seats WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public static void insert(Connection c, Seat s) throws SQLException {
        Sql.update(c, "INSERT INTO seats (id, zone_id, type, capacity, has_outlet, active, note) VALUES (?,?,?,?,?,?,?)",
                s.getId(), s.getZoneId(), s.getType().name(), s.getCapacity(), s.isHasOutlet(), s.isActive(),
                s.getNote());
    }

    /** Cập nhật thông tin chỗ (trạng thái bảo trì được đổi bằng {@link #setActive}). */
    public static int update(Connection c, Seat s) throws SQLException {
        return Sql.update(c, "UPDATE seats SET zone_id = ?, type = ?, capacity = ?, has_outlet = ? WHERE id = ?",
                s.getZoneId(), s.getType().name(), s.getCapacity(), s.isHasOutlet(), s.getId());
    }

    public static int setActive(Connection c, String id, boolean active, String note) throws SQLException {
        return Sql.update(c, "UPDATE seats SET active = ?, note = ? WHERE id = ?", active, note, id);
    }

    public static int delete(Connection c, String id) throws SQLException {
        return Sql.update(c, "DELETE FROM seats WHERE id = ?", id);
    }

    public static List<String> idsInZone(Connection c, String zoneId) throws SQLException {
        return ids(c, "SELECT id FROM seats WHERE zone_id = ?", zoneId);
    }

    /** Mã chỗ bắt đầu bằng tiền tố (tiền tố đã được kiểm tra chỉ gồm A-Z, 0-9, '-'). */
    public static List<String> idsWithPrefix(Connection c, String prefix) throws SQLException {
        return ids(c, "SELECT id FROM seats WHERE id LIKE ?", prefix + "%");
    }

    private static List<String> ids(Connection c, String sql, String param) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
            }
        }
        return list;
    }

    public static int count(Connection c) throws SQLException {
        return Sql.count(c, "SELECT COUNT(*) FROM seats");
    }

    public static int countInactive(Connection c) throws SQLException {
        return Sql.count(c, "SELECT COUNT(*) FROM seats WHERE active = FALSE");
    }
}
