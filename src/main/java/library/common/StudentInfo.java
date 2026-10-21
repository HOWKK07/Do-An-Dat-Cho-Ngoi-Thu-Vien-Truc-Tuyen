package library.common;

import java.io.Serializable;
import java.time.LocalDate;

/** Thông tin sinh viên cho quản trị viên: điểm uy tín, số lần không đến. */
public class StudentInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String fullName;
    private final String studentId;
    private final int score;
    private final Reputation.Level level;
    private final LocalDate lockedUntil;
    private final int noShows;
    private final int totalBookings;

    public StudentInfo(String username, String fullName, String studentId, int score, Reputation.Level level,
                       LocalDate lockedUntil, int noShows, int totalBookings) {
        this.username = username;
        this.fullName = fullName;
        this.studentId = studentId;
        this.score = score;
        this.level = level;
        this.lockedUntil = lockedUntil;
        this.noShows = noShows;
        this.totalBookings = totalBookings;
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

    public int getScore() {
        return score;
    }

    public Reputation.Level getLevel() {
        return level;
    }

    public LocalDate getLockedUntil() {
        return lockedUntil;
    }

    public int getNoShows() {
        return noShows;
    }

    public int getTotalBookings() {
        return totalBookings;
    }
}
