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
    /** Vị trí ngồi ghép trên bàn (1, 2, ...); 0 = đặt cả bàn / chỗ ngồi đơn. */
    private int place;
    /** Người đi cùng khi đặt cả bàn nhiều chỗ, kèm trạng thái xác nhận (chỉ để hiển thị). */
    private java.util.ArrayList<Companion> companions = new java.util.ArrayList<>();
    /** Số chỗ của bàn (để tính số người tối thiểu); 0 = chưa biết. */
    private int seatCapacity;
    /** Khi xem danh sách "bàn được mời đi cùng": trạng thái xác nhận của chính người đang xem. */
    private Companion.Status myCompanionStatus;
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
        b.place = place;
        b.companions = new java.util.ArrayList<>(companions);
        b.seatCapacity = seatCapacity;
        b.myCompanionStatus = myCompanionStatus;
        b.reputationNote = reputationNote;
        return b;
    }

    public TimeSlot getTimeSlot() {
        return TimeSlot.of(slot);
    }

    /** Mô tả ngắn gọn: "chỗ A05, Thứ 4, 21/10/2026, Ca 2 (09:30 - 12:00)". */
    public String describe() {
        TimeSlot ts = getTimeSlot();
        return "chỗ " + getSeatLabel() + ", " + Fmt.dayLabel(date) + ", " + (ts != null ? ts.getLabel() : "ca " + slot);
    }

    /** Mã chỗ để hiển thị: "B03", hoặc "B03 (ghép #1)" khi đặt lẻ một chỗ trên bàn. */
    public String getSeatLabel() {
        return place > 0 ? seatId + " (ghép #" + place + ")" : seatId;
    }

    public java.util.List<Companion> getCompanions() {
        return companions;
    }

    public int getSeatCapacity() {
        return seatCapacity;
    }

    public void setSeatCapacity(int seatCapacity) {
        this.seatCapacity = seatCapacity;
    }

    public Companion.Status getMyCompanionStatus() {
        return myCompanionStatus;
    }

    public void setMyCompanionStatus(Companion.Status myCompanionStatus) {
        this.myCompanionStatus = myCompanionStatus;
    }

    /** Số người đi cùng cần có (không tính người đặt) để đủ người cho bàn này; 0 nếu không áp dụng. */
    public int getCompanionsNeeded() {
        return seatCapacity >= 2 && place == 0 ? Protocol.minPeopleForTable(seatCapacity) - 1 : 0;
    }

    public int getAcceptedCompanionCount() {
        int n = 0;
        for (Companion c : companions) {
            if (c.isAccepted()) {
                n++;
            }
        }
        return n;
    }

    /** Số người đi cùng chưa từ chối (đã đồng ý + đang chờ xác nhận). */
    public int getActiveCompanionCount() {
        int n = 0;
        for (Companion c : companions) {
            if (!c.isDeclined()) {
                n++;
            }
        }
        return n;
    }

    /** Có người từ chối nên kể cả khi mọi người còn lại đồng ý vẫn không đủ người - cần khai báo thêm. */
    public boolean isCompanionsShort() {
        return !companions.isEmpty() && getActiveCompanionCount() < getCompanionsNeeded();
    }

    /** Đủ số người đã xác nhận đi cùng để check-in (lượt đặt cũ không có danh sách thì luôn đủ). */
    public boolean isCompanionsReady() {
        return companions.isEmpty() || getAcceptedCompanionCount() >= getCompanionsNeeded();
    }

    /**
     * Giờ lượt đặt sẽ tự hủy nếu vẫn chưa đủ người xác nhận đi cùng - CỐ ĐỊNH 30 phút kể từ lúc đặt (thêm / thay người
     * hay có người từ chối cũng không lùi hạn); null nếu đã đủ người / không áp dụng.
     */
    public LocalDateTime getCompanionDeadline() {
        if (companions.isEmpty() || isCompanionsReady() || createdAt == null) {
            return null;
        }
        return createdAt.plusMinutes(Protocol.COMPANION_CONFIRM_MINUTES);
    }

    /** Lượt đặt cả bàn đang chờ check-in mà chưa đủ người xác nhận đi cùng - người đặt nên thêm / thay người hoặc hủy. */
    public boolean needsCompanionAction() {
        return status == BookingStatus.ACTIVE && getCompanionDeadline() != null;
    }

    /** "1/2 đã xác nhận: Trần B (2280600001) - đã đồng ý, Lê C (2280600002) - chờ xác nhận" hoặc "" nếu không có. */
    public String getCompanionsLabel() {
        if (companions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int need = getCompanionsNeeded();
        if (need > 0) {
            LocalDateTime deadline = status == BookingStatus.ACTIVE ? getCompanionDeadline() : null;
            if (deadline != null) {
                sb.append(isCompanionsShort() ? "THIẾU NGƯỜI" : "Chờ xác nhận").append(" (tự hủy lúc ")
                        .append(Fmt.time(deadline)).append(") - ");
            }
            sb.append(Math.min(getAcceptedCompanionCount(), need)).append('/').append(need).append(" đã xác nhận: ");
        }
        for (int i = 0; i < companions.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(companions.get(i));
        }
        return sb.toString();
    }

    /** Chỉ tên những người đi cùng (không kèm trạng thái): "Trần B (2280600001), Lê C (2280600002)". */
    public String getCompanionNames() {
        StringBuilder sb = new StringBuilder();
        for (Companion c : companions) {
            if (!c.isDeclined()) {
                sb.append(sb.length() == 0 ? "" : ", ").append(c.getName());
            }
        }
        return sb.toString();
    }

    public int getPlace() {
        return place;
    }

    public void setPlace(int place) {
        this.place = place;
    }

    public boolean isSharedPlace() {
        return place > 0;
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
