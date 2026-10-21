package library.server.db;

import library.common.Protocol;
import library.common.Role;
import library.common.Seat;
import library.common.SeatType;
import library.common.Zone;
import library.server.service.PasswordHasher;

import java.sql.Connection;
import java.sql.SQLException;

/** Thêm dữ liệu mẫu khi CSDL còn trống (chưa có tài khoản nào). */
public final class DataSeeder {

    public static final String WELCOME_CONTENT = Protocol.LIBRARY_NAME
            + " mở cửa từ 07:00 đến 21:00, chia thành 5 ca học.\n\n"
            + "Quy định:\n"
            + "• Mỗi sinh viên được đặt tối đa 3 ca trong một ngày, đặt trước tối đa 7 ngày.\n"
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

    private static int addUser(Connection c, String username, String password, String fullName, String studentId,
                               Role role) throws SQLException {
        String salt = PasswordHasher.newSalt();
        UserDao.insert(c, username, fullName, studentId, role, salt, PasswordHasher.hash(password, salt));
        return 1;
    }

    public static Zone[] sampleZones() {
        return new Zone[]{
                new Zone("A", "Khu đọc yên tĩnh", 1, "Giữ trật tự tuyệt đối, phù hợp tự học và ôn thi."),
                new Zone("B", "Khu học nhóm", 2, "Bàn lớn cho nhóm 4-6 người, được phép thảo luận nhỏ."),
                new Zone("C", "Khu máy tính", 2, "Máy tính có Internet, tra cứu cơ sở dữ liệu và tài liệu số."),
                new Zone("D", "Phòng học riêng", 3, "Phòng kín có bảng và máy chiếu, dành cho nhóm thuyết trình.")};
    }

    public static Seat[] sampleSeats() {
        Seat[] seats = new Seat[44];
        int k = 0;
        for (int i = 1; i <= 20; i++) {
            seats[k++] = new Seat(String.format("A%02d", i), "A", SeatType.SINGLE, 1, i <= 10);
        }
        for (int i = 1; i <= 8; i++) {
            seats[k++] = new Seat(String.format("B%02d", i), "B", SeatType.GROUP, i <= 4 ? 6 : 4, true);
        }
        for (int i = 1; i <= 12; i++) {
            seats[k++] = new Seat(String.format("C%02d", i), "C", SeatType.COMPUTER, 1, true);
        }
        for (int i = 1; i <= 4; i++) {
            seats[k++] = new Seat(String.format("D%02d", i), "D", SeatType.ROOM, 8, true);
        }
        return seats;
    }
}
