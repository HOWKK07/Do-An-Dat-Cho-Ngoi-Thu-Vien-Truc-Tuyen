package library.common;

/** Trạng thái của một chỗ ngồi trong một ca cụ thể, nhìn từ phía người xem. */
public enum SeatState {
    AVAILABLE("Còn trống"),
    BOOKED("Đã có người đặt"),
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
