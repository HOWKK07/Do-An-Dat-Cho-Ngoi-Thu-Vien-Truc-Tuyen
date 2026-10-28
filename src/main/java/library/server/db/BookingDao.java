package library.server.db;

import library.common.Booking;
import library.common.BookingStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Truy vấn bảng {@code bookings} (lượt đặt chỗ). Mã hiển thị dạng BK00001 = cột id (AUTO_INCREMENT).
 * Cột {@code live} = 1 khi lượt đặt còn hiệu lực (ACTIVE / CHECKED_IN), NULL khi đã kết thúc.
 * Cột {@code owner_live} = 1 chỉ với lượt sinh viên tự đặt còn hiệu lực (giới hạn 1 chỗ/ca cho mỗi sinh viên).
 */
public final class BookingDao {
    /** Mỗi vị trí trên một chỗ / bàn chỉ có 1 lượt còn hiệu lực trong ca (place 0 = cả bàn, 1, 2... = ngồi ghép). */
    public static final String UQ_SEAT_LIVE = "uq_seat_place_live";
    public static final String UQ_USER_LIVE = "uq_owner_live";

    private static final String COLUMNS = "id, username, full_name, student_id, seat_id, zone_id, zone_name, "
            + "booking_date, slot, status, created_at, checked_in_at, finished_at, note, walk_in, phone, guest_note, place";
    private static final String SELECT = "SELECT " + COLUMNS + " FROM bookings ";

    private BookingDao() {
    }

    public static String formatId(int id) {
        return String.format("BK%05d", id);
    }

