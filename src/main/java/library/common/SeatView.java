package library.common;

import java.io.Serializable;

/** Chỗ ngồi kèm trạng thái trong một ngày/ca (dùng để vẽ sơ đồ chỗ ngồi). */
public class SeatView implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Seat seat;
    private SeatState state = SeatState.AVAILABLE;
    private String bookingId;
    private BookingStatus bookingStatus;
    /** Người đặt - chỉ gửi cho quản trị viên. */
    private String bookedBy;

    public SeatView(Seat seat) {
        this.seat = seat;
    }

    public Seat getSeat() {
        return seat;
    }

    public SeatState getState() {
        return state;
    }

    public void setState(SeatState state) {
        this.state = state;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public BookingStatus getBookingStatus() {
        return bookingStatus;
    }

    public void setBookingStatus(BookingStatus bookingStatus) {
        this.bookingStatus = bookingStatus;
    }

    public String getBookedBy() {
        return bookedBy;
    }

    public void setBookedBy(String bookedBy) {
        this.bookedBy = bookedBy;
    }
}
