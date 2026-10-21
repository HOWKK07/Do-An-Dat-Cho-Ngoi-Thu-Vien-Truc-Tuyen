package library.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Các ca học cố định trong ngày của thư viện. */
public final class TimeSlot {

    public static final List<TimeSlot> ALL = Collections.unmodifiableList(Arrays.asList(
            new TimeSlot(1, LocalTime.of(7, 0), LocalTime.of(9, 30)),
            new TimeSlot(2, LocalTime.of(9, 30), LocalTime.of(12, 0)),
            new TimeSlot(3, LocalTime.of(13, 0), LocalTime.of(15, 30)),
            new TimeSlot(4, LocalTime.of(15, 30), LocalTime.of(18, 0)),
            new TimeSlot(5, LocalTime.of(18, 0), LocalTime.of(21, 0))));

    private final int index;
    private final LocalTime start;
    private final LocalTime end;

    private TimeSlot(int index, LocalTime start, LocalTime end) {
        this.index = index;
        this.start = start;
        this.end = end;
    }

    public static TimeSlot of(int index) {
        for (TimeSlot s : ALL) {
            if (s.index == index) {
                return s;
            }
        }
        return null;
    }

    /** Ca đầu tiên trong ngày còn đặt được tại thời điểm {@code now}; null nếu đã hết ca. */
    public static TimeSlot firstBookable(LocalTime now) {
        for (TimeSlot s : ALL) {
            if (now.isBefore(s.start.plusMinutes(Protocol.CHECKIN_LATE_MINUTES))) {
                return s;
            }
        }
        return null;
    }

    /** Ca đầu tiên trong ngày chưa kết thúc tại thời điểm {@code now}; null nếu đã hết ca. */
    public static TimeSlot firstNotEnded(LocalTime now) {
        for (TimeSlot s : ALL) {
            if (now.isBefore(s.end)) {
                return s;
            }
        }
        return null;
    }

    public int getIndex() {
        return index;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }

    public LocalDateTime startAt(LocalDate date) {
        return date.atTime(start);
    }

    public LocalDateTime endAt(LocalDate date) {
        return date.atTime(end);
    }

    /** Giờ bắt đầu được check-in: 15 phút trước giờ bắt đầu ca. */
    public LocalDateTime checkInOpensAt(LocalDate date) {
        return startAt(date).minusMinutes(Protocol.CHECKIN_EARLY_MINUTES);
    }

    /** Đã đến ca (đã tới giờ check-in) và ca chưa kết thúc. */
    public boolean isCheckInStarted(LocalDate date, LocalDateTime now) {
        return !now.isBefore(checkInOpensAt(date)) && now.isBefore(endAt(date));
    }

    public String getRange() {
        return Fmt.time(start) + " - " + Fmt.time(end);
    }

    public String getLabel() {
        return "Ca " + index + " (" + getRange() + ")";
    }

    @Override
    public String toString() {
        return getLabel();
    }
}
