package library.common;

/**
 * Các lệnh client gửi lên server.
 * Lệnh có tiền tố ADMIN_ chỉ dành cho quản trị viên.
 */
public enum Command {
    // ----- Không cần đăng nhập -----
    PING, LOGIN, REGISTER, RESUME_SESSION,

    // ----- Người dùng đã đăng nhập -----
    LOGOUT,
    GET_ZONES,
    GET_SEAT_MAP,
    BOOK_SEAT,
    CANCEL_BOOKING,
    CHECK_IN,
    CHECK_OUT,
    GET_MY_BOOKINGS,
    GET_ANNOUNCEMENTS,
    GET_MY_REPUTATION,

    // ----- Quản trị viên: nội dung, chỗ ngồi -----
    ADMIN_SAVE_ZONE,
    ADMIN_DELETE_ZONE,
    ADMIN_SAVE_SEAT,
    ADMIN_ADD_SEATS,
    ADMIN_DELETE_SEAT,
    ADMIN_SET_SEAT_ACTIVE,
    ADMIN_POST_ANNOUNCEMENT,
    ADMIN_DELETE_ANNOUNCEMENT,

    // ----- Quản trị viên: booking, phiên, hệ thống -----
    ADMIN_GET_BOOKINGS,
    /** Quản trị viên đặt chỗ: cho chính mình hoặc hộ sinh viên không có tài khoản (không giới hạn lượt). */
    ADMIN_BOOK_SEAT,
    ADMIN_CHECK_IN,
    ADMIN_CHECK_OUT,
    ADMIN_CANCEL_BOOKING,
    ADMIN_GET_STUDENTS,
    ADMIN_GET_REPUTATION,
    ADMIN_ADJUST_REPUTATION,
    ADMIN_GET_SESSIONS,
    ADMIN_KICK_SESSION,
    ADMIN_GET_STATS;

    public boolean requiresLogin() {
        return this != PING && this != LOGIN && this != REGISTER && this != RESUME_SESSION;
    }

    public boolean isAdminOnly() {
        return name().startsWith("ADMIN_");
    }
}
