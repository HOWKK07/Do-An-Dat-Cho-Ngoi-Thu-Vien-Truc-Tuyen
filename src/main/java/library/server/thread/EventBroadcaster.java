package library.server.thread;

import library.common.EventType;
import library.common.Message;
import library.server.ClientHandler;
import library.server.session.Session;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Đẩy sự kiện thời gian thực tới các client đang đăng nhập.
 * Việc gửi chạy trên luồng "event-broadcaster" riêng, nên luồng đang xử lý yêu cầu đặt chỗ
 * trả lời ngay cho người đặt mà không phải chờ gửi xong cho hàng chục client khác.
 */
public class EventBroadcaster {
    private final Set<ClientHandler> clients;
    private final Executor executor;

    public EventBroadcaster(Set<ClientHandler> clients, Executor executor) {
        this.clients = clients;
        this.executor = executor;
    }

    public void broadcast(Message event) {
        submit(() -> {
            for (ClientHandler c : clients) {
                if (c.getSession() != null) {
                    c.send(event);
                }
            }
        });
    }

    public void sendToUser(String username, EventType type, String text) {
        sendToUser(username, Message.event(type, text));
    }

    /** Gửi một sự kiện (có thể kèm dữ liệu, VD "bookingId") tới mọi phiên của một người dùng. */
    public void sendToUser(String username, Message event) {
        submit(() -> {
            for (ClientHandler c : clients) {
                Session s = c.getSession();
                if (s != null && s.getUser().getUsername().equals(username)) {
                    c.send(event);
                }
            }
        });
    }

    public void seatsChanged(LocalDate date, int slot) {
        broadcast(Message.event(EventType.SEATS_CHANGED, null).put("date", date).put("slot", slot));
    }

    public void zonesChanged() {
        broadcast(Message.event(EventType.ZONES_CHANGED, null));
    }

    public void announcementsChanged(String text) {
        broadcast(Message.event(EventType.ANNOUNCEMENTS_CHANGED, text));
    }

    private void submit(Runnable r) {
        try {
            executor.execute(r);
        } catch (RejectedExecutionException ignored) {
            // server đang tắt
        }
    }
}
