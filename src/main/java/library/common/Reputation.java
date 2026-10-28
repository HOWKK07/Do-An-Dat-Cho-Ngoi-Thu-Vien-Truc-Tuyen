package library.common;

import java.time.LocalDate;

/**
 * Quy tắc ĐIỂM UY TÍN - hạn chế sinh viên đặt chỗ rồi không đến.
 * <ul>
 *   <li>Mỗi tài khoản sinh viên bắt đầu với 80 điểm (tối đa 100).</li>
 *   <li>Không check-in (không đến): −20; đặt CẢ bàn nhiều chỗ mà không đến: thêm −5 cho mỗi chỗ thêm, tối đa −40
 *       (bàn đôi −25, bàn 4 người −35, bàn 6 người / phòng 8 người −40).
 *       Người đi cùng đã xác nhận "Đồng ý" mà cả nhóm không đến: −10 mỗi người.
 *       Hủy sát giờ (dưới 60 phút trước ca): −5. Check-in đúng hẹn: +5.</li>
 *   <li>100: Xuất sắc - 3 ca/ngày, đặt trước 3 ngày (tối đa của thư viện).</li>
 *   <li>80-99: Tốt - 3 ca/ngày, đặt trước 2 ngày.</li>
 *   <li>50-79: Hạn chế - 1 ca/ngày, đặt trước 2 ngày.</li>
 *   <li>Dưới 50: Tạm khóa đặt chỗ 7 ngày; hết hạn tự mở lại ở mức 50 điểm.</li>
 * </ul>
 * "Đặt trước N ngày" tính cả hôm nay: 2 ngày = hôm nay và ngày mai.
 */
public final class Reputation {
    public static final int START = 80;
    public static final int MAX = 100;
    public static final int NO_SHOW_PENALTY = 20;
    /** Đặt CẢ bàn nhiều chỗ mà không đến: trừ thêm cho mỗi chỗ bị bỏ trống... */
    public static final int NO_SHOW_EXTRA_PER_SEAT = 5;
    /** ...nhưng một lần không đến bị trừ tối đa từng này điểm. */
    public static final int NO_SHOW_MAX_PENALTY = 40;
    /** Người đi cùng đã bấm "Đồng ý" nhưng cả nhóm không đến (không check-in) - mỗi người bị trừ từng này điểm. */
    public static final int COMPANION_NO_SHOW_PENALTY = 10;
    public static final int LATE_CANCEL_PENALTY = 5;
    public static final int LATE_CANCEL_MINUTES = 60;
    public static final int CHECK_IN_REWARD = 5;
    /** Đạt mức này (100) mới được đặt trước 3 ngày. */
    public static final int EXCELLENT_MIN = MAX;
    public static final int GOOD_MIN = 80;
    public static final int GOOD_DAYS_AHEAD = 2;
    public static final int LOCK_BELOW = 50;
    public static final int LOCK_DAYS = 7;
    public static final int RESTRICTED_MAX_PER_DAY = 1;
    public static final int RESTRICTED_DAYS_AHEAD = 2;

    /** Mức uy tín và quyền đặt chỗ tương ứng. */
    public enum Level {
        EXCELLENT("Xuất sắc", Protocol.MAX_BOOKINGS_PER_DAY, Protocol.BOOKING_DAYS_AHEAD),
        GOOD("Tốt", Protocol.MAX_BOOKINGS_PER_DAY, GOOD_DAYS_AHEAD),
        RESTRICTED("Hạn chế", RESTRICTED_MAX_PER_DAY, RESTRICTED_DAYS_AHEAD),
        LOCKED("Tạm khóa", 0, 0);

        private final String label;
        private final int maxPerDay;
        private final int daysAhead;

        Level(String label, int maxPerDay, int daysAhead) {
            this.label = label;
            this.maxPerDay = maxPerDay;
            this.daysAhead = daysAhead;
        }

        public String getLabel() {
            return label;
        }

        public int getMaxPerDay() {
            return maxPerDay;
        }

