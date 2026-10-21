package library.common;

public enum BookingStatus {
    ACTIVE("Đã đặt"),
    CHECKED_IN("Đang sử dụng"),
    COMPLETED("Hoàn thành"),
    CANCELLED("Đã hủy"),
    EXPIRED("Quá hạn (không đến)");

    private final String label;

    BookingStatus(String label) {
        this.label = label;
    }

    /** Lượt đặt còn giữ chỗ (chưa kết thúc). */
    public boolean isLive() {
        return this == ACTIVE || this == CHECKED_IN;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
