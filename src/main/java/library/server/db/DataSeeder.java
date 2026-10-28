package library.server.db;

import library.common.Protocol;
import library.common.Role;
import library.common.Seat;
import library.common.SeatType;
import library.common.Zone;
import library.server.service.PasswordHasher;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thêm dữ liệu mẫu khi CSDL còn trống (chưa có tài khoản nào). */
public final class DataSeeder {

    private static final String DAYS_RULE = "đặt trước tối đa " + Protocol.BOOKING_DAYS_AHEAD + " ngày (tùy điểm uy tín).";
    /** Tên thư viện ở các phiên bản trước - dữ liệu mẫu cũ trong CSDL được đổi sang tên mới khi khởi động. */
    private static final String OLD_LIBRARY_NAME = "Thư viện Fix Bug";
    private static final String OLD_DAYS_RULE = "đặt trước tối đa 7 ngày.";

    public static final String WELCOME_CONTENT = Protocol.LIBRARY_NAME
            + " mở cửa từ 07:00 đến 21:00, chia thành 5 ca học.\n\n"
            + "Quy định:\n"
            + "• Mỗi sinh viên được đặt tối đa 3 ca trong một ngày, " + DAYS_RULE + "\n"
            + "• Check-in trong khoảng 15 phút trước đến 15 phút sau giờ bắt đầu ca.\n"
            + "• Không check-in đúng giờ, hệ thống sẽ tự động hủy và nhường chỗ cho người khác.\n"
            + "• Nếu không đến được, vui lòng hủy lượt đặt sớm.";

    private DataSeeder() {
    }

    /** @return số dòng đã thêm (0 nếu CSDL đã có dữ liệu) */
    static int seedIfEmpty(Connection c) throws SQLException {
        if (UserDao.count(c) > 0) {
            return 0;
        }
        int rows = 0;
        rows += addUser(c, "admin", "admin123", "Quản trị viên " + Protocol.LIBRARY_NAME, null, Role.ADMIN);
        rows += addUser(c, "thinh", "123456", "Nguyễn Văn Thịnh", "2280603107", Role.STUDENT);
        rows += addUser(c, "truong", "123456", "Nguyễn Đan Trường", "2280603486", Role.STUDENT);
        rows += addUser(c, "bao", "123456", "Chu Nguyễn Gia Bảo", "2380600118", Role.STUDENT);

        for (Zone z : sampleZones()) {
            if (ZoneDao.find(c, z.getId()) == null) {
                ZoneDao.insert(c, z);
                rows++;
            }
        }
        for (Seat s : sampleSeats()) {
            if (SeatDao.find(c, s.getId()) == null) {
                SeatDao.insert(c, s);
                rows++;
            }
        }
        AnnouncementDao.insert(c, "Chào mừng đến với " + Protocol.LIBRARY_NAME, WELCOME_CONTENT,
                "Quản trị viên " + Protocol.LIBRARY_NAME);
        return rows + 1;
    }

    /**
     * Đổi tên thư viện cũ ("Thư viện Fix Bug") sang tên hiện tại trong dữ liệu do phiên bản trước tạo:
     * họ tên tài khoản quản trị, tên người giữ chỗ trên các lượt admin tự đặt, thông báo của quản trị viên.
     * Chạy lại nhiều lần vẫn an toàn (lần sau không còn gì để đổi).
     *
     * @return số dòng đã đổi
     */
    static int renameLegacyLibrary(Connection c) throws SQLException {
        if (OLD_LIBRARY_NAME.equals(Protocol.LIBRARY_NAME)) {
            return 0;
        }
        String oldAdmin = "Quản trị viên " + OLD_LIBRARY_NAME;
        String newAdmin = "Quản trị viên " + Protocol.LIBRARY_NAME;
        int n = Sql.update(c, "UPDATE users SET full_name = ? WHERE full_name = ?", newAdmin, oldAdmin);
        n += Sql.update(c, "UPDATE bookings SET full_name = ? WHERE full_name = ?", newAdmin, oldAdmin);
        n += Sql.update(c, "UPDATE announcements SET title = REPLACE(title, ?, ?), "
                        + "content = REPLACE(REPLACE(content, ?, ?), ?, ?), author = ? WHERE author = ?",
                OLD_LIBRARY_NAME, Protocol.LIBRARY_NAME, OLD_LIBRARY_NAME, Protocol.LIBRARY_NAME,
                OLD_DAYS_RULE, DAYS_RULE, newAdmin, oldAdmin);
        return n;
    }