        public int getDaysAhead() {
            return daysAhead;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private Reputation() {
    }

    public static Level levelOf(int score, LocalDate lockedUntil, LocalDate today) {
        if (lockedUntil != null && today.isBefore(lockedUntil)) {
            return Level.LOCKED;
        }
        if (score >= EXCELLENT_MIN) {
            return Level.EXCELLENT;
        }
        if (score >= GOOD_MIN) {
            return Level.GOOD;
        }
        return score >= LOCK_BELOW ? Level.RESTRICTED : Level.LOCKED;
    }

    /**
     * Số điểm bị trừ khi đặt mà không đến: chỗ đơn hoặc 1 chỗ ngồi ghép → 20; đặt cả bàn {@code capacity} người
     * → 20 + 5 × (capacity − 1), tối đa 40 - giữ cả bàn rồi bỏ trống làm nhiều người khác mất chỗ hơn.
     */
    public static int noShowPenalty(int capacity, boolean wholeTable) {
        if (!wholeTable || capacity <= 1) {
            return NO_SHOW_PENALTY;
        }
        return Math.min(NO_SHOW_MAX_PENALTY, NO_SHOW_PENALTY + NO_SHOW_EXTRA_PER_SEAT * (capacity - 1));
    }

    /** Gợi ý để lên mức tiếp theo (null nếu đã ở mức cao nhất). */
    public static String nextLevelHint(Level level) {
        switch (level) {
            case GOOD:
                return "Check-in đúng hẹn (+" + CHECK_IN_REWARD + ") để đạt " + EXCELLENT_MIN
                        + " điểm và được đặt trước " + Level.EXCELLENT.daysAhead + " ngày.";
            case RESTRICTED:
                return "Check-in đúng hẹn (+" + CHECK_IN_REWARD + ") để đạt " + GOOD_MIN
                        + " điểm và lấy lại quyền đặt " + Level.GOOD.maxPerDay + " ca/ngày.";
            default:
                return null;
        }
    }

    /** Mô tả quy tắc để hiển thị cho người dùng. */
    public static String rulesHtml() {
        return "<html><b>Quy tắc điểm uy tín</b> (tài khoản mới " + START + " điểm, tối đa " + MAX + ")<br>"
                + "• Đặt chỗ nhưng không đến / không check-in: <b>−" + NO_SHOW_PENALTY + "</b><br>"
                + "&nbsp;&nbsp;&nbsp;Đặt cả bàn nhiều chỗ: thêm <b>−" + NO_SHOW_EXTRA_PER_SEAT + "</b>/chỗ, tối đa <b>−"
                + NO_SHOW_MAX_PENALTY + "</b> (bàn đôi −" + noShowPenalty(2, true) + ", bàn 4 người −"
                + noShowPenalty(4, true) + ")<br>"
                + "• Đã xác nhận đi cùng một bàn mà cả nhóm không đến: <b>−" + COMPANION_NO_SHOW_PENALTY
                + "</b> mỗi người<br>"
                + "• Hủy lượt đặt dưới " + LATE_CANCEL_MINUTES + " phút trước giờ bắt đầu ca: <b>−" + LATE_CANCEL_PENALTY + "</b><br>"
                + "• Check-in đúng hẹn: <b>+" + CHECK_IN_REWARD + "</b><br><br>"
                + "<b>" + EXCELLENT_MIN + " (Xuất sắc):</b> tối đa " + Level.EXCELLENT.maxPerDay + " ca/ngày, đặt trước "
                + Level.EXCELLENT.daysAhead + " ngày<br>"
                + "<b>" + GOOD_MIN + "–" + (EXCELLENT_MIN - 1) + " (Tốt):</b> tối đa " + Level.GOOD.maxPerDay
                + " ca/ngày, đặt trước " + Level.GOOD.daysAhead + " ngày<br>"
                + "<b>" + LOCK_BELOW + "–" + (GOOD_MIN - 1) + " (Hạn chế):</b> tối đa " + RESTRICTED_MAX_PER_DAY
                + " ca/ngày, đặt trước " + RESTRICTED_DAYS_AHEAD + " ngày<br>"
                + "<b>Dưới " + LOCK_BELOW + " (Tạm khóa):</b> không được đặt chỗ trong " + LOCK_DAYS
                + " ngày, sau đó tự mở lại ở mức " + LOCK_BELOW + " điểm<br>"
                + "<i>Số ngày đặt trước tính cả hôm nay; thư viện cho đặt trước tối đa "
                + Protocol.BOOKING_DAYS_AHEAD + " ngày.</i></html>";
    }
}
