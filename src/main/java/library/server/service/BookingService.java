package library.server.service;

import library.common.Booking;
import library.common.BookingStatus;
import library.common.EventType;
import library.common.Fmt;
import library.common.Protocol;
import library.common.Reputation;
import library.common.Role;
import library.common.Seat;
import library.common.SeatMap;
import library.common.SeatState;
import library.common.SeatView;
import library.common.TimeSlot;
import library.common.UserInfo;
import library.common.Zone;
import library.server.Log;
import library.server.db.BookingDao;
import library.server.db.Database;
import library.server.db.SeatDao;
import library.server.db.Sql;
import library.server.db.UserDao;
import library.server.db.UserDao.Standing;
import library.server.db.ZoneDao;
import library.server.thread.EventBroadcaster;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nghiệp vụ đặt chỗ: xem sơ đồ, đặt, hủy, check-in, trả chỗ, tự hủy khi không đến.
 *
 * <p>Chống đặt trùng chỗ 2 lớp:
 * (1) kiểm tra trong transaction trước khi INSERT để báo lỗi thân thiện;
 * (2) UNIQUE KEY uq_seat_live / uq_user_live trong MySQL là chốt chặn cuối cùng -
 * nếu nhiều luồng cùng vượt qua bước (1), chỉ một lệnh INSERT thành công, các lệnh còn lại bị MySQL từ chối.</p>
 */
public class BookingService {
    private final Database db;
    private final EventBroadcaster events;

    public BookingService(Database db, EventBroadcaster events) {
        this.db = db;
        this.events = events;
    }

    // ------------------------------------------------------------------ Xem sơ đồ chỗ

    public SeatMap getSeatMap(UserInfo viewer, LocalDate date, int slot) {
        TimeSlot ts = requireSlot(date, slot);
        boolean admin = viewer.isAdmin();
        String windowError = admin ? adminWindowError(date, ts, LocalDateTime.now())
                : bookingWindowError(date, ts, LocalDateTime.now());

        return db.query(c -> {
            String closedReason = windowError;
            Standing standing = admin ? null : UserDao.standing(c, viewer.getUsername());
            Reputation.Level level = standing == null ? Reputation.Level.EXCELLENT : standing.level(LocalDate.now());
            if (closedReason == null && standing != null) {
                closedReason = reputationError(standing, level, date);
            }
            Map<String, Booking> taken = new HashMap<>();
            int mine = 0;
            for (Booking b : BookingDao.liveOnDate(c, date)) {
                if (b.getUsername().equals(viewer.getUsername()) && !b.isWalkIn()) {
                    mine++;
                }
                if (b.getSlot() == slot) {
                    taken.put(b.getSeatId(), b);
                }
            }
            SeatMap map = new SeatMap(date, slot);
            map.getZones().addAll(ZoneDao.list(c));
            for (Seat s : SeatDao.list(c)) {
                SeatView v = new SeatView(s);
                Booking b = taken.get(s.getId());
                if (b != null && b.getUsername().equals(viewer.getUsername()) && !b.isWalkIn()) {
                    v.setState(SeatState.MINE);
                    v.setBookingId(b.getId());
                    v.setBookingStatus(b.getStatus());
                } else if (b != null) {
                    v.setState(SeatState.BOOKED);
                    v.setBookingStatus(b.getStatus());
                    if (admin) { // chỉ quản trị viên mới thấy ai đang giữ chỗ
                        v.setBookingId(b.getId());
                        v.setBookedBy(holderInfo(b));
                    }
                } else if (!s.isActive()) {
                    v.setState(SeatState.MAINTENANCE);
                } else {
                    v.setState(SeatState.AVAILABLE);
                }
                map.getSeats().add(v);
            }
            map.setOpen(closedReason == null);
            map.setClosedReason(closedReason);
            map.setMyBookingsThatDay(mine);
            if (standing != null) {
                map.setReputation(standing.score, level);
            }
            return map;
        });
    }

    // ------------------------------------------------------------------ Đặt chỗ

