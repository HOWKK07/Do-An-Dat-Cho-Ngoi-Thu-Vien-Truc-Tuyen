package library.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Một lần cộng / trừ điểm uy tín. */
public class ReputationEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int delta;
    private final int scoreAfter;
    private final String reason;
    private final String bookingId;
    private final LocalDateTime createdAt;

    public ReputationEntry(int delta, int scoreAfter, String reason, String bookingId, LocalDateTime createdAt) {
        this.delta = delta;
        this.scoreAfter = scoreAfter;
        this.reason = reason;
        this.bookingId = bookingId;
        this.createdAt = createdAt;
    }

    public int getDelta() {
        return delta;
    }

    public String getDeltaLabel() {
        return delta > 0 ? "+" + delta : String.valueOf(delta);
    }

    public int getScoreAfter() {
        return scoreAfter;
    }

    public String getReason() {
        return reason;
    }

    public String getBookingId() {
        return bookingId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