    /**
     * Cập nhật dữ liệu cho CSDL tạo từ phiên bản trước. Mỗi bản cập nhật chỉ chạy MỘT lần (ghi dấu vào bảng
     * app_meta), nên nếu quản trị viên xóa khu vực vừa thêm thì lần khởi động sau không thêm lại.
     *
     * @return mô tả những gì đã thêm (rỗng nếu không có gì)
     */
    static List<String> applyDataUpdates(Connection c) throws SQLException {
        List<String> done = new ArrayList<>();
        if (!metaDone(c, "zone_pair_v1")) {
            String added = addPairZoneIfMissing(c);
            markMeta(c, "zone_pair_v1", added == null ? "đã có bàn đôi" : added);
            if (added != null) {
                done.add(added);
            }
        }
        if (!metaDone(c, "zone_letters_v2")) {
            String relettered = reletterZones(c);
            markMeta(c, "zone_letters_v2", relettered == null ? "bỏ qua - bố cục khu vực khác dữ liệu mẫu" : relettered);
            if (relettered != null) {
                done.add(relettered);
            }
        }
        return done;
    }

    /** {mã cũ, tên khu, mã mới}: khu đôi lên B (ngay sau khu chỗ đơn A), các khu sau lùi một chữ cái. */
    private static final String[][] RELETTER = {
            {"B", "Khu học nhóm", "C"},
            {"C", "Khu máy tính", "D"},
            {"D", "Phòng học riêng", "E"},
            {"E", "Khu học đôi", "B"}};

    /**
     * Đổi mã khu của CSDL theo bố cục mẫu cũ (A đơn, B nhóm, C máy tính, D phòng riêng, E đôi) sang bố cục mới
     * (A đơn, B đôi, C nhóm, D máy tính, E phòng riêng): đổi mã khu, mã chỗ (B01 → C01...) và mã chỗ trên các lượt
     * đặt cũ. Chỉ chạy khi dữ liệu khu vực còn đúng như mẫu; quản trị viên đã sửa khu / chỗ thì giữ nguyên.
     * Vì các mã đổi vòng tròn (B→C→D→E→B) nên đi qua mã tạm "~B", "~B01"... để không trùng khóa chính.
     */
    private static String reletterZones(Connection c) throws SQLException {
        Map<String, Zone> zones = new LinkedHashMap<>();
        for (String[] r : RELETTER) {
            Zone z = ZoneDao.find(c, r[0]);
            if (z == null || !r[1].equals(z.getName())) {
                return null;
            }
            zones.put(r[0], z);
        }
        List<Seat> seats = new ArrayList<>();
        for (Seat s : SeatDao.list(c)) {
            if (zones.containsKey(s.getZoneId())) {
                if (!s.getId().startsWith(s.getZoneId()) || s.getId().length() > 9) {
                    return null;
                }
                seats.add(s);
            }
        }
        for (String[] r : RELETTER) {
            if (Sql.count(c, "SELECT COUNT(*) FROM bookings WHERE zone_id = ? AND (seat_id NOT LIKE ? "
                    + "OR CHAR_LENGTH(seat_id) > 9)", r[0], r[0] + "%") > 0) {
                return null;
            }
        }
        // 1) Chuyển sang mã tạm
        for (String[] r : RELETTER) {
            Zone z = zones.get(r[0]);
            ZoneDao.insert(c, new Zone("~" + r[0], z.getName(), z.getFloor(), z.getDescription()));
        }
        for (Seat s : seats) {
            Sql.update(c, "UPDATE seats SET id = ?, zone_id = ? WHERE id = ?", "~" + s.getId(), "~" + s.getZoneId(),
                    s.getId());
        }
        for (String[] r : RELETTER) {
            Sql.update(c, "UPDATE bookings SET seat_id = CONCAT('~', seat_id), zone_id = ? WHERE zone_id = ?",
                    "~" + r[0], r[0]);
            Sql.update(c, "DELETE FROM zones WHERE id = ?", r[0]);
        }
        // 2) Từ mã tạm sang mã mới
        for (String[] r : RELETTER) {
            Zone z = zones.get(r[0]);
            Zone moved = new Zone(r[2], z.getName(), z.getFloor(), z.getDescription());
            ZoneDao.insert(c, moved);
            Sql.update(c, "UPDATE seats SET id = CONCAT(?, SUBSTRING(id, 3)), zone_id = ? WHERE zone_id = ?",
                    r[2], r[2], "~" + r[0]);
            Sql.update(c, "UPDATE bookings SET seat_id = CONCAT(?, SUBSTRING(seat_id, 3)), zone_id = ?, zone_name = ? "
                    + "WHERE zone_id = ?", r[2], r[2], moved.getDisplayName(), "~" + r[0]);
        }
        for (String[] r : RELETTER) {
            Sql.update(c, "DELETE FROM zones WHERE id = ?", "~" + r[0]);
        }
        return "đổi mã khu cho đúng thứ tự: B = Khu học đôi, C = Khu học nhóm, D = Khu máy tính, "
                + "E = Phòng học riêng (mã chỗ và các lượt đặt đổi theo, VD B01 → C01)";
    }

