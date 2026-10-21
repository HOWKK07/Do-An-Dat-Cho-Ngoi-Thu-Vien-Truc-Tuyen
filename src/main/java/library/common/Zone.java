package library.common;

import java.io.Serializable;

/** Khu vực trong thư viện (VD: Khu đọc yên tĩnh - tầng 1). */
public class Zone implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private int floor;
    private String description;

    public Zone() {
    }

    public Zone(String id, String name, int floor, String description) {
        this.id = id;
        this.name = name;
        this.floor = floor;
        this.description = description;
    }

    public Zone copy() {
        return new Zone(id, name, floor, description);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getFloor() {
        return floor;
    }

    public void setFloor(int floor) {
        this.floor = floor;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDisplayName() {
        return "Khu " + id + " - " + name;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