    public static int parseId(String id) {
        try {
            return Integer.parseInt(id.trim().replaceFirst("(?i)^BK", ""));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static Booking map(ResultSet rs) throws SQLException {
        Booking b = new Booking();
        b.setId(formatId(rs.getInt("id")));
        b.setUsername(rs.getString("username"));
        b.setFullName(rs.getString("full_name"));
        b.setStudentId(rs.getString("student_id"));
        b.setSeatId(rs.getString("seat_id"));
        b.setZoneId(rs.getString("zone_id"));
        b.setZoneName(rs.getString("zone_name"));
        b.setDate(rs.getObject("booking_date", LocalDate.class));
        b.setSlot(rs.getInt("slot"));
        b.setStatus(BookingStatus.valueOf(rs.getString("status")));
        b.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        b.setCheckedInAt(rs.getObject("checked_in_at", LocalDateTime.class));
        b.setFinishedAt(rs.getObject("finished_at", LocalDateTime.class));
        b.setNote(rs.getString("note"));
        b.setWalkIn(rs.getBoolean("walk_in"));
        b.setPhone(rs.getString("phone"));
        b.setGuestNote(rs.getString("guest_note"));
        b.setPlace(rs.getInt("place"));
        return b;
    }

    private static List<Booking> list(Connection c, String sql, Object... params) throws SQLException {
        List<Booking> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Sql.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ Đọc

    /** Đọc và KHÓA một lượt đặt tới hết transaction (tránh 2 thao tác cùng sửa một lượt). */
    public static Booking findForUpdate(Connection c, String id) throws SQLException {
        List<Booking> l = list(c, SELECT + "WHERE id = ? FOR UPDATE", parseId(id));
        return l.isEmpty() ? null : l.get(0);
    }

    public static List<Booking> liveOnDate(Connection c, LocalDate date) throws SQLException {
        return list(c, SELECT + "WHERE booking_date = ? AND live = 1", date);
    }

    /** Các lượt TỰ ĐẶT còn hiệu lực của một người trong ngày (không tính lượt đặt hộ tại quầy). */
    public static List<Booking> liveOfUserOnDate(Connection c, String username, LocalDate date) throws SQLException {
        return list(c, SELECT + "WHERE username = ? AND booking_date = ? AND live = 1 AND walk_in = FALSE",
                username, date);
    }

    /** Các lượt còn hiệu lực trên một chỗ / bàn trong ca (cả bàn hoặc từng chỗ ngồi ghép). */
    public static List<Booking> liveOfSeat(Connection c, String seatId, LocalDate date, int slot) throws SQLException {
        return list(c, SELECT + "WHERE seat_id = ? AND booking_date = ? AND slot = ? AND live = 1 ORDER BY place",
                seatId, date, slot);
    }

    public static boolean isSeatTaken(Connection c, String seatId, LocalDate date, int slot) throws SQLException {
        return Sql.count(c, "SELECT COUNT(*) FROM bookings WHERE seat_id = ? AND booking_date = ? AND slot = ? AND live = 1",
                seatId, date, slot) > 0;
    }

    public static List<Booking> ofUser(Connection c, String username) throws SQLException {
        return list(c, SELECT + "WHERE username = ? AND walk_in = FALSE ORDER BY booking_date DESC, slot DESC, id DESC",
                username);
    }

    public static List<Booking> search(Connection c, LocalDate date, BookingStatus status) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT + "WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (date != null) {
            sql.append(" AND booking_date = ?");
            params.add(date);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        sql.append(" ORDER BY booking_date DESC, slot, seat_id LIMIT 2000");
        return list(c, sql.toString(), params.toArray());
    }

    /** Các lượt còn hiệu lực từ hôm nay trở về trước (ứng viên để tự hủy / tự hoàn thành). */
    public static List<Booking> liveUpTo(Connection c, LocalDate today) throws SQLException {
        return list(c, SELECT + "WHERE live = 1 AND booking_date <= ?", today);
    }

    /** Các lượt còn hiệu lực của những chỗ ngồi cho trước, có khóa dòng. */
    public static List<Booking> liveOfSeatsForUpdate(Connection c, Collection<String> seatIds) throws SQLException {
        if (seatIds.isEmpty()) {
            return new ArrayList<>();
        }
        return list(c, SELECT + "WHERE live = 1 AND seat_id IN (" + Sql.placeholders(seatIds.size()) + ") FOR UPDATE",
                Sql.array(seatIds));
    }

    // ------------------------------------------------------------------ Ghi

    /**
     * Thêm lượt đặt mới (live = 1). Trạng thái lấy từ {@code b} (ACTIVE, hoặc CHECKED_IN khi quản trị viên
     * check-in ngay). Ném lỗi trùng khóa nếu chỗ đã có lượt trong ca, hoặc sinh viên đã giữ chỗ khác trong ca.
     *
     * @param limitOnePerSlot true với lượt sinh viên tự đặt (owner_live = 1), false với lượt quản trị viên đặt
     */
    public static Booking insert(Connection c, Booking b, boolean limitOnePerSlot) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bookings (username, full_name, student_id, "
                        + "seat_id, zone_id, zone_name, booking_date, slot, status, live, owner_live, created_at, "
                        + "checked_in_at, walk_in, phone, guest_note, place) VALUES (?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            Sql.bind(ps, b.getUsername(), b.getFullName(), b.getStudentId(), b.getSeatId(), b.getZoneId(),
                    b.getZoneName(), b.getDate(), b.getSlot(), b.getStatus().name(),
                    limitOnePerSlot ? 1 : null, b.getCreatedAt(), b.getCheckedInAt(), b.isWalkIn(),
                    b.getPhone(), b.getGuestNote(), b.getPlace());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                b.setId(formatId(keys.getInt(1)));
            }
        }
        return b;
    }

    /**
     * Đổi trạng thái kết thúc (CANCELLED / EXPIRED / COMPLETED): live = NULL để giải phóng chỗ.
     * Chỉ cập nhật nếu trạng thái hiện tại vẫn là {@code expected} → trả về false nếu đã bị luồng khác đổi trước.
     */
    public static boolean finish(Connection c, String id, BookingStatus expected, BookingStatus newStatus,
                                 String note, LocalDateTime at) throws SQLException {
        return Sql.update(c, "UPDATE bookings SET status = ?, live = NULL, owner_live = NULL, note = ?, finished_at = ? "
                + "WHERE id = ? AND status = ?", newStatus.name(), note, at, parseId(id), expected.name()) == 1;
    }

    public static boolean checkIn(Connection c, String id, LocalDateTime at) throws SQLException {
        return Sql.update(c, "UPDATE bookings SET status = ?, checked_in_at = ? WHERE id = ? AND status = ?",
                BookingStatus.CHECKED_IN.name(), at, parseId(id), BookingStatus.ACTIVE.name()) == 1;
    }

    // ------------------------------------------------------------------ Thống kê

    public static Map<BookingStatus, Integer> countByStatusOn(Connection c, LocalDate date) throws SQLException {
        Map<BookingStatus, Integer> m = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT status, COUNT(*) FROM bookings WHERE booking_date = ? GROUP BY status")) {
            ps.setObject(1, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(BookingStatus.valueOf(rs.getString(1)), rs.getInt(2));
                }
            }
        }
        return m;
    }

    /** Ngày + ca của các lượt ACTIVE từ hôm nay trở đi (để đếm lượt sắp diễn ra). */
    public static List<int[]> activeSlotsFrom(Connection c, LocalDate today) throws SQLException {
        List<int[]> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT booking_date, slot FROM bookings WHERE status = 'ACTIVE' AND booking_date >= ?")) {
            ps.setObject(1, today);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate d = rs.getObject(1, LocalDate.class);
                    list.add(new int[]{(int) d.toEpochDay(), rs.getInt(2)});
                }
            }
        }
        return list;
    }
}