    public Booking book(UserInfo user, String rawSeatId, LocalDate date, int slot) {
        TimeSlot ts = requireSlot(date, slot);
        String seatId = rawSeatId == null ? "" : rawSeatId.trim().toUpperCase();
        String err = bookingWindowError(date, ts, LocalDateTime.now());
        if (err != null) {
            throw new ServiceException(err);
        }

        Booking created = db.tx(c -> {
            Seat seat = SeatDao.find(c, seatId);
            if (seat == null) {
                throw new ServiceException("Chỗ ngồi " + seatId + " không tồn tại.");
            }
            if (!seat.isActive()) {
                throw new ServiceException("Chỗ " + seatId + " đang tạm ngừng phục vụ (bảo trì).");
            }
            // Khóa dòng người dùng: các yêu cầu của cùng một người được xử lý tuần tự; đọc luôn điểm uy tín
            Standing standing = UserDao.standingForUpdate(c, user.getUsername());
            Reputation.Level level = standing.level(LocalDate.now());
            String repError = reputationError(standing, level, date);
            if (repError != null) {
                throw new ServiceException(repError);
            }

            int countThatDay = 0;
            for (Booking b : BookingDao.liveOfUserOnDate(c, user.getUsername(), date)) {
                if (b.getSlot() == slot) {
                    throw new ServiceException("Bạn đã đặt chỗ " + b.getSeatId()
                            + " trong ca này rồi. Mỗi ca chỉ được giữ một chỗ.");
                }
                countThatDay++;
            }
            if (countThatDay >= level.getMaxPerDay()) {
                throw new ServiceException("Bạn đã đặt đủ " + level.getMaxPerDay() + " ca trong ngày " + Fmt.date(date)
                        + (level.getMaxPerDay() >= Protocol.MAX_BOOKINGS_PER_DAY ? "." : " (giới hạn của mức uy tín " + level.getLabel()
                        + ", " + standing.score + " điểm)."));
            }
            if (BookingDao.isSeatTaken(c, seatId, date, slot)) {
                throw seatTaken(seatId);
            }

            Zone zone = ZoneDao.find(c, seat.getZoneId());
            Booking b = new Booking();
            b.setUsername(user.getUsername());
            b.setFullName(user.getFullName());
            b.setStudentId(user.getStudentId());
            b.setSeatId(seatId);
            b.setZoneId(seat.getZoneId());
            b.setZoneName(zone != null ? zone.getDisplayName() : seat.getZoneId());
            b.setDate(date);
            b.setSlot(slot);
            b.setCreatedAt(LocalDateTime.now().withNano(0));
            b.setStatus(BookingStatus.ACTIVE);
            try {
                return BookingDao.insert(c, b, true);
            } catch (SQLException e) {
                // Chốt chặn cuối: luồng khác vừa INSERT cùng chỗ / cùng người trong ca này
                if (Sql.isDuplicate(e, BookingDao.UQ_SEAT_LIVE)) {
                    throw seatTaken(seatId);
                }
                if (Sql.isDuplicate(e, BookingDao.UQ_USER_LIVE)) {
                    throw new ServiceException("Bạn đã đặt một chỗ khác trong ca này rồi.");
                }
                throw e;
            }
        });

        Log.info("%s đặt %s -> %s", user.getUsername(), created.describe(), created.getId());
        events.seatsChanged(date, slot);
        return created;
    }

    // ------------------------------------------------------------------ Quản trị viên đặt chỗ

    /** Thông tin sinh viên đặt trực tiếp tại quầy (không có tài khoản). */
    public static final class WalkInInfo {
        final String name;
        final String studentId;
        final String phone;
        final String note;

        public WalkInInfo(String name, String studentId, String phone, String note) {
            this.name = clean(name);
            this.studentId = clean(studentId);
            this.phone = clean(phone);
            this.note = clean(note);
        }

        private static String clean(String s) {
            return s == null || s.trim().isEmpty() ? null : s.trim().replaceAll("\\s+", " ");
        }
    }

