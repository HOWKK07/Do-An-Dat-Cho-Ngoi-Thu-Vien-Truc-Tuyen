package library.server.service;

import library.common.Announcement;
import library.common.Booking;
import library.common.Seat;
import library.common.SeatType;
import library.common.UserInfo;
import library.common.Zone;
import library.server.Log;
import library.server.db.AnnouncementDao;
import library.server.db.Database;
import library.server.db.SeatDao;
import library.server.db.Sql;
import library.server.db.ZoneDao;
import library.server.thread.EventBroadcaster;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quản lý NỘI DUNG của thư viện: khu vực, chỗ ngồi (thêm/sửa/xóa/bảo trì) và bảng thông báo.
 * Dữ liệu nằm trong các bảng zones, seats, announcements của MySQL.
 * Mọi thay đổi được đẩy ngay tới các client đang trực tuyến qua {@link EventBroadcaster}.
 */
public class ContentService {
    private static final Pattern ZONE_ID = Pattern.compile("[A-Z][A-Z0-9]{0,3}");
    private static final Pattern SEAT_ID = Pattern.compile("[A-Z0-9-]{2,10}");

    private final Database db;
    private final EventBroadcaster events;
    private final BookingService bookings;

    public ContentService(Database db, EventBroadcaster events, BookingService bookings) {
        this.db = db;
        this.events = events;
        this.bookings = bookings;
    }

    // ------------------------------------------------------------------ Khu vực

    public List<Zone> listZones() {
        return db.query(ZoneDao::list);
    }

    public Zone saveZone(UserInfo actor, Zone input, boolean isNew) {
        if (input == null) {
            throw new ServiceException("Thiếu thông tin khu vực.");
        }
        String id = upper(input.getId());
        String name = trim(input.getName());
        String desc = trim(input.getDescription());
        if (!ZONE_ID.matcher(id).matches()) {
            throw new ServiceException("Mã khu vực gồm 1-4 ký tự in hoa/số và bắt đầu bằng chữ cái (VD: A, B2).");
        }
        if (name.isEmpty() || name.length() > 60) {
            throw new ServiceException("Tên khu vực không được trống và tối đa 60 ký tự.");
        }
        if (input.getFloor() < 0 || input.getFloor() > 50) {
            throw new ServiceException("Tầng không hợp lệ.");
        }
        if (desc.length() > 200) {
            throw new ServiceException("Mô tả tối đa 200 ký tự.");
        }
        Zone z = new Zone(id, name, input.getFloor(), desc);
        db.tx(c -> {
            if (isNew) {
                if (ZoneDao.find(c, id) != null) {
                    throw new ServiceException("Mã khu vực " + id + " đã tồn tại.");
                }
                try {
                    ZoneDao.insert(c, z);
                } catch (SQLException e) {
                    if (Sql.isDuplicate(e, "PRIMARY")) {
                        throw new ServiceException("Mã khu vực " + id + " đã tồn tại.");
                    }
                    throw e;
                }
            } else if (ZoneDao.update(c, z) == 0) {
                throw new ServiceException("Không tìm thấy khu vực " + id + ".");
            }
            return null;
        });
        Log.info("%s %s khu vực %s", actor.getUsername(), isNew ? "thêm" : "cập nhật", z.getDisplayName());
        events.zonesChanged();
        return z;
    }

    public int deleteZone(UserInfo actor, String rawId) {
        String id = upper(rawId);
        List<Booking> cancelled = db.tx(c -> {
            Zone z = ZoneDao.find(c, id);
            if (z == null) {
                throw new ServiceException("Không tìm thấy khu vực " + id + ".");
            }
            List<Booking> list = BookingService.cancelLiveBookingsForSeats(c, SeatDao.idsInZone(c, id),
                    z.getDisplayName() + " đã ngừng phục vụ");
            ZoneDao.delete(c, id); // chỗ ngồi trong khu bị xóa theo (ON DELETE CASCADE)
            return list;
        });
        Log.info("%s xóa khu vực %s (hủy %d lượt đặt)", actor.getUsername(), id, cancelled.size());
        bookings.notifyCancelled(cancelled);
        events.zonesChanged();
        return cancelled.size();
    }

    // ------------------------------------------------------------------ Chỗ ngồi

    public List<Seat> listSeats() {
        return db.query(SeatDao::list);
    }

    public Seat saveSeat(UserInfo actor, Seat input, boolean isNew) {
        if (input == null) {
            throw new ServiceException("Thiếu thông tin chỗ ngồi.");
        }
        String id = upper(input.getId());
        String zoneId = upper(input.getZoneId());
        validateSeat(id, input.getType(), input.getCapacity());
        Seat s = new Seat(id, zoneId, input.getType(), input.getCapacity(), input.isHasOutlet());
        Seat saved = db.tx(c -> {
            if (ZoneDao.find(c, zoneId) == null) {
                throw new ServiceException("Khu vực " + zoneId + " không tồn tại.");
            }
            Seat existing = SeatDao.find(c, id);
            if (isNew) {
                if (existing != null) {
                    throw new ServiceException("Mã chỗ " + id + " đã tồn tại.");
                }
                SeatDao.insert(c, s);
                return s;
            }
            if (existing == null) {
                throw new ServiceException("Không tìm thấy chỗ " + id + ".");
            }
            SeatDao.update(c, s); // trạng thái bảo trì giữ nguyên, đổi bằng chức năng riêng
            s.setActive(existing.isActive());
            s.setNote(existing.getNote());
            return s;
        });
        Log.info("%s %s chỗ %s (khu %s)", actor.getUsername(), isNew ? "thêm" : "cập nhật", id, zoneId);
        events.zonesChanged();
        return saved;
    }

