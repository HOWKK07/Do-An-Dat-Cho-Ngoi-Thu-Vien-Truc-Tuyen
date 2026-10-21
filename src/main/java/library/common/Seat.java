package library.common;

import java.io.Serializable;

/** Một chỗ ngồi / bàn / phòng có thể đặt. */
public class Seat implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String zoneId;
    private SeatType type = SeatType.SINGLE;
    private int capacity = 1;
    private boolean hasOutlet;
    private boolean active = true;
    /** Ghi chú khi bảo trì. */
    private String note;

    public Seat() {
    }

    public Seat(String id, String zoneId, SeatType type, int capacity, boolean hasOutlet) {
        this.id = id;
        this.zoneId = zoneId;
        this.type = type;
        this.capacity = capacity;
        this.hasOutlet = hasOutlet;
    }

    public Seat copy() {
        Seat s = new Seat(id, zoneId, type, capacity, hasOutlet);
        s.active = active;
        s.note = note;
        return s;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public SeatType getType() {
        return type;
    }

    public void setType(SeatType type) {
        this.type = type;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public boolean isHasOutlet() {
        return hasOutlet;
    }

    public void setHasOutlet(boolean hasOutlet) {
        this.hasOutlet = hasOutlet;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
