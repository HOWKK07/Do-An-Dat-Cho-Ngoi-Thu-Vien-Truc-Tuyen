package library.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Định dạng ngày giờ theo kiểu Việt Nam. */
public final class Fmt {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private Fmt() {
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    public static String dateTime(LocalDateTime dt) {
        return dt == null ? "" : dt.format(DATE_TIME);
    }

    public static String time(LocalTime t) {
        return t == null ? "" : t.format(TIME);
    }

    public static String time(LocalDateTime t) {
        return t == null ? "" : t.format(TIME);
    }

    public static String dayOfWeek(LocalDate d) {
        switch (d.getDayOfWeek()) {
            case MONDAY:
                return "Thứ 2";
            case TUESDAY:
                return "Thứ 3";
            case WEDNESDAY:
                return "Thứ 4";
            case THURSDAY:
                return "Thứ 5";
            case FRIDAY:
                return "Thứ 6";
            case SATURDAY:
                return "Thứ 7";
            default:
                return "Chủ nhật";
        }
    }

    /** "Thứ 4, 21/10/2026" */
    public static String dayLabel(LocalDate d) {
        return d == null ? "" : dayOfWeek(d) + ", " + date(d);
    }

    /** "Hôm nay - Thứ 4, 21/10/2026" */
    public static String relativeDayLabel(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) {
            return "Hôm nay - " + dayLabel(d);
        }
        if (d.equals(today.plusDays(1))) {
            return "Ngày mai - " + dayLabel(d);
        }
        return dayLabel(d);
    }

    public static String duration(long millis) {
        long s = millis / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return h + " giờ " + m + " phút";
        }
        if (m > 0) {
            return m + " phút " + sec + " giây";
        }
        return sec + " giây";
    }
}
