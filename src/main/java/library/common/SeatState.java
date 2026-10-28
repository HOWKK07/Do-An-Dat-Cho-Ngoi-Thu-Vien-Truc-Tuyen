package library.common;

/** Trạng thái của một chỗ ngồi trong một ca cụ thể, nhìn từ phía người xem. */
public enum SeatState {
    AVAILABLE("Còn trống"),
    BOOKED("Đã có người đặt"),
    /** Bàn đang có người ngồi ghép và còn chỗ trống để đặt lẻ. */
    PARTIAL("Còn chỗ ngồi ghép"),
    MINE("Chỗ của bạn"),
    MAINTENANCE("Đang bảo trì");

    private final String label;

    SeatState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
