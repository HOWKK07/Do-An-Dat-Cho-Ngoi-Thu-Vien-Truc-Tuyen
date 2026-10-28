package library.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Một người đi cùng được khai báo khi đặt cả bàn nhiều chỗ, kèm trạng thái xác nhận của người đó.
 * Người đi cùng phải bấm "Đồng ý" trong ứng dụng thì mới được tính là đủ người để check-in.
 */
public class Companion implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Trạng thái xác nhận lời mời đi cùng. */
    public enum Status {
        PENDING("Chờ xác nhận"),
        ACCEPTED("Đã đồng ý"),
        DECLINED("Đã từ chối");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public static Status parse(String s) {
            try {
                return s == null ? PENDING : valueOf(s);
            } catch (IllegalArgumentException e) {
                return PENDING;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final String username;
    private final String fullName;
    private final String studentId;
    private final Status status;
    /** Lúc được mời (null với dữ liệu từ bản cũ - khi đó tính theo giờ đặt). */
    private final LocalDateTime invitedAt;
    /** Lúc trả lời Đồng ý / Từ chối. */
    private final LocalDateTime respondedAt;
    /** Người đặt đã được nhắc (sau 15 phút chưa đủ người xác nhận) kể từ lần thay đổi này chưa. */
    private final boolean reminded;

    public Companion(String username, String fullName, String studentId, Status status) {
        this(username, fullName, studentId, status, null, null, false);
    }

    public Companion(String username, String fullName, String studentId, Status status, LocalDateTime invitedAt,
                     LocalDateTime respondedAt, boolean reminded) {
        this.username = username;
        this.fullName = fullName;
        this.studentId = studentId;
        this.status = status;
        this.invitedAt = invitedAt;
        this.respondedAt = respondedAt;
        this.reminded = reminded;
    }

    public LocalDateTime getInvitedAt() {
        return invitedAt;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public boolean isReminded() {
        return reminded;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public String getStudentId() {
        return studentId;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    public boolean isDeclined() {
        return status == Status.DECLINED;
    }

    /** "Họ tên (MSSV)". */
    public String getName() {
        return fullName + (studentId != null ? " (" + studentId + ")" : "");
    }

    /** MSSV nếu có, không thì tên đăng nhập - dùng để điền lại vào ô khai báo. */
    public String getIdent() {
        return studentId != null ? studentId : username;
    }

    /** "Nguyễn Văn A (2280600001) - đã đồng ý". */
    @Override
    public String toString() {
        return getName() + " - " + status.getLabel().toLowerCase();
    }
}