    /**
     * Quản trị viên đặt chỗ "thoải mái": không giới hạn 3 ca/ngày, không giới hạn 1 chỗ/ca, đặt được cả ca đang
     * diễn ra; vẫn chỉ đặt trước tối đa {@link Protocol#ADMIN_BOOKING_DAYS_AHEAD} ngày theo quy định thư viện.
     *
     * @param guest     null = đặt cho chính quản trị viên; khác null = đặt hộ sinh viên không có tài khoản
     * @param checkInNow sinh viên đã có mặt → chuyển thẳng sang "Đang sử dụng"
     */
    public Booking bookByAdmin(UserInfo admin, String rawSeatId, LocalDate date, int slot, WalkInInfo guest,
                               boolean checkInNow) {
        TimeSlot ts = requireSlot(date, slot);
        String seatId = rawSeatId == null ? "" : rawSeatId.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String err = adminWindowError(date, ts, now);
        if (err != null) {
            throw new ServiceException(err);
        }
        if (guest != null) {
            validateGuest(guest);
        }
        boolean today = date.equals(now.toLocalDate());
        // Ca đã quá hạn check-in mà chưa check-in thì hệ thống sẽ tự hủy → check-in luôn cho sinh viên đang có mặt
        boolean pastDeadline = now.isAfter(ts.startAt(date).plusMinutes(Protocol.CHECKIN_LATE_MINUTES));
        if (checkInNow && !today) {
            throw new ServiceException("Chỉ check-in ngay được với lượt đặt trong hôm nay.");
        }
        if (checkInNow && now.isBefore(ts.checkInOpensAt(date))) {
            throw new ServiceException("Chưa đến ca nên chưa check-in được. " + ts.getLabel()
                    + " mở check-in từ " + Fmt.time(ts.checkInOpensAt(date)) + ".");
        }
        boolean checkIn = checkInNow || pastDeadline;

        Booking created = db.tx(c -> {
            Seat seat = SeatDao.find(c, seatId);
            if (seat == null) {
                throw new ServiceException("Chỗ ngồi " + seatId + " không tồn tại.");
            }
            if (!seat.isActive()) {
                throw new ServiceException("Chỗ " + seatId + " đang tạm ngừng phục vụ (bảo trì).");
            }
            if (BookingDao.isSeatTaken(c, seatId, date, slot)) {
                throw seatTaken(seatId);
            }
            Zone zone = ZoneDao.find(c, seat.getZoneId());
            Booking b = new Booking();
            b.setUsername(admin.getUsername()); // tài khoản tạo lượt đặt (FK tới bảng users)
            if (guest != null) {
                b.setWalkIn(true);
                b.setFullName(guest.name);
                b.setStudentId(guest.studentId);
                b.setPhone(guest.phone);
                b.setGuestNote(guest.note);
            } else {
                b.setFullName(admin.getFullName());
                b.setStudentId(admin.getStudentId());
            }
            b.setSeatId(seatId);
            b.setZoneId(seat.getZoneId());
            b.setZoneName(zone != null ? zone.getDisplayName() : seat.getZoneId());
            b.setDate(date);
            b.setSlot(slot);
            b.setCreatedAt(now);
            b.setStatus(checkIn ? BookingStatus.CHECKED_IN : BookingStatus.ACTIVE);
            b.setCheckedInAt(checkIn ? now : null);
            try {
                return BookingDao.insert(c, b, false);
            } catch (SQLException e) {
                if (Sql.isDuplicate(e, BookingDao.UQ_SEAT_LIVE)) {
                    throw seatTaken(seatId);
                }
                throw e;
            }
        });

        Log.info("Quản trị viên %s đặt %s cho %s -> %s%s", admin.getUsername(), created.describe(),
                guest != null ? "sinh viên tại quầy " + created.getFullName() : "chính mình", created.getId(),
                created.getStatus() == BookingStatus.CHECKED_IN ? " (đã check-in)" : "");
        events.seatsChanged(date, slot);
        return created;
    }

    private static void validateGuest(WalkInInfo g) {
        if (g.name == null || g.name.length() < 2 || g.name.length() > 60) {
            throw new ServiceException("Vui lòng nhập họ tên sinh viên (2-60 ký tự).");
        }
        if (g.studentId != null && !g.studentId.matches("\\d{6,12}")) {
            throw new ServiceException("Mã số sinh viên phải gồm 6-12 chữ số (hoặc để trống).");
        }
        if (g.phone != null && !g.phone.matches("\\+?[0-9 .]{8,15}")) {
            throw new ServiceException("Số điện thoại không hợp lệ (hoặc để trống).");
        }
        if (g.note != null && g.note.length() > 200) {
            throw new ServiceException("Ghi chú tối đa 200 ký tự.");
        }
    }

