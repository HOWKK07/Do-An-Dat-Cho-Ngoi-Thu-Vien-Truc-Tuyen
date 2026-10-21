package library.server.service;

import library.common.EventType;
import library.common.Fmt;
import library.common.Reputation;
import library.common.ReputationInfo;
import library.common.Role;
import library.common.StudentInfo;
import library.common.UserInfo;
import library.server.Log;
import library.server.db.Database;
import library.server.db.ReputationDao;
import library.server.db.UserDao;
import library.server.db.UserDao.Standing;
import library.server.thread.EventBroadcaster;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ĐIỂM UY TÍN: trừ điểm khi đặt chỗ mà không đến / hủy sát giờ, cộng điểm khi check-in đúng hẹn,
 * hạn chế hoặc tạm khóa quyền đặt chỗ khi điểm thấp (quy tắc trong {@link Reputation}).
 * Mọi thay đổi được ghi vào bảng reputation_log để sinh viên và quản trị viên xem lại.
 */
public class ReputationService {
    private final Database db;
    private final EventBroadcaster events;

    /** Kết quả một lần thay đổi điểm. */
    public static final class Change {
        public final String username;
        public final int before;
        public final int after;
        public final LocalDate lockedUntil;
        public final boolean newlyLocked;

        Change(String username, int before, int after, LocalDate lockedUntil, boolean newlyLocked) {
            this.username = username;
            this.before = before;
            this.after = after;
            this.lockedUntil = lockedUntil;
            this.newlyLocked = newlyLocked;
        }

        public int delta() {
            return after - before;
        }

        /** "−20 điểm uy tín (còn 60)" + thông báo khóa nếu có. */
        public String describe() {
            String s = (delta() > 0 ? "+" : "−") + Math.abs(delta()) + " điểm uy tín (còn " + after + ")";
            if (newlyLocked && lockedUntil != null) {
                s += ". Tài khoản bị tạm khóa đặt chỗ đến hết ngày " + Fmt.date(lockedUntil.minusDays(1));
            }
            return s;
        }
    }

    public ReputationService(Database db, EventBroadcaster events) {
        this.db = db;
        this.events = events;
    }

    /**
     * Cộng / trừ điểm trong transaction đang mở (dùng chung kết nối {@code c} với thao tác đặt chỗ).
     * Bỏ qua tài khoản quản trị viên. Trả về null nếu điểm không đổi.
     */
    public static Change change(Connection c, String username, int delta, String reason, String bookingId,
                                LocalDate today) throws SQLException {
        Standing s = UserDao.standingForUpdate(c, username);
        if (s == null || s.role != Role.STUDENT || delta == 0) {
            return null;
        }
        int after = Math.max(0, Math.min(Reputation.MAX, s.score + delta));
        if (after == s.score) {
            return null;
        }
        LocalDate locked = s.lockedUntil;
        boolean newlyLocked = false;
        if (after < Reputation.LOCK_BELOW) {
            LocalDate until = today.plusDays(Reputation.LOCK_DAYS);
            if (delta < 0 && (locked == null || locked.isBefore(until))) {
                newlyLocked = locked == null || !today.isBefore(locked);
                locked = until; // bị trừ điểm khi đang dưới ngưỡng → gia hạn khóa
            }
        } else {
            locked = null; // đủ điểm → mở khóa
        }
        UserDao.setReputation(c, username, after, locked);
        ReputationDao.insert(c, username, after - s.score, after, reason, bookingId,
                LocalDateTime.now().withNano(0));
        return new Change(username, s.score, after, locked, newlyLocked);
    }

    // ------------------------------------------------------------------ Xem điểm

    public ReputationInfo get(String username) {
        LocalDate today = LocalDate.now();
        return db.query(c -> {
            Standing s = UserDao.standing(c, username);
            if (s == null) {
                throw new ServiceException("Không tìm thấy tài khoản " + username + ".");
            }
            ReputationInfo info = new ReputationInfo(s.username, s.fullName, s.score, s.level(today),
                    s.lockedUntil != null && today.isBefore(s.lockedUntil) ? s.lockedUntil : null);
            info.getHistory().addAll(ReputationDao.history(c, username, 200));
            return info;
        });
    }