    /** Thêm nhanh nhiều chỗ: tiền tố + số thứ tự nối tiếp (VD: A21, A22, ...). */
    public List<String> addSeats(UserInfo actor, String rawZoneId, String rawPrefix, int count,
                                 SeatType type, int capacity, boolean outlet) {
        String zoneId = upper(rawZoneId);
        String prefix = upper(rawPrefix).isEmpty() ? zoneId : upper(rawPrefix);
        if (count < 1 || count > 50) {
            throw new ServiceException("Số lượng chỗ cần thêm phải từ 1 đến 50.");
        }
        if (!prefix.matches("[A-Z0-9-]{1,6}")) {
            throw new ServiceException("Tiền tố mã chỗ không hợp lệ.");
        }
        validateSeat(prefix + "01", type, capacity);
        List<String> ids = db.tx(c -> {
            if (ZoneDao.find(c, zoneId) == null) {
                throw new ServiceException("Khu vực " + zoneId + " không tồn tại.");
            }
            Pattern p = Pattern.compile(Pattern.quote(prefix) + "(\\d+)");
            int max = 0;
            for (String existing : SeatDao.idsWithPrefix(c, prefix)) {
                Matcher m = p.matcher(existing);
                if (m.matches()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
            List<String> added = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                String id = String.format("%s%02d", prefix, max + i);
                SeatDao.insert(c, new Seat(id, zoneId, type, capacity, outlet));
                added.add(id);
            }
            return added;
        });
        Log.info("%s thêm %d chỗ vào khu %s: %s", actor.getUsername(), ids.size(), zoneId, ids);
        events.zonesChanged();
        return ids;
    }

    public int deleteSeat(UserInfo actor, String rawId) {
        String id = upper(rawId);
        List<Booking> cancelled = db.tx(c -> {
            if (SeatDao.find(c, id) == null) {
                throw new ServiceException("Không tìm thấy chỗ " + id + ".");
            }
            List<Booking> list = BookingService.cancelLiveBookingsForSeats(c, Collections.singleton(id),
                    "Chỗ " + id + " đã bị gỡ bỏ");
            SeatDao.delete(c, id);
            return list;
        });
        Log.info("%s xóa chỗ %s (hủy %d lượt đặt)", actor.getUsername(), id, cancelled.size());
        bookings.notifyCancelled(cancelled);
        events.zonesChanged();
        return cancelled.size();
    }

    /** Bật/tắt bảo trì. Khi tắt, mọi lượt đặt còn hiệu lực của chỗ đó bị hủy và người dùng được báo. */
    public int setSeatActive(UserInfo actor, String rawId, boolean active, String reason) {
        String id = upper(rawId);
        String note = active ? null : (trim(reason).isEmpty() ? "Bảo trì" : trim(reason));
        List<Booking> cancelled = db.tx(c -> {
            if (SeatDao.setActive(c, id, active, note) == 0) {
                throw new ServiceException("Không tìm thấy chỗ " + id + ".");
            }
            if (active) {
                return new ArrayList<Booking>();
            }
            return BookingService.cancelLiveBookingsForSeats(c, Collections.singleton(id),
                    "Chỗ " + id + " tạm ngừng phục vụ (" + note + ")");
        });
        Log.info("%s %s chỗ %s", actor.getUsername(), active ? "mở lại" : "đưa vào bảo trì", id);
        bookings.notifyCancelled(cancelled);
        events.zonesChanged();
        return cancelled.size();
    }

    // ------------------------------------------------------------------ Thông báo

    public List<Announcement> listAnnouncements() {
        return db.query(AnnouncementDao::list);
    }

    public Announcement postAnnouncement(UserInfo actor, String rawTitle, String rawContent) {
        String title = trim(rawTitle);
        String content = rawContent == null ? "" : rawContent.trim();
        if (title.isEmpty() || title.length() > 120) {
            throw new ServiceException("Tiêu đề không được trống và tối đa 120 ký tự.");
        }
        if (content.isEmpty() || content.length() > 5000) {
            throw new ServiceException("Nội dung không được trống và tối đa 5000 ký tự.");
        }
        Announcement a = db.tx(c -> AnnouncementDao.insert(c, title, content, actor.getFullName()));
        Log.info("%s đăng thông báo %s: %s", actor.getUsername(), a.getId(), title);
        events.announcementsChanged("Thông báo mới: " + title);
        return a;
    }

    public void deleteAnnouncement(UserInfo actor, String id) {
        int removed = db.tx(c -> AnnouncementDao.delete(c, id == null ? "" : id));
        if (removed == 0) {
            throw new ServiceException("Không tìm thấy thông báo " + id + ".");
        }
        Log.info("%s xóa thông báo %s", actor.getUsername(), id);
        events.announcementsChanged(null);
    }

    // ------------------------------------------------------------------ Tiện ích

    private static void validateSeat(String id, SeatType type, int capacity) {
        if (!SEAT_ID.matcher(id).matches()) {
            throw new ServiceException("Mã chỗ gồm 2-10 ký tự in hoa, số hoặc dấu gạch ngang (VD: A01).");
        }
        if (type == null) {
            throw new ServiceException("Vui lòng chọn loại chỗ.");
        }
        if (capacity < 1 || capacity > 30) {
            throw new ServiceException("Sức chứa phải từ 1 đến 30 người.");
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String upper(String s) {
        return trim(s).toUpperCase();
    }
}