    /** Quản trị viên check-in hộ (sinh viên đến quầy, hoặc lượt đặt tại quầy). */
    public Booking checkInByAdmin(UserInfo admin, String bookingId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Booking b = db.tx(c -> {
            Booking x = requireBooking(c, bookingId);
            if (x.getStatus() != BookingStatus.ACTIVE) {
                throw new ServiceException("Chỉ check-in được lượt đang ở trạng thái \"Đã đặt\" (hiện tại: "
                        + x.getStatus().getLabel() + ").");
            }
            if (!x.getDate().equals(now.toLocalDate())) {
                throw new ServiceException("Lượt đặt này không phải của hôm nay (" + Fmt.dayLabel(x.getDate()) + ").");
            }
            if (!now.isBefore(x.getTimeSlot().endAt(x.getDate()))) {
                throw new ServiceException("Ca học của lượt đặt này đã kết thúc.");
            }
            LocalDateTime opens = x.getTimeSlot().checkInOpensAt(x.getDate());
            if (now.isBefore(opens)) {
                throw new ServiceException("Chưa đến ca nên chưa check-in được. Check-in mở từ " + Fmt.time(opens)
                        + " (15 phút trước giờ bắt đầu " + x.getTimeSlot().getLabel() + ").");
            }
            BookingDao.checkIn(c, x.getId(), now);
            x.setStatus(BookingStatus.CHECKED_IN);
            x.setCheckedInAt(now);
            if (!x.isWalkIn()) {
                rewardCheckIn(c, x, now.toLocalDate());
            }
            return x;
        });
        Log.info("Quản trị viên %s check-in hộ lượt %s (%s)", admin.getUsername(), b.getId(), b.getFullName());
        events.seatsChanged(b.getDate(), b.getSlot());
        if (!b.isWalkIn() && !b.getUsername().equals(admin.getUsername())) {
            events.sendToUser(b.getUsername(), EventType.BOOKING_UPDATED,
                    "Quản trị viên đã check-in giúp bạn lượt " + b.getId() + " (" + b.describe() + ").");
        }
        return b;
    }

    /** Quản trị viên xác nhận sinh viên đã trả chỗ. */
    public Booking checkOutByAdmin(UserInfo admin, String bookingId) {
        Booking b = db.tx(c -> {
            Booking x = requireBooking(c, bookingId);
            if (x.getStatus() != BookingStatus.CHECKED_IN) {
                throw new ServiceException("Chỉ trả chỗ được với lượt đang sử dụng (đã check-in).");
            }
            return finish(c, x, BookingStatus.COMPLETED,
                    "Quản trị viên xác nhận trả chỗ lúc " + Fmt.time(LocalDateTime.now()));
        });
        Log.info("Quản trị viên %s xác nhận trả chỗ %s (%s)", admin.getUsername(), b.getSeatId(), b.getId());
        events.seatsChanged(b.getDate(), b.getSlot());
        return b;
    }

    /** Mô tả người giữ chỗ cho quản trị viên xem trên sơ đồ. */
    private static String holderInfo(Booking b) {
        if (!b.isWalkIn()) {
            return b.getFullName() + " (" + b.getUsername() + ")";
        }
        StringBuilder sb = new StringBuilder(b.getFullName()).append(" - đặt tại quầy");
        if (b.getStudentId() != null) {
            sb.append(", MSSV ").append(b.getStudentId());
        }
        if (b.getPhone() != null) {
            sb.append(", SĐT ").append(b.getPhone());
        }
        if (b.getGuestNote() != null) {
            sb.append(". Ghi chú: ").append(b.getGuestNote());
        }
        return sb.toString();
    }

    private static ServiceException seatTaken(String seatId) {
        return new ServiceException("Rất tiếc, chỗ " + seatId + " vừa được người khác đặt. Vui lòng chọn chỗ khác.");
    }

    // ------------------------------------------------------------------ Hủy / check-in / trả chỗ

