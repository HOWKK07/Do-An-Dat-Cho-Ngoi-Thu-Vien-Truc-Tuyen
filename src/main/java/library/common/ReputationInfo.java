package library.common;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;

/** Điểm uy tín hiện tại của một sinh viên kèm lịch sử thay đổi. */
public class ReputationInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String fullName;
    private final int score;
    private final Reputation.Level level;
    private final LocalDate lockedUntil;
    private final ArrayList<ReputationEntry> history = new ArrayList<>();

    public ReputationInfo(String username, String fullName, int score, Reputation.Level level, LocalDate lockedUntil) {
        this.username = username;
        this.fullName = fullName;
        this.score = score;
        this.level = level;
        this.lockedUntil = lockedUntil;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
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

    public ArrayList<ReputationEntry> getHistory() {
        return history;
    }
}
