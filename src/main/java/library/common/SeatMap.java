package library.common;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;

/** Sơ đồ chỗ ngồi của toàn thư viện trong một ngày + một ca học. */
public class SeatMap implements Serializable {
    private static final long serialVersionUID = 1L;

    private final LocalDate date;
    private final int slot;
    private final ArrayList<Zone> zones = new ArrayList<>();
    private final ArrayList<SeatView> seats = new ArrayList<>();
    /** Ca này còn cho phép đặt chỗ hay không. */
    private boolean open;
    private String closedReason;
    /** Số lượt người xem đã đặt trong ngày này. */
    private int myBookingsThatDay;
    /** Điểm uy tín của người xem (sinh viên) và quyền đặt chỗ tương ứng. */
    private int reputation = Reputation.START;
    private Reputation.Level reputationLevel = Reputation.Level.GOOD;
    private int maxBookingsPerDay = Protocol.MAX_BOOKINGS_PER_DAY;

    public SeatMap(LocalDate date, int slot) {
        this.date = date;
        this.slot = slot;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getSlot() {
        return slot;
    }

    public ArrayList<Zone> getZones() {
        return zones;
    }

    public ArrayList<SeatView> getSeats() {
        return seats;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public String getClosedReason() {
        return closedReason;
    }

    public void setClosedReason(String closedReason) {
        this.closedReason = closedReason;
    }

    public int getMyBookingsThatDay() {
        return myBookingsThatDay;
    }

    public void setMyBookingsThatDay(int myBookingsThatDay) {
        this.myBookingsThatDay = myBookingsThatDay;
    }

    public int getReputation() {
        return reputation;
    }

    public Reputation.Level getReputationLevel() {
        return reputationLevel;
    }

    public int getMaxBookingsPerDay() {
        return maxBookingsPerDay;
    }

    public void setReputation(int reputation, Reputation.Level level) {
        this.reputation = reputation;
        this.reputationLevel = level;
        this.maxBookingsPerDay = level.getMaxPerDay();
    }
}