    public Booking cancelByUser(UserInfo user, String bookingId) {
        Booking b = db.tx(c -> {
            Booking x = requireBooking(c, bookingId);
            if (!x.getUsername().equals(user.getUsername())) {
                throw new ServiceException("Bạn không thể hủy lượt đặt của người khác.");
            }
            if (x.getStatus() == BookingStatus.CHECKED_IN) {
                throw new ServiceException("Bạn đã check-in, hãy dùng chức năng \"Trả chỗ\" khi rời đi.");
            }
            if (x.getStatus() != BookingStatus.ACTIVE) {
                throw new ServiceException("Lượt đặt này không thể hủy (trạng thái: " + x.getStatus().getLabel() + ").");
            }
            LocalDateTime now = LocalDateTime.now();
            boolean late = !now.isBefore(x.getTimeSlot().startAt(x.getDate()).minusMinutes(Reputation.LATE_CANCEL_MINUTES));
            Booking done = finish(c, x, BookingStatus.CANCELLED, late
                    ? "Người dùng hủy sát giờ (dưới " + Reputation.LATE_CANCEL_MINUTES + " phút trước ca)"
                    : "Người dùng tự hủy");
            if (late) {
                ReputationService.Change ch = ReputationService.change(c, x.getUsername(), -Reputation.LATE_CANCEL_PENALTY,
                        "Hủy sát giờ lượt " + x.getId() + " (" + x.describe() + ")", x.getId(), now.toLocalDate());
                if (ch != null) {
                    done.setReputationNote("Hủy sát giờ: " + ch.describe());
                }
            }
            return done;
        });
        Log.info("%s hủy lượt đặt %s (%s)", user.getUsername(), b.getId(), b.describe());
        events.seatsChanged(b.getDate(), b.getSlot());
        return b;
    }

    public Booking cancelByAdmin(UserInfo admin, String bookingId, String reason) {
        String why = reason == null || reason.trim().isEmpty() ? "" : ": " + reason.trim();
        Booking b = db.tx(c -> {
            Booking x = requireBooking(c, bookingId);
            if (!x.getStatus().isLive()) {
                throw new ServiceException("Lượt đặt này đã kết thúc (" + x.getStatus().getLabel() + ").");
            }
            return finish(c, x, BookingStatus.CANCELLED, "Quản trị viên hủy" + why);
        });
        Log.info("Quản trị viên %s hủy lượt đặt %s của %s", admin.getUsername(), b.getId(), b.getUsername());
        events.seatsChanged(b.getDate(), b.getSlot());
        if (!b.getUsername().equals(admin.getUsername())) {
            events.sendToUser(b.getUsername(), EventType.BOOKING_UPDATED,
                    "Lượt đặt " + b.getId() + " (" + b.describe() + ") đã bị quản trị viên hủy" + why);
        }
        return b;
    }

    public Booking checkIn(UserInfo user, String bookingId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Booking b = db.tx(c -> {
            Booking x = requireBooking(c, bookingId);
            if (!x.getUsername().equals(user.getUsername())) {
                throw new ServiceException("Đây không phải lượt đặt của bạn.");
            }
            if (x.getStatus() == BookingStatus.CHECKED_IN) {
                throw new ServiceException("Bạn đã check-in lượt này rồi.");
            }
            if (x.getStatus() != BookingStatus.ACTIVE) {
                throw new ServiceException("Không thể check-in (trạng thái: " + x.getStatus().getLabel() + ").");
            }
            TimeSlot ts = x.getTimeSlot();
            LocalDateTime open = ts.startAt(x.getDate()).minusMinutes(Protocol.CHECKIN_EARLY_MINUTES);
            LocalDateTime close = ts.startAt(x.getDate()).plusMinutes(Protocol.CHECKIN_LATE_MINUTES);
            if (now.isBefore(open)) {
                throw new ServiceException("Chưa đến ca nên chưa check-in được. Bạn có thể check-in từ "
                        + Fmt.time(open) + " ngày " + Fmt.date(open.toLocalDate()) + ".");
            }
            if (now.isAfter(close)) {
                throw new ServiceException("Đã quá giờ check-in (hạn chót " + Fmt.time(close) + ").");
            }
            BookingDao.checkIn(c, x.getId(), now);
            x.setStatus(BookingStatus.CHECKED_IN);
            x.setCheckedInAt(now);
            rewardCheckIn(c, x, now.toLocalDate());
            return x;
        });
        Log.info("%s check-in lượt %s (%s)", user.getUsername(), b.getId(), b.getSeatId());
        events.seatsChanged(b.getDate(), b.getSlot());
        return b;
    }

