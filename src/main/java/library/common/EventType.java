package library.common;

/** Các sự kiện server chủ động đẩy xuống client (push). */
public enum EventType {
    /** Trạng thái chỗ ngồi của một ngày/ca thay đổi (có người đặt, hủy, check-in...). */
    SEATS_CHANGED,
    /** Danh sách khu vực / chỗ ngồi thay đổi (quản trị viên chỉnh sửa). */
    ZONES_CHANGED,
    /** Có thông báo mới hoặc thông báo bị xóa. */
    ANNOUNCEMENTS_CHANGED,
    /** Lượt đặt của chính người dùng bị thay đổi (bị hủy, quá hạn...). */
    BOOKING_UPDATED,
    /** Điểm uy tín của người dùng thay đổi (quản trị viên điều chỉnh, hết hạn khóa...). */
    REPUTATION_CHANGED,
    /** Bị buộc đăng xuất (đăng nhập ở máy khác / quản trị viên ngắt phiên). */
    FORCE_LOGOUT,
    /** Phiên hết hạn do không hoạt động. */
    SESSION_EXPIRED,
    /** Server sắp tắt. */
    SERVER_SHUTDOWN,
    /** Server đã đầy, từ chối kết nối mới. */
    SERVER_BUSY
}
