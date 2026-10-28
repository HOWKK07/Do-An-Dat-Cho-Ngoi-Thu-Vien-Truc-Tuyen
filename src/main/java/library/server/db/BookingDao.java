package library.server.db;

import library.common.Booking;
import library.common.BookingStatus;
import library.common.Companion;
import library.common.Seat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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

    // ------------------------------------------------------------------ Người đi cùng (đặt cả bàn nhiều chỗ)

    private static final String NOT_DECLINED = "status <> 'DECLINED'";

    /** Thêm người đi cùng (trạng thái "chờ xác nhận", tính giờ mời từ {@code now}); đã có dòng (từng từ chối) thì mời lại. */
    public static void addCompanion(Connection c, String bookingId, String username, LocalDateTime now)
            throws SQLException {
        int n = Sql.update(c, "UPDATE booking_companions SET status = 'PENDING', responded_at = NULL, invited_at = ?, "
                + "reminded = FALSE WHERE booking_id = ? AND username = ?", now, parseId(bookingId), username);
        if (n == 0) {
            Sql.update(c, "INSERT INTO booking_companions (booking_id, username, status, invited_at, reminded) "
                    + "VALUES (?,?,'PENDING',?,FALSE)", parseId(bookingId), username, now);
        }
    }

    /** Đánh dấu đã nhắc người đặt (15 phút sau khi đặt chưa đủ người xác nhận) - mỗi lượt đặt chỉ nhắc một lần. */
    public static void markReminded(Connection c, String bookingId) throws SQLException {
        Sql.update(c, "UPDATE booking_companions SET reminded = TRUE WHERE booking_id = ?", parseId(bookingId));
    }

    /** Các lượt đặt cả bàn đang chờ check-in có khai báo người đi cùng (để hẹn giờ nhắc / tự hủy khi khởi động). */
    public static List<Booking> liveWithCompanions(Connection c) throws SQLException {
        return list(c, SELECT + "WHERE live = 1 AND status = 'ACTIVE' AND id IN (SELECT booking_id FROM booking_companions)");
    }

    public static void removeCompanion(Connection c, String bookingId, String username) throws SQLException {
        Sql.update(c, "DELETE FROM booking_companions WHERE booking_id = ? AND username = ?", parseId(bookingId), username);
    }

    public static void setCompanionStatus(Connection c, String bookingId, String username, Companion.Status status,
                                          LocalDateTime at) throws SQLException {
        Sql.update(c, "UPDATE booking_companions SET status = ?, responded_at = ? WHERE booking_id = ? AND username = ?",
                status.name(), at, parseId(bookingId), username);
    }

    private static final String COMPANION_COLS = "bc.username, u.full_name, u.student_id, bc.status, bc.invited_at, "
            + "bc.responded_at, bc.reminded";

    /** Đọc một người đi cùng từ các cột {@link #COMPANION_COLS} bắt đầu ở cột {@code i}. */
    private static Companion mapCompanion(ResultSet rs, int i) throws SQLException {
        return new Companion(rs.getString(i), rs.getString(i + 1), rs.getString(i + 2),
                Companion.Status.parse(rs.getString(i + 3)), rs.getObject(i + 4, LocalDateTime.class),
                rs.getObject(i + 5, LocalDateTime.class), rs.getBoolean(i + 6));
    }

    /** Danh sách người đi cùng của một lượt đặt (kể cả người đã từ chối), xếp theo họ tên. */
    public static List<Companion> companionsOf(Connection c, String bookingId) throws SQLException {
        List<Companion> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COMPANION_COLS
                + " FROM booking_companions bc JOIN users u ON u.username = bc.username WHERE bc.booking_id = ? "
                + "ORDER BY u.full_name")) {
            ps.setInt(1, parseId(bookingId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapCompanion(rs, 1));
                }
            }
        }
        return out;
    }

    /** Tên đăng nhập những người đi cùng chưa từ chối (để báo tin khi lượt đặt bị hủy). */
    public static List<String> companionUsernames(Connection c, String bookingId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT username FROM booking_companions WHERE booking_id = ? AND "
                + NOT_DECLINED)) {
            ps.setInt(1, parseId(bookingId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** Lượt đặt còn hiệu lực trong ca mà {@code username} được khai báo đi cùng (chưa từ chối), hoặc null. */
    public static Booking liveAsCompanion(Connection c, String username, LocalDate date, int slot) throws SQLException {
        return liveAsCompanion(c, username, date, slot, null);
    }

    /** Như trên nhưng bỏ qua lượt đặt {@code exceptBookingId} (khi người đặt sửa danh sách của chính lượt đó). */
    public static Booking liveAsCompanion(Connection c, String username, LocalDate date, int slot, String exceptBookingId)
            throws SQLException {
        List<Booking> l = list(c, SELECT + "WHERE live = 1 AND booking_date = ? AND slot = ? AND id <> ? AND id IN "
                        + "(SELECT booking_id FROM booking_companions WHERE username = ? AND " + NOT_DECLINED + ")",
                date, slot, exceptBookingId == null ? -1 : parseId(exceptBookingId), username);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Các lượt đặt mà {@code username} được người khác khai báo đi cùng (mới nhất trước), kèm trạng thái của họ. */
    public static List<Booking> invitesOf(Connection c, String username) throws SQLException {
        List<Booking> list = list(c, SELECT + "WHERE id IN (SELECT booking_id FROM booking_companions WHERE username = ?) "
                + "ORDER BY booking_date DESC, slot DESC, id DESC LIMIT 200", username);
        Map<Integer, Companion.Status> mine = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT booking_id, status FROM booking_companions WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mine.put(rs.getInt(1), Companion.Status.parse(rs.getString(2)));
                }
            }
        }
        for (Booking b : list) {
            b.setMyCompanionStatus(mine.get(parseId(b.getId())));
        }
        return list;
    }

    /**
     * Gắn danh sách người đi cùng (kèm trạng thái xác nhận) và số chỗ của bàn vào các lượt đặt
     * (một truy vấn cho cả danh sách).
     */
    public static void attachCompanions(Connection c, List<Booking> bookings) throws SQLException {
        Map<Integer, Booking> byId = new LinkedHashMap<>();
        for (Booking b : bookings) {
            if (!b.isSharedPlace()) {
                byId.put(parseId(b.getId()), b);
            }
        }
        if (byId.isEmpty()) {
            return;
        }
        Map<String, Integer> capacity = new HashMap<>();
        for (Seat s : SeatDao.list(c)) {
            capacity.put(s.getId(), s.getCapacity());
        }
        for (Booking b : byId.values()) {
            b.setSeatCapacity(capacity.getOrDefault(b.getSeatId(), 0));
        }
        List<Object> ids = new ArrayList<>(byId.keySet());
        for (int from = 0; from < ids.size(); from += 500) { // chia nhỏ để câu IN (...) không quá dài
            List<Object> part = ids.subList(from, Math.min(ids.size(), from + 500));
            try (PreparedStatement ps = c.prepareStatement("SELECT bc.booking_id, " + COMPANION_COLS
                    + " FROM booking_companions bc JOIN users u ON u.username = bc.username WHERE bc.booking_id IN ("
                    + Sql.placeholders(part.size()) + ") ORDER BY u.full_name")) {
                Sql.bind(ps, part.toArray());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byId.get(rs.getInt(1)).getCompanions().add(mapCompanion(rs, 2));
                    }
                }
            }
        }
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