    public Booking checkOut(UserInfo user, String bookingId) {
        Booking b = db.tx(c -> {
            Booking x = requireBooking(c, bookingId);
            if (!x.getUsername().equals(user.getUsername())) {
                throw new ServiceException("Đây không phải lượt đặt của bạn.");
            }
            if (x.getStatus() != BookingStatus.CHECKED_IN) {
                throw new ServiceException("Chỉ trả chỗ được khi đang sử dụng (đã check-in).");
            }
            return finish(c, x, BookingStatus.COMPLETED, "Trả chỗ sớm lúc " + Fmt.time(LocalDateTime.now()));
        });
        Log.info("%s trả chỗ %s (%s)", user.getUsername(), b.getSeatId(), b.getId());
        events.seatsChanged(b.getDate(), b.getSlot());
        return b;
    }

    /** Kết thúc một lượt đặt (đã được khóa bằng FOR UPDATE) và trả về bản cập nhật. */
    private static Booking finish(Connection c, Booking x, BookingStatus status, String note) throws SQLException {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (!BookingDao.finish(c, x.getId(), x.getStatus(), status, note, now)) {
            throw new ServiceException("Lượt đặt " + x.getId() + " vừa được cập nhật bởi thao tác khác, hãy thử lại.");
        }
        x.setStatus(status);
        x.setNote(note);
        x.setFinishedAt(now);
        return x;
    }

    // ------------------------------------------------------------------ Danh sách

    public List<Booking> myBookings(String username) {
        return db.query(c -> BookingDao.ofUser(c, username));
    }

    public List<Booking> allBookings(LocalDate date, BookingStatus status) {
        return db.query(c -> BookingDao.search(c, date, status));
    }

    // ------------------------------------------------------------------ Tác vụ nền (luồng scheduler)

