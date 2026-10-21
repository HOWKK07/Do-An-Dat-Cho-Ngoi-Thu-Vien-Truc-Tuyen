package library.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Thông báo do thư viện đăng (nội dung hiển thị cho mọi người dùng). */
public class Announcement implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String title;
    private final String content;
    private final String author;
    private final LocalDateTime createdAt;

    public Announcement(String id, String title, String content, String author, LocalDateTime createdAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.author = author;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getAuthor() {
        return author;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return title;
    }
}
