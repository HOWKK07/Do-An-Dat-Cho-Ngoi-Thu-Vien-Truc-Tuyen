package library.server.service;

import library.common.Booking;
import library.common.BookingStatus;
import library.common.Companion;
import library.common.EventType;
import library.common.Fmt;
import library.common.Message;
import library.common.Protocol;
import library.common.Reputation;
import library.common.Role;
import library.common.Seat;
import library.common.SeatMap;
import library.common.SeatState;
import library.common.SeatType;
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
import library.server.db.UserDao.UserRecord;
import library.server.db.ZoneDao;
import library.server.thread.EventBroadcaster;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    /** Thứ tự mở bàn cho ngồi ghép khi khu chỗ đơn đã kín: bàn đôi → bàn nhóm → phòng riêng. */
    public static final List<SeatType> SHARE_ORDER =
            Collections.unmodifiableList(Arrays.asList(SeatType.PAIR, SeatType.GROUP, SeatType.ROOM));

    private final Database db;
    private final EventBroadcaster events;
    /** Đặt lẻ được xử lý tuần tự để 2 người cùng lúc không "mở" 2 bàn mới (quy tắc chỉ mở từng bàn). */
    private final Object shareLock = new Object();
    /** Hẹn giờ chạy một lần (luồng scheduler của server); null = không hẹn (VD trong kiểm thử). */
    private volatile DelayedRunner delayed;

    /** Cách chạy một tác vụ sau một khoảng thời gian - server dùng {@code ServerThreadPools.scheduleOnce}. */
    public interface DelayedRunner {
        void runAfter(Runnable task, long delayMs);
    }

    public BookingService(Database db, EventBroadcaster events) {
        this.db = db;
        this.events = events;
    }

    public void setDelayedRunner(DelayedRunner delayed) {
        this.delayed = delayed;
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
            Map<String, List<Booking>> bySeat = new HashMap<>();
            int mine = 0;
            List<Booking> live = BookingDao.liveOnDate(c, date);
            if (admin) {
                BookingDao.attachCompanions(c, live); // quản trị viên xem được ai đi cùng
            }
            for (Booking b : live) {
                if (b.getUsername().equals(viewer.getUsername()) && !b.isWalkIn()) {
                    mine++;
                }
                if (b.getSlot() == slot) {
                    bySeat.computeIfAbsent(b.getSeatId(), k -> new ArrayList<>()).add(b);
                }
            }
            SeatMap map = new SeatMap(date, slot);
            List<Zone> zones = ZoneDao.list(c);
            List<Seat> seats = SeatDao.list(c);
            map.getZones().addAll(zones);
            Sharing sharing = computeSharing(seats, zones, bySeat);
            for (Seat s : seats) {
                SeatView v = new SeatView(s);
                List<Booking> onSeat = bySeat.getOrDefault(s.getId(), Collections.emptyList());
                Booking whole = null;
                Booking mineB = null;
                List<Booking> sharers = new ArrayList<>();
                for (Booking b : onSeat) {
                    if (b.isSharedPlace()) {
                        sharers.add(b);
                    } else {
                        whole = b;
                    }
                    if (b.getUsername().equals(viewer.getUsername()) && !b.isWalkIn()) {
                        mineB = b;
                    }
                }
                v.setSharedTaken(sharers.size());
                v.setShareOpen(sharing.open.contains(s.getId()));
                if (mineB != null) {
                    v.setState(SeatState.MINE);
                    v.setBookingId(mineB.getId());
                    v.setBookingStatus(mineB.getStatus());
                    v.setMyPlace(mineB.getPlace());
                } else if (whole != null) {
                    v.setState(SeatState.BOOKED);
                    v.setBookingStatus(whole.getStatus());
                    if (admin) { // chỉ quản trị viên mới thấy ai đang giữ chỗ
                        v.setBookingId(whole.getId());
                        v.setBookedBy(holderInfo(whole));
                    }
                } else if (!sharers.isEmpty()) {
                    v.setState(sharers.size() >= s.getCapacity() ? SeatState.BOOKED : SeatState.PARTIAL);
                    if (admin) {
                        StringBuilder who = new StringBuilder();
                        for (Booking b : sharers) {
                            who.append(who.length() > 0 ? "; " : "").append("#").append(b.getPlace()).append(" ")
                                    .append(holderInfo(b)).append(" - ").append(b.getId()).append(" (")
                                    .append(b.getStatus().getLabel()).append(")");
                        }
                        v.setBookedBy(who.toString());
                    }
                } else if (!s.isActive()) {
                    v.setState(SeatState.MAINTENANCE);
                } else {
                    v.setState(SeatState.AVAILABLE);
                }
                map.getSeats().add(v);
            }
            map.setShareNote(sharing.describe());
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
        return book(user, rawSeatId, date, slot, false);
    }

    /**
     * @param onePlace true = đặt lẻ MỘT chỗ trên bàn đang mở ngồi ghép; false = đặt cả chỗ / cả bàn như thường
     */
    public Booking book(UserInfo user, String rawSeatId, LocalDate date, int slot, boolean onePlace) {
        return book(user, rawSeatId, date, slot, onePlace, null);
    }

    /**
     * @param companions người đi cùng (MSSV hoặc tên đăng nhập) - bắt buộc khi sinh viên đặt CẢ bàn nhiều chỗ:
     *                   tối thiểu {@link Protocol#minPeopleForTable} người kể cả người đặt
     */
    public Booking book(UserInfo user, String rawSeatId, LocalDate date, int slot, boolean onePlace,
                        List<String> companions) {
        if (onePlace) {
            synchronized (shareLock) {
                return doBook(user, rawSeatId, date, slot, true, null);
            }
        }
        return doBook(user, rawSeatId, date, slot, false, companions);
    }

    private Booking doBook(UserInfo user, String rawSeatId, LocalDate date, int slot, boolean onePlace,
                           List<String> companions) {
        TimeSlot ts = requireSlot(date, slot);
        String seatId = rawSeatId == null ? "" : rawSeatId.trim().toUpperCase();
        String err = bookingWindowError(date, ts, LocalDateTime.now());
        if (err != null) {
            throw new ServiceException(err);
        }

        List<UserRecord> mates = new ArrayList<>();
        Booking created = db.tx(c -> {
            mates.clear(); // transaction có thể được chạy lại khi deadlock
            Seat seat = SeatDao.findForUpdate(c, seatId); // khóa chỗ: các lượt đặt vào cùng chỗ / bàn xử lý lần lượt
            if (seat == null) {
                throw new ServiceException("Chỗ ngồi " + seatId + " không tồn tại.");
            }
            if (!seat.isActive()) {
                throw new ServiceException("Chỗ " + seatId + " đang tạm ngừng phục vụ (bảo trì).");
            }
            // Khóa dòng người dùng: các yêu cầu của cùng một người được xử lý tuần tự; đọc luôn điểm uy tín
            Standing standing = UserDao.standingForUpdate(c, user.getUsername());
            // Đặt CẢ bàn nhiều chỗ: khóa luôn tài khoản những người đi cùng (trước mọi lệnh đọc khác) để họ không
            // vừa được khai báo ở đây vừa tự đặt chỗ khác cùng lúc
            if (!onePlace && seat.getCapacity() >= 2 && standing.role == Role.STUDENT) {
                mates.addAll(lockCompanions(c, user, seat, companions));
            }
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
            Booking asCompanion = BookingDao.liveAsCompanion(c, user.getUsername(), date, slot);
            if (asCompanion != null) {
                throw new ServiceException("Bạn đã được " + asCompanion.getFullName() + " khai báo là người đi cùng ở bàn "
                        + asCompanion.getSeatId() + " trong ca này nên không đặt thêm chỗ khác.");
            }
            for (UserRecord m : mates) {
                checkCanJoin(c, m, date, slot, null);
            }
            if (countThatDay >= level.getMaxPerDay()) {
                throw new ServiceException("Bạn đã đặt đủ " + level.getMaxPerDay() + " ca trong ngày " + Fmt.date(date)
                        + (level.getMaxPerDay() >= Protocol.MAX_BOOKINGS_PER_DAY ? "." : " (giới hạn của mức uy tín " + level.getLabel()
                        + ", " + standing.score + " điểm)."));
            }
            int place = 0;
            if (onePlace) {
                place = pickSharedPlace(c, seat, date, slot);
            } else if (BookingDao.isSeatTaken(c, seatId, date, slot)) {
                throw seatTaken(seatId);
            }

            Zone zone = ZoneDao.find(c, seat.getZoneId());
            Booking b = new Booking();
            b.setPlace(place);
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
                BookingDao.insert(c, b, true);
                for (UserRecord m : mates) {
                    BookingDao.addCompanion(c, b.getId(), m.username, b.getCreatedAt());
                    b.getCompanions().add(new Companion(m.username, m.fullName, m.studentId, Companion.Status.PENDING));
                }
                b.setSeatCapacity(seat.getCapacity());
                return b;
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

        Log.info("%s đặt %s -> %s%s", user.getUsername(), created.describe(), created.getId(),
                mates.isEmpty() ? "" : " (mời đi cùng: " + created.getCompanionNames() + ")");
        for (UserRecord m : mates) {
            sendInvite(m.username, user.getFullName(), created);
        }
        if (!mates.isEmpty()) {
            scheduleChecks(created.getId(), created.getCreatedAt());
        }
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
        return bookByAdmin(admin, rawSeatId, date, slot, guest, checkInNow, false);
    }

    /** @param onePlace true = đặt lẻ một chỗ trên bàn đang mở ngồi ghép (theo cùng quy tắc với sinh viên) */
    public Booking bookByAdmin(UserInfo admin, String rawSeatId, LocalDate date, int slot, WalkInInfo guest,
                               boolean checkInNow, boolean onePlace) {
        if (onePlace) {
            synchronized (shareLock) {
                return doBookByAdmin(admin, rawSeatId, date, slot, guest, checkInNow, true);
            }
        }
        return doBookByAdmin(admin, rawSeatId, date, slot, guest, checkInNow, false);
    }

    private Booking doBookByAdmin(UserInfo admin, String rawSeatId, LocalDate date, int slot, WalkInInfo guest,
                                  boolean checkInNow, boolean onePlace) {
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
            Seat seat = SeatDao.findForUpdate(c, seatId);
            if (seat == null) {
                throw new ServiceException("Chỗ ngồi " + seatId + " không tồn tại.");
            }
            if (!seat.isActive()) {
                throw new ServiceException("Chỗ " + seatId + " đang tạm ngừng phục vụ (bảo trì).");
            }
            int place = 0;
            if (onePlace) {
                place = pickSharedPlace(c, seat, date, slot);
            } else if (BookingDao.isSeatTaken(c, seatId, date, slot)) {
                throw seatTaken(seatId);
            }
            Zone zone = ZoneDao.find(c, seat.getZoneId());
            Booking b = new Booking();
            b.setPlace(place);
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
    // ------------------------------------------------------------------ Ngồi ghép (đặt lẻ từng chỗ)

    /** Các bàn đang mở cho đặt lẻ trong một ca. */
    static final class Sharing {
        final Set<String> open = new LinkedHashSet<>();
        boolean singlesFull;

        String describe() {
            if (!open.isEmpty()) {
                return (singlesFull ? "Khu chỗ đơn đã kín - " : "") + "bàn " + String.join(", ", open)
                        + " đang mở cho đặt lẻ từng chỗ (ngồi ghép). Bàn đủ người thì mở bàn tiếp theo.";
            }
            return singlesFull ? "Khu chỗ đơn đã kín và không còn bàn trống để mở ngồi ghép." : null;
        }
    }

    static boolean isSharable(Seat s) {
        return s.isActive() && s.getCapacity() >= 2 && SHARE_ORDER.contains(s.getType());
    }

    /**
     * QUY TẮC NGỒI GHÉP của một ca (tính lại mỗi lần, không cần lưu trạng thái "đang mở"):
     * <ol>
     *   <li>Bàn đã có người đặt lẻ mà còn chỗ → vẫn mở để lấp cho đủ bàn.</li>
     *   <li>Nếu không có bàn nào như vậy và khu chỗ đơn (mọi chỗ đơn đang phục vụ) đã kín → mở THÊM MỘT bàn trống
     *       đầu tiên theo thứ tự {@link #SHARE_ORDER} (bàn đôi → bàn nhóm → phòng riêng), trong cùng loại theo thứ tự
     *       khu rồi mã chỗ.</li>
     * </ol>
     * Vì vậy bàn đang mở đủ người thì lần đặt sau sẽ thấy bàn kế tiếp được mở - mở lần lượt từng bàn.
     */
    static Sharing computeSharing(List<Seat> seats, List<Zone> zones, Map<String, List<Booking>> bySeat) {
        Sharing sh = new Sharing();
        int singles = 0;
        int singlesTaken = 0;
        for (Seat s : seats) {
            if (s.isActive() && s.getType() == SeatType.SINGLE) {
                singles++;
                if (!bySeat.getOrDefault(s.getId(), Collections.emptyList()).isEmpty()) {
                    singlesTaken++;
                }
            }
        }
        sh.singlesFull = singles > 0 && singlesTaken >= singles;

        Map<String, Integer> zoneOrder = new HashMap<>();
        for (int i = 0; i < zones.size(); i++) {
            zoneOrder.put(zones.get(i).getId(), i);
        }
        List<Seat> tables = new ArrayList<>();
        for (Seat s : seats) {
            if (isSharable(s)) {
                tables.add(s);
            }
        }
        tables.sort(Comparator.comparingInt((Seat s) -> SHARE_ORDER.indexOf(s.getType()))
                .thenComparingInt(s -> zoneOrder.getOrDefault(s.getZoneId(), Integer.MAX_VALUE))
                .thenComparing(Seat::getId));

        for (Seat s : tables) {
            List<Booking> onSeat = bySeat.getOrDefault(s.getId(), Collections.emptyList());
            boolean whole = onSeat.stream().anyMatch(b -> !b.isSharedPlace());
            long sharers = onSeat.stream().filter(Booking::isSharedPlace).count();
            if (!whole && sharers > 0 && sharers < s.getCapacity()) {
                sh.open.add(s.getId());
            }
        }
        if (sh.open.isEmpty() && sh.singlesFull) {
            for (Seat s : tables) {
                if (bySeat.getOrDefault(s.getId(), Collections.emptyList()).isEmpty()) {
                    sh.open.add(s.getId());
                    break;
                }
            }
        }
        return sh;
    }

    private static Sharing loadSharing(Connection c, LocalDate date, int slot) throws SQLException {
        Map<String, List<Booking>> bySeat = new HashMap<>();
        for (Booking b : BookingDao.liveOnDate(c, date)) {
            if (b.getSlot() == slot) {
                bySeat.computeIfAbsent(b.getSeatId(), k -> new ArrayList<>()).add(b);
            }
        }
        return computeSharing(SeatDao.list(c), ZoneDao.list(c), bySeat);
    }

    /** Kiểm tra bàn đang mở ngồi ghép và chọn vị trí trống nhỏ nhất (1, 2, ...) trên bàn. */
    private static int pickSharedPlace(Connection c, Seat seat, LocalDate date, int slot) throws SQLException {
        if (!SHARE_ORDER.contains(seat.getType()) || seat.getCapacity() < 2) {
            throw new ServiceException("Chỗ " + seat.getId() + " là chỗ 1 người, không đặt lẻ được.");
        }
        Sharing sh = loadSharing(c, date, slot);
        List<Booking> onSeat = BookingDao.liveOfSeat(c, seat.getId(), date, slot);
        if (!sh.open.contains(seat.getId())) {
            boolean wholeBooked = onSeat.stream().anyMatch(b -> !b.isSharedPlace());
            if (!onSeat.isEmpty() && !wholeBooked) { // bàn ghép vừa đủ người
                throw new ServiceException("Bàn " + seat.getId() + " vừa đủ người ngồi ghép."
                        + (sh.open.isEmpty() ? "" : " Bàn đang mở tiếp theo: " + String.join(", ", sh.open) + "."));
            }
            if (wholeBooked) {
                throw seatTaken(seat.getId());
            }
            if (!sh.open.isEmpty()) {
                throw new ServiceException("Bàn " + seat.getId() + " chưa mở đặt lẻ. Thư viện mở lần lượt từng bàn - "
                        + "hiện đang mở bàn " + String.join(", ", sh.open) + ".");
            }
            throw new ServiceException(sh.singlesFull ? "Không còn bàn nào mở cho ngồi ghép trong ca này."
                    : "Chỉ mở đặt lẻ từng chỗ (ngồi ghép) khi khu chỗ đơn đã kín. Hiện vẫn còn chỗ đơn trống - "
                    + "vui lòng chọn chỗ đơn, hoặc đặt cả bàn.");
        }
        boolean[] used = new boolean[seat.getCapacity() + 1];
        for (Booking b : onSeat) {
            if (!b.isSharedPlace()) {
                throw seatTaken(seat.getId());
            }
            if (b.getPlace() < used.length) {
                used[b.getPlace()] = true;
            }
        }
        for (int p = 1; p < used.length; p++) {
            if (!used[p]) {
                return p;
            }
        }
        throw new ServiceException("Bàn " + seat.getId() + " vừa đủ người. Vui lòng chọn bàn đang mở tiếp theo.");
    }

    /** Kiểm tra danh sách người đi cùng khi đặt cả bàn và khóa tài khoản của họ. */
    private static List<UserRecord> lockCompanions(Connection c, UserInfo booker, Seat seat, List<String> raw)
            throws SQLException {
        int capacity = seat.getCapacity();
        int minPeople = Protocol.minPeopleForTable(capacity);
        Set<String> idents = new LinkedHashSet<>();
        if (raw != null) {
            for (String r : raw) {
                if (r != null && !r.trim().isEmpty()) {
                    idents.add(r.trim().toLowerCase());
                }
            }
        }
        List<UserRecord> mates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String ident : idents) {
            UserRecord u = UserDao.findByUsernameOrStudentIdForUpdate(c, ident);
            if (u == null) {
                throw new CompanionException("Không tìm thấy sinh viên '" + ident
                        + "'. Hãy nhập đúng mã số sinh viên hoặc tên đăng nhập (người đi cùng cần có tài khoản).");
            }
            if (u.role != Role.STUDENT) {
                throw new CompanionException("'" + ident + "' không phải tài khoản sinh viên.");
            }
            if (u.username.equals(booker.getUsername())) {
                throw new CompanionException("Không cần khai báo chính bạn - chỉ nhập những người đi cùng.");
            }
            if (seen.add(u.username)) {
                mates.add(u);
            }
        }
        if (mates.size() + 1 < minPeople) {
            throw new CompanionException("Bàn " + seat.getId() + " có " + capacity + " chỗ: đặt cả bàn cần ít nhất "
                    + minPeople + " người (gồm bạn), hãy khai báo thêm " + (minPeople - 1 - mates.size())
                    + " người đi cùng. Đi ít người hơn thì hãy đặt chỗ đơn, hoặc đặt 1 chỗ ngồi ghép khi bàn mở.");
        }
        if (mates.size() > capacity - 1) {
            throw new CompanionException("Bàn " + seat.getId() + " chỉ có " + capacity + " chỗ - khai báo tối đa "
                    + (capacity - 1) + " người đi cùng.");
        }
        return mates;
    }

    private static String holderInfo(Booking b) {
        if (!b.isWalkIn()) {
            return b.getFullName() + " (" + b.getUsername() + ")"
                    + (b.getCompanions().isEmpty() ? "" : ", đi cùng: " + b.getCompanionsLabel());
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
        List<String> mates = new ArrayList<>();
        Booking b = db.tx(c -> {
            mates.clear();
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
            mates.addAll(BookingDao.companionUsernames(c, x.getId()));
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
        notifyCompanionsCancelled(mates, b, user.getFullName() + " đã hủy");
        return b;
    }

    /** Báo cho những người đi cùng biết bàn đã bị hủy để họ tự đặt chỗ khác. */
    private void notifyCompanionsCancelled(List<String> mates, Booking b, String who) {
        for (String m : mates) {
            events.sendToUser(m, EventType.BOOKING_UPDATED, who + " lượt đặt " + b.getId() + " (" + b.describe()
                    + ") mà bạn được khai báo đi cùng. Bạn có thể tự đặt chỗ khác.");
        }
    }

    public Booking cancelByAdmin(UserInfo admin, String bookingId, String reason) {
        String why = reason == null || reason.trim().isEmpty() ? "" : ": " + reason.trim();
        List<String> mates = new ArrayList<>();
        Booking b = db.tx(c -> {
            mates.clear();
            Booking x = requireBooking(c, bookingId);
            if (!x.getStatus().isLive()) {
                throw new ServiceException("Lượt đặt này đã kết thúc (" + x.getStatus().getLabel() + ").");
            }
            mates.addAll(BookingDao.companionUsernames(c, x.getId()));
            return finish(c, x, BookingStatus.CANCELLED, "Quản trị viên hủy" + why);
        });
        Log.info("Quản trị viên %s hủy lượt đặt %s của %s", admin.getUsername(), b.getId(), b.getUsername());
        events.seatsChanged(b.getDate(), b.getSlot());
        notifyCompanionsCancelled(mates, b, "Quản trị viên đã hủy");
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
            String notReady = companionsNotReady(c, x);
            if (notReady != null) {
                throw new ServiceException(notReady);
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
        return db.query(c -> {
            List<Booking> list = BookingDao.ofUser(c, username);
            BookingDao.attachCompanions(c, list);
            return list;
        });
    }

    public List<Booking> allBookings(LocalDate date, BookingStatus status) {
        return db.query(c -> {
            List<Booking> list = BookingDao.search(c, date, status);
            BookingDao.attachCompanions(c, list);
            return list;
        });
    }

    /** Các bàn mà sinh viên được người khác khai báo đi cùng, kèm trạng thái xác nhận của sinh viên đó. */
    public List<Booking> companionInvites(String username) {
        return db.query(c -> {
            List<Booking> list = BookingDao.invitesOf(c, username);
            BookingDao.attachCompanions(c, list);
            return list;
        });
    }

    // ------------------------------------------------------------------ Xác nhận người đi cùng

    /**
     * Người được khai báo đi cùng trả lời lời mời. Đồng ý: được tính vào số người để check-in, nhưng nếu cả nhóm
     * không đến sẽ bị trừ {@link Reputation#COMPANION_NO_SHOW_PENALTY} điểm. Từ chối: được tự đặt chỗ khác trong ca,
     * người đặt được báo để khai báo người thay thế. Trả lời được khi lượt đặt còn chờ check-in.
     */
    public Booking respondCompanion(UserInfo user, String bookingId, boolean accept) {
        String[] bookerNote = new String[1];
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Booking b = db.tx(c -> {
            bookerNote[0] = null;
            Booking x = requireBooking(c, bookingId); // khóa lượt đặt: các thay đổi danh sách xử lý lần lượt
            List<Companion> list = BookingDao.companionsOf(c, x.getId());
            Companion me = list.stream().filter(m -> m.getUsername().equals(user.getUsername())).findFirst().orElse(null);
            if (me == null) {
                throw new ServiceException("Bạn không có trong danh sách người đi cùng của lượt đặt " + x.getId() + ".");
            }
            if (x.getStatus() == BookingStatus.CHECKED_IN) {
                throw new ServiceException("Nhóm của " + x.getFullName() + " đã check-in, không cần xác nhận nữa.");
            }
            if (x.getStatus() != BookingStatus.ACTIVE) {
                throw new ServiceException("Lượt đặt " + x.getId() + " đã kết thúc (" + x.getStatus().getLabel() + ").");
            }
            if (me.isDeclined()) {
                throw new ServiceException("Bạn đã từ chối lời mời này. Nếu muốn đi cùng, hãy nhờ " + x.getFullName()
                        + " thêm lại bạn vào danh sách người đi cùng.");
            }
            Companion.Status target = accept ? Companion.Status.ACCEPTED : Companion.Status.DECLINED;
            x.setMyCompanionStatus(target);
            if (me.getStatus() != target) {
                BookingDao.setCompanionStatus(c, x.getId(), user.getUsername(), target, now);
            }
            BookingDao.attachCompanions(c, Collections.singletonList(x));
            if (me.getStatus() == target) {
                return x; // bấm lại lần nữa: không đổi gì, không báo lại
            }
            String who = user.getFullName() + " đã " + (accept ? "đồng ý" : "từ chối") + " đi cùng bạn ở "
                    + x.describe() + " (mã " + x.getId() + "). ";
            if (x.isCompanionsReady()) {
                bookerNote[0] = who + "Đã đủ người xác nhận - nhóm có thể check-in.";
            } else if (x.isCompanionsShort()) {
                bookerNote[0] = who + "Bàn đang THIẾU NGƯỜI: cần " + x.getCompanionsNeeded() + " người đi cùng. Hãy vào "
                        + "\"Lịch đặt của tôi\" → \"Sửa người đi cùng\" để thêm người trước "
                        + Fmt.time(x.getCompanionDeadline()) + ", nếu không lượt đặt sẽ tự hủy.";
            } else {
                bookerNote[0] = who + "Đã xác nhận " + x.getAcceptedCompanionCount() + "/" + x.getCompanionsNeeded()
                        + " người (cần đủ trước " + Fmt.time(x.getCompanionDeadline()) + ").";
            }
            return x;
        });
        if (bookerNote[0] != null) {
            Log.info("%s %s lời mời đi cùng lượt %s", user.getUsername(), accept ? "đồng ý" : "từ chối", b.getId());
            events.sendToUser(b.getUsername(), EventType.BOOKING_UPDATED, bookerNote[0]);
        }
        return b;
    }

    /**
     * Người đặt cả bàn sửa danh sách người đi cùng (VD khi có người từ chối). Người giữ nguyên trong danh sách
     * giữ trạng thái xác nhận; người mới (hoặc được mời lại) ở trạng thái chờ xác nhận; người bị bỏ ra được báo tin.
     */
    public Booking updateCompanions(UserInfo user, String bookingId, List<String> rawCompanions) {
        List<UserRecord> invited = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Booking b = db.tx(c -> {
            invited.clear();
            removed.clear();
            Booking x = requireBooking(c, bookingId);
            if (!x.getUsername().equals(user.getUsername())) {
                throw new ServiceException("Đây không phải lượt đặt của bạn.");
            }
            if (x.getStatus() != BookingStatus.ACTIVE) {
                throw new ServiceException("Chỉ sửa được người đi cùng khi lượt đặt đang chờ check-in (hiện: "
                        + x.getStatus().getLabel() + ").");
            }
            LocalDateTime close = x.getTimeSlot().startAt(x.getDate()).plusMinutes(Protocol.CHECKIN_LATE_MINUTES);
            if (LocalDateTime.now().isAfter(close)) {
                throw new ServiceException("Đã quá giờ check-in của lượt đặt này.");
            }
            Seat seat = SeatDao.findForUpdate(c, x.getSeatId());
            if (seat == null || seat.getCapacity() < 2 || x.isSharedPlace() || x.isWalkIn()) {
                throw new ServiceException("Lượt đặt này không phải đặt cả bàn nhiều chỗ nên không có người đi cùng.");
            }
            List<UserRecord> mates = lockCompanions(c, user, seat, rawCompanions);
            Map<String, Companion> old = new HashMap<>();
            for (Companion m : BookingDao.companionsOf(c, x.getId())) {
                old.put(m.getUsername(), m);
            }
            Set<String> keep = new HashSet<>();
            for (UserRecord m : mates) {
                keep.add(m.username);
                Companion was = old.get(m.username);
                if (was != null && !was.isDeclined()) {
                    continue; // giữ nguyên trạng thái đã xác nhận / đang chờ
                }
                checkCanJoin(c, m, x.getDate(), x.getSlot(), x.getId());
                BookingDao.addCompanion(c, x.getId(), m.username, now);
                invited.add(m);
            }
            for (Companion m : old.values()) {
                if (!keep.contains(m.getUsername())) {
                    BookingDao.removeCompanion(c, x.getId(), m.getUsername());
                    if (!m.isDeclined()) {
                        removed.add(m.getUsername());
                    }
                }
            }
            if (old.values().stream().anyMatch(Companion::isReminded)) {
                BookingDao.markReminded(c, x.getId()); // đã nhắc rồi thì sửa danh sách cũng không nhắc lại
            }
            BookingDao.attachCompanions(c, Collections.singletonList(x));
            return x;
        });
        Log.info("%s sửa người đi cùng lượt %s: %s", user.getUsername(), b.getId(), b.getCompanionsLabel());
        for (UserRecord m : invited) {
            sendInvite(m.username, user.getFullName(), b); // hạn trả lời vẫn tính từ lúc đặt
        }
        for (String m : removed) {
            events.sendToUser(m, EventType.BOOKING_UPDATED, user.getFullName() + " đã bỏ bạn khỏi danh sách người đi cùng ở "
                    + b.describe() + " (mã " + b.getId() + "). Bạn có thể tự đặt chỗ khác.");
        }
        return b;
    }

    // ------------------------------------------------------------------ Nhắc sau 15 phút, tự hủy sau 30 phút

    /**
     * Hẹn kiểm tra lượt {@code bookingId} tại 2 mốc CỐ ĐỊNH tính từ lúc đặt ({@code bookedAt}): +15 phút (nhắc người
     * đặt) và +30 phút (tự hủy nếu vẫn chưa đủ người xác nhận). Mỗi mốc là một mục hẹn giờ chạy 1 lần.
     */
    private void scheduleChecks(String bookingId, LocalDateTime bookedAt) {
        DelayedRunner d = delayed;
        if (d == null || bookedAt == null) {
            return;
        }
        for (int minutes : new int[]{Protocol.COMPANION_REMIND_MINUTES, Protocol.COMPANION_CONFIRM_MINUTES}) {
            LocalDateTime due = bookedAt.plusMinutes(minutes).plusSeconds(1);
            d.runAfter(() -> checkCompanions(bookingId), Math.max(0, Duration.between(LocalDateTime.now(), due).toMillis()));
        }
    }

    /**
     * Khởi động server: hẹn lại mốc nhắc / tự hủy cho các lượt đặt cả bàn đang chờ check-in
     * (mốc đã qua trong lúc server tắt thì chạy ngay).
     *
     * @return số lượt đặt chưa đủ người xác nhận
     */
    public int scheduleCompanionChecks() {
        List<Booking> list = db.query(c -> {
            List<Booking> l = BookingDao.liveWithCompanions(c);
            BookingDao.attachCompanions(c, l);
            return l;
        });
        int n = 0;
        for (Booking b : list) {
            if (!b.isCompanionsReady()) {
                scheduleChecks(b.getId(), b.getCreatedAt());
                n++;
            }
        }
        return n;
    }

    /**
     * Kiểm tra một lượt đặt cả bàn chưa đủ người xác nhận đi cùng (thời gian tính cố định từ lúc đặt):
     * <ul>
     *   <li>đủ 30 phút → tự hủy (như người đặt tự hủy: sát giờ dưới 60 phút trước ca thì −5 điểm), báo người đặt
     *       và người đi cùng, nhả bàn cho người khác;</li>
     *   <li>đủ 15 phút → nhắc người đặt (hộp hỏi Sửa người đi cùng / Hủy lượt đặt / Để sau), mỗi lượt đặt nhắc 1 lần.</li>
     * </ul>
     * Lượt đã đủ người, đã check-in / hủy, hoặc đã quá giờ check-in (để tác vụ quá hạn xử lý) thì bỏ qua.
     *
     * @return "hủy", "nhắc" hoặc null nếu không làm gì
     */
    public String checkCompanions(String bookingId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String[] reminder = new String[1];
        boolean[] cancelled = new boolean[1];
        List<String> mates = new ArrayList<>();
        Booking b = db.tx(c -> {
            reminder[0] = null;
            cancelled[0] = false;
            mates.clear();
            Booking x = BookingDao.findForUpdate(c, bookingId);
            if (x == null || x.getStatus() != BookingStatus.ACTIVE || x.getTimeSlot() == null || x.isWalkIn()
                    || x.isSharedPlace()) {
                return x;
            }
            LocalDateTime start = x.getTimeSlot().startAt(x.getDate());
            if (now.isAfter(start.plusMinutes(Protocol.CHECKIN_LATE_MINUTES))) {
                return x; // hết giờ check-in: tác vụ quá hạn xử lý như không đến
            }
            BookingDao.attachCompanions(c, Collections.singletonList(x));
            LocalDateTime deadline = x.getCompanionDeadline();
            if (deadline == null) {
                return x; // đủ người xác nhận rồi
            }
            String count = x.getAcceptedCompanionCount() + "/" + x.getCompanionsNeeded();
            if (!now.isBefore(deadline)) {
                for (Companion m : x.getCompanions()) {
                    if (!m.isDeclined()) {
                        mates.add(m.getUsername());
                    }
                }
                finish(c, x, BookingStatus.CANCELLED, "Tự hủy: quá " + Protocol.COMPANION_CONFIRM_MINUTES
                        + " phút chưa đủ người xác nhận đi cùng (" + count + ")");
                if (!now.isBefore(start.minusMinutes(Reputation.LATE_CANCEL_MINUTES))) {
                    ReputationService.Change ch = ReputationService.change(c, x.getUsername(),
                            -Reputation.LATE_CANCEL_PENALTY, "Lượt đặt cả bàn " + x.getId() + " (" + x.describe()
                                    + ") tự hủy sát giờ do chưa đủ người xác nhận đi cùng", x.getId(), now.toLocalDate());
                    if (ch != null) {
                        x.setReputationNote(ch.describe());
                    }
                }
                cancelled[0] = true;
                return x;
            }
            boolean remindedBefore = x.getCompanions().stream().anyMatch(Companion::isReminded);
            if (!now.isBefore(x.getCreatedAt().plusMinutes(Protocol.COMPANION_REMIND_MINUTES)) && !remindedBefore) {
                BookingDao.markReminded(c, x.getId());
                StringBuilder waiting = new StringBuilder();
                for (Companion m : x.getCompanions()) {
                    if (m.getStatus() == Companion.Status.PENDING) {
                        waiting.append(waiting.length() == 0 ? "" : ", ").append(m.getName());
                    }
                }
                reminder[0] = "Lượt đặt " + x.getId() + " (" + x.describe() + ") vẫn chưa đủ người "
                        + "xác nhận đi cùng: mới có " + count + " người đồng ý"
                        + (waiting.length() > 0 ? ", chưa trả lời: " + waiting : "")
                        + (x.isCompanionsShort() ? ". Có người đã từ chối nên cần thêm người khác" : "")
                        + ".\nNếu đến " + Fmt.time(deadline) + " vẫn chưa đủ người đồng ý, lượt đặt sẽ TỰ HỦY. "
                        + "Hãy thêm / thay người đi cùng, hoặc hủy lượt đặt nếu nhóm không đi nữa.";
            }
            return x;
        });
        if (cancelled[0]) {
            Log.info("Tự hủy lượt %s của %s: quá %d phút chưa đủ người xác nhận đi cùng", b.getId(), b.getUsername(),
                    Protocol.COMPANION_CONFIRM_MINUTES);
            events.sendToUser(b.getUsername(), EventType.BOOKING_UPDATED, "Lượt đặt " + b.getId() + " (" + b.describe()
                    + ") đã TỰ HỦY vì quá " + Protocol.COMPANION_CONFIRM_MINUTES + " phút chưa đủ người xác nhận đi cùng"
                    + (b.getReputationNote() != null ? " (" + b.getReputationNote() + ")" : "") + ".");
            notifyCompanionsCancelled(mates, b, "Hệ thống đã tự hủy (chưa đủ người xác nhận)");
            events.seatsChanged(b.getDate(), b.getSlot());
            return "hủy";
        }
        if (reminder[0] != null) {
            Log.info("Nhắc %s: lượt %s chưa đủ người xác nhận đi cùng", b.getUsername(), b.getId());
            events.sendToUser(b.getUsername(),
                    Message.event(EventType.COMPANION_REMINDER, reminder[0]).put("bookingId", b.getId()));
            return "nhắc";
        }
        return null;
    }

    /** Người được khai báo phải chưa tự đặt chỗ trong ca và chưa đi cùng bàn khác (bỏ qua lượt {@code exceptId}). */
    private static void checkCanJoin(Connection c, UserRecord m, LocalDate date, int slot, String exceptId)
            throws SQLException {
        for (Booking other : BookingDao.liveOfUserOnDate(c, m.username, date)) {
            if (other.getSlot() == slot) {
                throw new CompanionException(m.fullName + " đã tự đặt chỗ " + other.getSeatLabel()
                        + " trong ca này nên không thể là người đi cùng.");
            }
        }
        Booking taken = BookingDao.liveAsCompanion(c, m.username, date, slot, exceptId);
        if (taken != null) {
            throw new CompanionException(m.fullName + " đã được " + taken.getFullName()
                    + " khai báo đi cùng ở bàn " + taken.getSeatId() + " trong ca này.");
        }
    }

    /** Lời mời đi cùng: client hiện hộp hỏi Đồng ý / Từ chối. */
    private void sendInvite(String username, String bookerName, Booking b) {
        events.sendToUser(username, Message.event(EventType.COMPANION_INVITE, bookerName + " mời bạn đi cùng ở "
                + b.describe() + " (mã " + b.getId() + "). Bạn có đi không? Hãy trả lời trước "
                + Fmt.time(b.getCreatedAt().plusMinutes(Protocol.COMPANION_CONFIRM_MINUTES)) + " - nếu lúc đó bàn chưa đủ người "
                + "đồng ý, lượt đặt sẽ tự hủy. Đồng ý mà cả nhóm không đến thì bạn bị trừ "
                + Reputation.COMPANION_NO_SHOW_PENALTY + " điểm uy tín.").put("bookingId", b.getId()));
    }

    /**
     * Lỗi nếu lượt đặt cả bàn chưa đủ người đã xác nhận đi cùng để check-in (lượt đặt tạo trước khi có chức năng
     * khai báo người đi cùng thì bỏ qua), hoặc null nếu đủ.
     */
    private static String companionsNotReady(Connection c, Booking x) throws SQLException {
        if (x.isWalkIn() || x.isSharedPlace()) {
            return null;
        }
        BookingDao.attachCompanions(c, Collections.singletonList(x));
        if (x.isCompanionsReady()) {
            return null;
        }
        int min = x.getCompanionsNeeded() + 1;
        if (x.isCompanionsShort()) {
            return "Bàn " + x.getSeatId() + " đang thiếu người vì có người từ chối đi cùng: cần ít nhất " + min
                    + " người (gồm bạn). Hãy bấm \"Sửa người đi cùng\" để khai báo thêm người rồi check-in.";
        }
        StringBuilder waiting = new StringBuilder();
        for (Companion m : x.getCompanions()) {
            if (m.getStatus() == Companion.Status.PENDING) {
                waiting.append(waiting.length() == 0 ? "" : ", ").append(m.getName());
            }
        }
        return "Bàn " + x.getSeatId() + " cần ít nhất " + min + " người (gồm bạn) đã xác nhận đi cùng mới check-in được - "
                + "hiện mới có " + x.getAcceptedCompanionCount() + "/" + x.getCompanionsNeeded() + " người đồng ý (đang chờ: "
                + waiting + "). Hãy nhắc bạn bè mở ứng dụng, vào \"Lịch đặt của tôi\" và bấm \"Đồng ý đi cùng\".";
    }

    /** Cả nhóm không đến: những người đã bấm "Đồng ý" đi cùng cũng bị trừ điểm (trong transaction đang mở). */
    private static void penalizeAcceptedCompanions(Connection c, Booking b, LocalDate today, List<String[]> notes)
            throws SQLException {
        for (Companion m : BookingDao.companionsOf(c, b.getId())) {
            if (!m.isAccepted()) {
                continue;
            }
            ReputationService.Change ch = ReputationService.change(c, m.getUsername(), -Reputation.COMPANION_NO_SHOW_PENALTY,
                    "Đã xác nhận đi cùng " + b.getFullName() + " nhưng cả nhóm không đến - lượt " + b.getId()
                            + " (" + b.describe() + ")", b.getId(), today);
            notes.add(new String[]{m.getUsername(), "Nhóm của " + b.getFullName() + " không check-in lượt " + b.getId()
                    + " (" + b.describe() + "). Bạn đã xác nhận đi cùng nên bị trừ điểm"
                    + (ch != null ? ": " + ch.describe() : "") + "."});
        }
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
        List<String[]> mateNotes = new ArrayList<>(); // {username, nội dung thông báo}
        List<Booking> changed = db.tx(c -> {
            mateNotes.clear(); // transaction có thể được chạy lại khi deadlock
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
                        // Đặt cả bàn nhiều chỗ rồi bỏ trống → trừ nặng hơn (theo số chỗ của bàn, tối đa 40)
                        Seat seat = SeatDao.find(c, b.getSeatId());
                        int capacity = seat != null ? seat.getCapacity() : 1;
                        int penalty = Reputation.noShowPenalty(capacity, !b.isSharedPlace());
                        ReputationService.Change ch = ReputationService.change(c, b.getUsername(), -penalty,
                                "Không đến / không check-in lượt " + b.getId() + " (" + b.describe() + ")"
                                        + (penalty > Reputation.NO_SHOW_PENALTY
                                        ? " - đặt cả bàn " + capacity + " người" : ""),
                                b.getId(), now.toLocalDate());
                        if (ch != null) {
                            b.setReputationNote(ch.describe());
                        }
                        if (!b.isSharedPlace()) {
                            penalizeAcceptedCompanions(c, b, now.toLocalDate(), mateNotes);
                        }
                    }
                    list.add(b);
                }
            }
            return list;
        });
        for (String[] n : mateNotes) {
            events.sendToUser(n[0], EventType.BOOKING_UPDATED, n[1]);
        }
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

    /**
     * Mốc kế tiếp (sau {@code now}) cần gọi {@link #processTimeouts()}: 15 phút sau giờ bắt đầu mỗi ca (hết hạn
     * check-in → tự hủy lượt chưa check-in) và giờ kết thúc mỗi ca (→ hoàn thành lượt đang sử dụng).
     * Lịch ca cố định nên mỗi ngày chỉ có tối đa 10 mốc - thay cho việc quét CSDL 30 giây/lần (2.880 lần/ngày).
     * Cộng thêm 1 giây để chắc chắn đã qua hạn khi chạy.
     */
    public static LocalDateTime nextDeadline(LocalDateTime now) {
        LocalDateTime best = null;
        for (int d = 0; d <= 1; d++) {
            LocalDate date = now.toLocalDate().plusDays(d);
            for (TimeSlot ts : TimeSlot.ALL) {
                LocalDateTime expire = ts.startAt(date).plusMinutes(Protocol.CHECKIN_LATE_MINUTES).plusSeconds(1);
                LocalDateTime end = ts.endAt(date).plusSeconds(1);
                for (LocalDateTime t : new LocalDateTime[]{expire, end}) {
                    if (t.isAfter(now) && (best == null || t.isBefore(best))) {
                        best = t;
                    }
                }
            }
        }
        return best;
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
