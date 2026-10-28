package library.common;

public enum SeatType {
    SINGLE("Chỗ ngồi đơn", "Đơn"),
    PAIR("Bàn đôi", "Đôi"),
    GROUP("Bàn học nhóm", "Nhóm"),
    COMPUTER("Máy tính", "PC"),
    ROOM("Phòng học riêng", "Phòng");

    private final String label;
    private final String shortLabel;

    SeatType(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public String getLabel() {
        return label;
    }

    public String getShortLabel() {
        return shortLabel;
    }

    @Override
    public String toString() {
        return label;
    }
}