    /** Thêm "Khu học đôi" nếu CSDL chưa có bàn đôi nào; dùng mã khu E, nếu E đã có thì lấy chữ cái trống kế tiếp. */
    private static String addPairZoneIfMissing(Connection c) throws SQLException {
        if (Sql.count(c, "SELECT COUNT(*) FROM seats WHERE type = ?", SeatType.PAIR.name()) > 0) {
            return null;
        }
        String id = null;
        for (char ch = 'E'; ch <= 'Z' && id == null; ch++) {
            String candidate = String.valueOf(ch);
            if (ZoneDao.find(c, candidate) == null
                    && Sql.count(c, "SELECT COUNT(*) FROM seats WHERE id LIKE ?", candidate + "%") == 0) {
                id = candidate;
            }
        }
        if (id == null) {
            return null;
        }
        Zone zone = pairZone(id);
        ZoneDao.insert(c, zone);
        for (int i = 1; i <= PAIR_SEATS; i++) {
            SeatDao.insert(c, pairSeat(id, i));
        }
        return "đã thêm khu " + id + " - " + zone.getName() + " (" + PAIR_SEATS + " bàn đôi " + id + "01-" + id
                + String.format("%02d", PAIR_SEATS) + ", 2 người/bàn, có ổ cắm điện)";
    }

    private static boolean metaDone(Connection c, String key) throws SQLException {
        return Sql.count(c, "SELECT COUNT(*) FROM app_meta WHERE meta_key = ?", key) > 0;
    }

    private static void markMeta(Connection c, String key, String value) throws SQLException {
        Sql.update(c, "INSERT INTO app_meta (meta_key, meta_value, updated_at) VALUES (?,?,?)", key,
                value.length() > 200 ? value.substring(0, 200) : value, LocalDateTime.now().withNano(0));
    }

    private static int addUser(Connection c, String username, String password, String fullName, String studentId,
                               Role role) throws SQLException {
        String salt = PasswordHasher.newSalt();
        UserDao.insert(c, username, fullName, studentId, role, salt, PasswordHasher.hash(password, salt));
        return 1;
    }

    public static Zone[] sampleZones() {
        return new Zone[]{
                new Zone("A", "Khu đọc yên tĩnh", 1, "Giữ trật tự tuyệt đối, phù hợp tự học và ôn thi."),
                pairZone("B"),
                new Zone("C", "Khu học nhóm", 2, "Bàn lớn cho nhóm 4-6 người, được phép thảo luận nhỏ."),
                new Zone("D", "Khu máy tính", 2, "Máy tính có Internet, tra cứu cơ sở dữ liệu và tài liệu số."),
                new Zone("E", "Phòng học riêng", 3, "Phòng kín có bảng và máy chiếu, dành cho nhóm thuyết trình.")};
    }

    /** Khu bàn đôi: mỗi bàn cho 2 người (học cặp, kèm bài). */
    private static Zone pairZone(String id) {
        return new Zone(id, "Khu học đôi", 1, "Bàn cho 2 người học cặp, kèm bài; được trao đổi nhỏ tiếng.");
    }

    private static final int PAIR_SEATS = 10;

    private static Seat pairSeat(String zoneId, int i) {
        return new Seat(String.format("%s%02d", zoneId, i), zoneId, SeatType.PAIR, 2, true);
    }

    public static Seat[] sampleSeats() {
        Seat[] seats = new Seat[44 + PAIR_SEATS];
        int k = 0;
        for (int i = 1; i <= 20; i++) {
            seats[k++] = new Seat(String.format("A%02d", i), "A", SeatType.SINGLE, 1, i <= 10);
        }
        for (int i = 1; i <= PAIR_SEATS; i++) {
            seats[k++] = pairSeat("B", i);
        }
        for (int i = 1; i <= 8; i++) {
            seats[k++] = new Seat(String.format("C%02d", i), "C", SeatType.GROUP, i <= 4 ? 6 : 4, true);
        }
        for (int i = 1; i <= 12; i++) {
            seats[k++] = new Seat(String.format("D%02d", i), "D", SeatType.COMPUTER, 1, true);
        }
        for (int i = 1; i <= 4; i++) {
            seats[k++] = new Seat(String.format("E%02d", i), "E", SeatType.ROOM, 8, true);
        }
        return seats;
    }
}
