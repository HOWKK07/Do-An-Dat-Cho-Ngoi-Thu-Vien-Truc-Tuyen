package library.common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * Gói tin trao đổi giữa client và server (gửi bằng ObjectOutputStream qua TCP).
 * <ul>
 *   <li>REQUEST: client gửi lên, mang requestId tăng dần.</li>
 *   <li>RESPONSE: server trả lời, mang đúng requestId của yêu cầu để client ghép cặp.</li>
 *   <li>EVENT: server chủ động đẩy xuống (chỗ ngồi thay đổi, thông báo mới, buộc đăng xuất...).</li>
 * </ul>
 */
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Kind { REQUEST, RESPONSE, EVENT }

    private final Kind kind;
    private Command command;
    private EventType event;
    private long requestId;
    private boolean success;
    private String text;
    private final HashMap<String, Serializable> data = new HashMap<>();

    private Message(Kind kind) {
        this.kind = kind;
    }

    public static Message request(Command command) {
        Message m = new Message(Kind.REQUEST);
        m.command = command;
        return m;
    }

    public static Message ok(Message request) {
        return response(request, true, null);
    }

    public static Message ok(Message request, String text) {
        return response(request, true, text);
    }

    public static Message error(Message request, String text) {
        return response(request, false, text);
    }

    private static Message response(Message request, boolean success, String text) {
        Message m = new Message(Kind.RESPONSE);
        m.command = request.command;
        m.requestId = request.requestId;
        m.success = success;
        m.text = text;
        return m;
    }

    public static Message event(EventType type, String text) {
        Message m = new Message(Kind.EVENT);
        m.event = type;
        m.success = true;
        m.text = text;
        return m;
    }

    public Message put(String key, Serializable value) {
        data.put(key, value);
        return this;
    }

    public Message putList(String key, Collection<? extends Serializable> values) {
        data.put(key, new ArrayList<Serializable>(values));
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    public String getString(String key) {
        Object v = data.get(key);
        return v == null ? null : v.toString();
    }

    public int getInt(String key, int defaultValue) {
        Object v = data.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                // dùng giá trị mặc định
            }
        }
        return defaultValue;
    }

    public boolean getBool(String key) {
        Object v = data.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return v != null && Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key) {
        Object v = data.get(key);
        return v instanceof List ? (List<T>) v : new ArrayList<T>();
    }

    public Kind getKind() {
        return kind;
    }

    public Command getCommand() {
        return command;
    }

    public EventType getEvent() {
        return event;
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return kind + "{" + (command != null ? command : event) + ", id=" + requestId
                + ", ok=" + success + ", text=" + text + ", keys=" + data.keySet() + "}";
    }
}