    public List<StudentInfo> listStudents() {
        LocalDate today = LocalDate.now();
        return db.query(c -> UserDao.listStudents(c, today));
    }

    // ------------------------------------------------------------------ Quản trị viên điều chỉnh

    /**
     * @param resetToStart true = khôi phục về mức khởi đầu {@link Reputation#START} điểm và mở khóa (bỏ qua {@code delta})
     */
    public ReputationInfo adjust(UserInfo admin, String username, int delta, boolean resetToStart, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            throw new ServiceException("Vui lòng nhập lý do điều chỉnh điểm.");
        }
        if (!resetToStart && (delta == 0 || Math.abs(delta) > Reputation.MAX)) {
            throw new ServiceException("Số điểm điều chỉnh phải khác 0 và trong khoảng -100 đến +100.");
        }
        LocalDate today = LocalDate.now();
        Change ch = db.tx(c -> {
            Standing s = UserDao.standingForUpdate(c, username);
            if (s == null) {
                throw new ServiceException("Không tìm thấy tài khoản " + username + ".");
            }
            if (s.role != Role.STUDENT) {
                throw new ServiceException("Chỉ điều chỉnh điểm uy tín của sinh viên.");
            }
            if (resetToStart && s.score >= Reputation.START && s.level(today) != Reputation.Level.LOCKED) {
                throw new ServiceException(s.fullName + " đang có " + s.score + " điểm (từ " + Reputation.START
                        + " trở lên), không cần khôi phục. Dùng \"Cộng / trừ điểm\" nếu muốn thay đổi.");
            }
            int d = resetToStart ? Reputation.START - s.score : delta;
            Change result = change(c, username, d, "Quản trị viên " + admin.getUsername() + ": " + reason, null, today);
            if (result == null && resetToStart && s.lockedUntil != null) { // đủ điểm nhưng còn khóa → mở khóa
                UserDao.setReputation(c, username, s.score, null);
            }
            return result;
        });
        if (ch != null) {
            Log.info("Quản trị viên %s điều chỉnh điểm uy tín của %s: %+d (%d → %d) - %s", admin.getUsername(),
                    username, ch.delta(), ch.before, ch.after, reason);
            events.sendToUser(username, EventType.REPUTATION_CHANGED,
                    "Điểm uy tín của bạn được quản trị viên điều chỉnh " + ch.describe() + ". Lý do: " + reason);
        }
        return get(username);
    }

    // ------------------------------------------------------------------ Tác vụ nền

    /** Chạy định kỳ: tài khoản hết thời gian tạm khóa được mở lại ở mức {@link Reputation#LOCK_BELOW} điểm. */
    public void releaseExpiredLocks() {
        LocalDate today = LocalDate.now();
        List<String> due = db.query(c -> UserDao.withExpiredLock(c, today));
        if (due.isEmpty()) {
            return;
        }
        List<Change> changes = db.tx(c -> {
            List<Change> list = new ArrayList<>();
            for (String username : due) {
                Standing s = UserDao.standingForUpdate(c, username);
                if (s == null || s.lockedUntil == null || today.isBefore(s.lockedUntil)) {
                    continue;
                }
                int target = Math.max(s.score, Reputation.LOCK_BELOW);
                UserDao.setReputation(c, username, target, null);
                if (target != s.score) {
                    ReputationDao.insert(c, username, target - s.score, target,
                            "Hết thời gian tạm khóa - khôi phục lên " + Reputation.LOCK_BELOW + " điểm (mức Hạn chế)",
                            null, LocalDateTime.now().withNano(0));
                }
                list.add(new Change(username, s.score, target, null, false));
            }
            return list;
        });
        for (Change ch : changes) {
            Log.info("Mở khóa đặt chỗ cho %s (điểm uy tín %d → %d)", ch.username, ch.before, ch.after);
            events.sendToUser(ch.username, EventType.REPUTATION_CHANGED,
                    "Tài khoản của bạn đã được mở khóa đặt chỗ (điểm uy tín: " + ch.after + ").");
        }
    }
}
