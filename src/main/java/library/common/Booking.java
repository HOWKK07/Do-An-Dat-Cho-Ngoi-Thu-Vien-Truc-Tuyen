package library.common;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Một lượt đặt chỗ: người dùng X đặt chỗ Y vào ngày D, ca S. */
public class Booking implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String username;
    private String fullName;
    private String studentId;
    private String seatId;
    private String zoneId;
    private String zoneName;
    private LocalDate date;
    private int slot;
    private BookingStatus status = BookingStatus.ACTIVE;
    private LocalDateTime createdAt;
    private LocalDateTime checkedInAt;
    private LocalDateTime finishedAt;
    private String note;
    /** Lượt đặt do quản trị viên tạo hộ sinh viên không có tài khoản (đặt trực tiếp tại quầy). */
    private boolean walkIn;
    private String phone;
    /** Ghi chú thông tin sinh viên do quản trị viên nhập. */
    private String guestNote;
    /** Thông tin thay đổi điểm uy tín sau thao tác (chỉ để hiển thị, không lưu CSDL). VD: "+5 điểm uy tín (còn 100)". */
    private String reputationNote;

    public Booking copy() {
        Booking b = new Booking();
        b.id = id;
        b.username = username;
        b.fullName = fullName;
        b.studentId = studentId;
        b.seatId = seatId;
        b.zoneId = zoneId;
        b.zoneName = zoneName;
        b.date = date;
        b.slot = slot;
        b.status = status;
        b.createdAt = createdAt;
        b.checkedInAt = checkedInAt;
        b.finishedAt = finishedAt;
        b.note = note;
        b.walkIn = walkIn;
        b.phone = phone;
        b.guestNote = guestNote;
        b.reputationNote = reputationNote;
        return b;
    }

    public TimeSlot getTimeSlot() {
        return TimeSlot.of(slot);
    }

    /** Mô tả ngắn gọn: "chỗ A05, Thứ 4, 21/10/2026, Ca 2 (09:30 - 12:00)". */
    public String describe() {
        TimeSlot ts = getTimeSlot();
        return "chỗ " + seatId + ", " + Fmt.dayLabel(date) + ", " + (ts != null ? ts.getLabel() : "ca " + slot);
    }

    /** Tên người giữ chỗ để hiển thị: "Nguyễn Văn A (đặt tại quầy)". */
    public String getHolderLabel() {
        return walkIn ? fullName + " (đặt tại quầy)" : fullName;
    }

    public boolean isWalkIn() {
        return walkIn;
    }

    public void setWalkIn(boolean walkIn) {
        this.walkIn = walkIn;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getGuestNote() {
        return guestNote;
    }

    public void setGuestNote(String guestNote) {
        this.guestNote = guestNote;
    }

    public String getReputationNote() {
        return reputationNote;
    }

    public void setReputationNote(String reputationNote) {
        this.reputationNote = reputationNote;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getSeatId() {
        return seatId;
    }

    public void setSeatId(String seatId) {
        this.seatId = seatId;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public int getSlot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCheckedInAt() {
        return checkedInAt;
    }

    public void setCheckedInAt(LocalDateTime checkedInAt) {
        this.checkedInAt = checkedInAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