    /**
     * Chạy định kỳ trên luồng scheduler:
     * - ACTIVE mà quá giờ check-in → EXPIRED (nhả chỗ cho người khác)
     * - CHECKED_IN mà hết ca → COMPLETED
     */
    public void processTimeouts() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<Booking> due = new ArrayList<>();
        for (Booking b : db.query(c -> BookingDao.liveUpTo(c, now.toLocalDate()))) {
            if (isDue(b, now)) {
                due.add(b);
            }
        }
        if (due.isEmpty()) {
            return; // không mở transaction khi không có gì cần đổi
        }
        List<Booking> changed = db.tx(c -> {
            List<Booking> list = new ArrayList<>();
            for (Booking b : due) {
                BookingStatus target = b.getStatus() == BookingStatus.ACTIVE ? BookingStatus.EXPIRED : BookingStatus.COMPLETED;
                String note = target == BookingStatus.EXPIRED ? "Không check-in đúng giờ - hệ thống tự hủy" : "Hết ca";
                // Điều kiện "AND status = trạng thái cũ": nếu người dùng vừa check-in/hủy thì bỏ qua
                if (BookingDao.finish(c, b.getId(), b.getStatus(), target, note, now)) {
                    b.setStatus(target);
                    b.setNote(note);
                    b.setFinishedAt(now);
                    if (target == BookingStatus.EXPIRED && !b.isWalkIn()) { // không đến → trừ điểm uy tín
                        ReputationService.Change ch = ReputationService.change(c, b.getUsername(),
                                -Reputation.NO_SHOW_PENALTY, "Không đến / không check-in lượt " + b.getId()
                                        + " (" + b.describe() + ")", b.getId(), now.toLocalDate());
                        if (ch != null) {
                            b.setReputationNote(ch.describe());
                        }
                    }
                    list.add(b);
                }
            }
            return list;
        });
        for (Booking b : changed) {
            if (b.getStatus() == BookingStatus.EXPIRED) {
                Log.info("Tự hủy lượt %s của %s do không check-in", b.getId(), b.getUsername());
                events.sendToUser(b.getUsername(), EventType.BOOKING_UPDATED, b.isWalkIn()
                        ? "Lượt đặt tại quầy " + b.getId() + " của " + b.getFullName() + " (" + b.describe()
                        + ") đã bị hủy do sinh viên không đến check-in."
                        : "Lượt đặt " + b.getId() + " (" + b.describe() + ") đã bị hủy do bạn không check-in đúng giờ"
                        + (b.getReputationNote() != null ? ": " + b.getReputationNote() + "." : "."));
            }
        }
        notifySeatChanges(changed);
    }

    private static boolean isDue(Booking b, LocalDateTime now) {
        TimeSlot ts = b.getTimeSlot();
        if (ts == null) {
            return false;
        }
        if (b.getStatus() == BookingStatus.ACTIVE) {
            return now.isAfter(ts.startAt(b.getDate()).plusMinutes(Protocol.CHECKIN_LATE_MINUTES));
        }
        if (b.getStatus() == BookingStatus.CHECKED_IN) {
            return !now.isBefore(ts.endAt(b.getDate()));
        }
        return false;
    }

    // ------------------------------------------------------------------ Hỗ trợ cho ContentService

    /**
     * Hủy các lượt đặt còn hiệu lực của những chỗ bị xóa / bảo trì.
     * Gọi bên trong transaction của ContentService (dùng chung kết nối {@code c}).
     */
    static List<Booking> cancelLiveBookingsForSeats(Connection c, Collection<String> seatIds, String reason)
            throws SQLException {
        List<Booking> cancelled = new ArrayList<>();
        for (Booking b : BookingDao.liveOfSeatsForUpdate(c, seatIds)) {
            cancelled.add(finish(c, b, BookingStatus.CANCELLED, reason));
        }
        return cancelled;
    }

    /** Báo cho từng người bị hủy và cập nhật sơ đồ chỗ cho mọi client. */
    void notifyCancelled(List<Booking> cancelled) {
        for (Booking b : cancelled) {
            events.sendToUser(b.getUsername(), EventType.BOOKING_UPDATED,
                    "Lượt đặt " + b.getId() + " (" + b.describe() + ") đã bị hủy: " + b.getNote());
        }
        notifySeatChanges(cancelled);
    }

    private void notifySeatChanges(List<Booking> changed) {
        Set<String> sent = new LinkedHashSet<>();
        for (Booking b : changed) {
            if (sent.add(b.getDate() + "#" + b.getSlot())) {
                events.seatsChanged(b.getDate(), b.getSlot());
            }
        }
    }

    // ------------------------------------------------------------------ Thống kê

    public LinkedHashMap<String, String> todayStats() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        return db.query(c -> {
            Map<BookingStatus, Integer> byStatus = BookingDao.countByStatusOn(c, today);
            int total = 0;
            for (int n : byStatus.values()) {
                total += n;
            }
            int upcoming = 0;
            for (int[] ds : BookingDao.activeSlotsFrom(c, today)) {
                TimeSlot ts = TimeSlot.of(ds[1]);
                if (ts != null && ts.endAt(LocalDate.ofEpochDay(ds[0])).isAfter(now)) {
                    upcoming++;
                }
            }
            LinkedHashMap<String, String> m = new LinkedHashMap<>();
            m.put("Tổng số khu vực / chỗ ngồi", ZoneDao.count(c) + " khu / " + SeatDao.count(c) + " chỗ");
            m.put("Chỗ đang bảo trì", String.valueOf(SeatDao.countInactive(c)));
            m.put("Lượt đặt trong hôm nay", String.valueOf(total));
            m.put("Đang sử dụng (đã check-in)", String.valueOf(byStatus.getOrDefault(BookingStatus.CHECKED_IN, 0)));
            m.put("Hôm nay: đã hủy / quá hạn", byStatus.getOrDefault(BookingStatus.CANCELLED, 0) + " / "
                    + byStatus.getOrDefault(BookingStatus.EXPIRED, 0));
            m.put("Lượt đặt sắp tới (chưa diễn ra)", String.valueOf(upcoming));
            return m;
        });
    }

    // ------------------------------------------------------------------ Kiểm tra dữ liệu vào

    private static TimeSlot requireSlot(LocalDate date, int slot) {
        if (date == null) {
            throw new ServiceException("Vui lòng chọn ngày.");
        }
        TimeSlot ts = TimeSlot.of(slot);
        if (ts == null) {
            throw new ServiceException("Ca học không hợp lệ.");
        }
        return ts;
    }

    private static Booking requireBooking(Connection c, String id) throws SQLException {
        Booking b = id == null ? null : BookingDao.findForUpdate(c, id);
        if (b == null) {
            throw new ServiceException("Không tìm thấy lượt đặt " + id + ".");
        }
        return b;
    }

    /** Cộng điểm uy tín khi check-in đúng hẹn (trong transaction đang mở). */
    private static void rewardCheckIn(Connection c, Booking x, LocalDate today) throws SQLException {
        ReputationService.Change ch = ReputationService.change(c, x.getUsername(), Reputation.CHECK_IN_REWARD,
                "Check-in đúng hẹn lượt " + x.getId() + " (" + x.describe() + ")", x.getId(), today);
        if (ch != null) {
            x.setReputationNote(ch.describe());
        }
    }

    /** Lý do không được đặt theo điểm uy tín (tạm khóa / vượt số ngày được đặt trước), hoặc null. */
    private static String reputationError(Standing s, Reputation.Level level, LocalDate date) {
        if (s == null || s.role == Role.ADMIN) {
            return null;
        }
        if (level == Reputation.Level.LOCKED) {
            return s.lockedUntil != null
                    ? "Tài khoản đang bị tạm khóa đặt chỗ đến hết ngày " + Fmt.date(s.lockedUntil.minusDays(1))
                    + " do điểm uy tín thấp (" + s.score + " điểm). Vui lòng liên hệ thủ thư."
                    : "Tài khoản đang bị tạm khóa đặt chỗ do điểm uy tín thấp (" + s.score
                    + " điểm). Vui lòng liên hệ thủ thư.";
        }
        if (date.isAfter(LocalDate.now().plusDays(level.getDaysAhead() - 1))) {
            return "Điểm uy tín của bạn đang ở mức " + level.getLabel() + " (" + s.score + " điểm): chỉ được đặt trước tối đa "
                    + level.getDaysAhead() + " ngày (tính cả hôm nay). Đạt " + Reputation.MAX
                    + " điểm uy tín để được đặt trước " + Reputation.Level.EXCELLENT.getDaysAhead() + " ngày.";
        }
        return null;
    }

    /** Quy tắc cho quản trị viên: không đặt ngày đã qua / ca đã kết thúc, đặt trước tối đa 3 ngày. */
    static String adminWindowError(LocalDate date, TimeSlot ts, LocalDateTime now) {
        if (date.isBefore(now.toLocalDate())) {
            return "Ngày " + Fmt.date(date) + " đã qua, không thể đặt chỗ.";
        }
        if (date.isAfter(now.toLocalDate().plusDays(Protocol.ADMIN_BOOKING_DAYS_AHEAD - 1))) {
            return "Thư viện chỉ cho đặt trước tối đa " + Protocol.ADMIN_BOOKING_DAYS_AHEAD + " ngày (tính cả hôm nay).";
        }
        if (!now.isBefore(ts.endAt(date))) {
            return ts.getLabel() + " ngày " + Fmt.date(date) + " đã kết thúc.";
        }
        return null;
    }

    /** Trả về lý do không được đặt, hoặc null nếu còn đặt được. */
    static String bookingWindowError(LocalDate date, TimeSlot ts, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (date.isBefore(today)) {
            return "Ngày " + Fmt.date(date) + " đã qua, không thể đặt chỗ.";
        }
        if (date.isAfter(today.plusDays(Protocol.BOOKING_DAYS_AHEAD - 1))) {
            return "Thư viện chỉ cho đặt trước tối đa " + Protocol.BOOKING_DAYS_AHEAD + " ngày (tính cả hôm nay).";
        }
        if (!now.isBefore(ts.startAt(date).plusMinutes(Protocol.CHECKIN_LATE_MINUTES))) {
            return ts.getLabel() + " ngày " + Fmt.date(date) + " đã bắt đầu quá "
                    + Protocol.CHECKIN_LATE_MINUTES + " phút, không thể đặt thêm.";
        }
        return null;
    }
}
