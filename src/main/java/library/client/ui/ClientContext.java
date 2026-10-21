package library.client.ui;

import library.client.net.ServerConnection;
import library.common.Command;
import library.common.Message;
import library.common.Seat;
import library.common.UserInfo;
import library.common.Zone;

import javax.swing.JFrame;
import javax.swing.Timer;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Trạng thái dùng chung của phiên làm việc phía client (kết nối, người dùng, danh mục khu vực). */
public class ClientContext {
    private final ServerConnection conn;
    private final UserInfo user;
    private final JFrame frame;
    private final List<Runnable> zoneListeners = new ArrayList<>();
    private final List<Timer> timers = new ArrayList<>();
    private List<Zone> zones = Collections.emptyList();
    private List<Seat> seats = Collections.emptyList();

    public ClientContext(ServerConnection conn, UserInfo user, JFrame frame) {
        this.conn = conn;
        this.user = user;
        this.frame = frame;
    }

    /** Tải lại danh sách khu vực + chỗ ngồi, sau đó báo cho các màn hình liên quan. */
    public void reloadZones() {
        UiKit.callQuiet(conn, Message.request(Command.GET_ZONES), r -> {
            zones = r.getList("zones");
            seats = r.getList("seats");
            for (Runnable l : zoneListeners) {
                l.run();
            }
        }, null);
    }

    public void addZoneListener(Runnable l) {
        zoneListeners.add(l);
    }

    public Zone findZone(String id) {
        for (Zone z : zones) {
            if (z.getId().equals(id)) {
                return z;
            }
        }
        return null;
    }

    /** Tạo Timer gắn với phiên - tự dừng khi đăng xuất. */
    public Timer timer(int delayMs, ActionListener l) {
        Timer t = new Timer(delayMs, l);
        timers.add(t);
        return t;
    }

    public void dispose() {
        for (Timer t : timers) {
            t.stop();
        }
    }

    public ServerConnection conn() {
        return conn;
    }

    public UserInfo user() {
        return user;
    }

    public boolean isAdmin() {
        return user.isAdmin();
    }

    public JFrame frame() {
        return frame;
    }

    public List<Zone> zones() {
        return zones;
    }

    public List<Seat> seats() {
        return seats;
    }
}
