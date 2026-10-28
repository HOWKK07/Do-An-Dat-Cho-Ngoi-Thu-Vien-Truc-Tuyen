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
    /** Bàn đang mở cho đặt lẻ từng chỗ (ngồi ghép). */
    private boolean shareOpen;
    /** Số chỗ đã có người đặt lẻ trên bàn này. */
    private int sharedTaken;
    /** Vị trí ghép của người xem trên bàn này (0 = đặt cả bàn / không có). */
    private int myPlace;

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

    public boolean isShareOpen() {
        return shareOpen;
    }

    public void setShareOpen(boolean shareOpen) {
        this.shareOpen = shareOpen;
    }

    public int getSharedTaken() {
        return sharedTaken;
    }

    public void setSharedTaken(int sharedTaken) {
        this.sharedTaken = sharedTaken;
    }

    public int getMyPlace() {
        return myPlace;
    }

    public void setMyPlace(int myPlace) {
        this.myPlace = myPlace;
    }

    /** Số chỗ còn trống để đặt lẻ (chỉ có ý nghĩa khi bàn đang mở ngồi ghép). */
    public int getFreePlaces() {
        return Math.max(0, seat.getCapacity() - sharedTaken);
    }
}
