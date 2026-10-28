package library.server;

import library.common.Booking;
import library.common.BookingStatus;
import library.common.Command;
import library.common.Message;
import library.common.Seat;
import library.common.SeatType;
import library.common.UserInfo;
import library.common.Zone;
import library.server.auth.GoogleAuthService;
import library.server.auth.GoogleIdentity;
import library.server.db.DbException;
import library.common.ReputationInfo;
import library.server.service.BookingService;
import library.server.service.ReputationService;
import library.server.service.ContentService;
import library.server.service.CompanionException;
import library.server.service.ServiceException;
import library.server.service.UserService;
import library.server.session.Session;
import library.server.session.SessionManager;

import java.time.LocalDate;
import java.util.List;

/**
 * Bộ điều phối yêu cầu: kiểm tra đăng nhập/quyền rồi gọi đúng service.
 * Được gọi trên luồng client-handler của từng client.
 */
public class RequestHandler {
    private final LibraryServer server;
    private final SessionManager sessions;
    private final UserService users;
    private final ContentService content;
    private final BookingService bookings;
    private final ReputationService reputation;
    private final GoogleAuthService google;

    public RequestHandler(LibraryServer server) {
        this.server = server;
        this.sessions = server.getSessions();
        this.users = server.getUsers();
        this.content = server.getContent();
        this.bookings = server.getBookings();
        this.reputation = server.getReputation();
        this.google = server.getGoogle();
    }

    public Message handle(ClientHandler client, Message req) {
        Command cmd = req.getCommand();
        if (cmd == null || req.getKind() != Message.Kind.REQUEST) {
            return Message.error(req, "Yêu cầu không hợp lệ.");
        }
        try {
            if (!cmd.requiresLogin()) {
                return handlePublic(client, req, cmd);
            }
            Session session = client.getSession();
            if (session == null || !sessions.isActive(session)) {
                return Message.error(req, "Bạn chưa đăng nhập hoặc phiên đăng nhập đã hết hạn.")
                        .put("sessionExpired", Boolean.TRUE);
            }
            session.touch();
            UserInfo user = session.getUser();
            if (cmd.isAdminOnly() && !user.isAdmin()) {
                Log.warn("%s cố gọi lệnh quản trị %s", user.getUsername(), cmd);
                return Message.error(req, "Bạn không có quyền thực hiện chức năng này.");
            }
            return handleAuthenticated(req, cmd, session, user);
        } catch (CompanionException e) {
            return Message.error(req, e.getMessage()).put("companionError", true);
        } catch (ServiceException e) {
            return Message.error(req, e.getMessage());
        } catch (DbException e) {
            Log.error("Lỗi CSDL khi xử lý lệnh " + cmd + ": " + e.getMessage(), e.getCause());
            return Message.error(req, "Lỗi cơ sở dữ liệu: " + e.getMessage());
        } catch (ClassCastException e) {
            return Message.error(req, "Dữ liệu gửi lên không đúng định dạng.");
        } catch (RuntimeException e) {
            Log.error("Lỗi khi xử lý lệnh " + cmd, e);
            return Message.error(req, "Lỗi máy chủ: " + e.getMessage());
        }
    }

    private Message handlePublic(ClientHandler client, Message req, Command cmd) {
        switch (cmd) {
            case PING:
                return Message.ok(req).put("serverTime", System.currentTimeMillis());

            case LOGIN: {
                UserInfo user = users.authenticate(req.getString("username"), req.getString("password"),
                        client.getRemoteIp());
                return loginOk(req, client, user, "");
            }

            // ---------------- Đăng nhập bằng Google ----------------
            case GOOGLE_CONFIG:
                return Message.ok(req).put("enabled", google.isEnabled())
                        .put("clientId", google.isEnabled() ? google.getConfig().getClientId() : null)
                        .put("authUrl", google.getConfig().getAuthUrl());

            case GOOGLE_LOGIN: {
                GoogleIdentity id = google.exchange(req.getString("code"), req.getString("codeVerifier"),
                        req.getString("redirectUri"));
                UserInfo user = users.findByGoogle(id);
                if (user != null) {
                    return loginOk(req, client, user, " bằng Google (" + id.email + ")");
                }
                // Tài khoản Google chưa liên kết → client hỏi: tạo tài khoản mới hay liên kết tài khoản đã có
                Log.info("Tài khoản Google %s chưa liên kết - chờ tạo mới / liên kết (từ %s)", id.email,
                        client.getRemoteIp());
                return Message.ok(req, "Tài khoản Google chưa liên kết")
                        .put("needLink", true)
                        .put("ticket", google.createTicket(id))
                        .put("email", id.email)
                        .put("name", id.name)
                        .put("suggestedUsername", id.suggestedUsername());
            }

            case GOOGLE_COMPLETE: {
                String ticket = req.getString("ticket");
                GoogleIdentity id = google.peekTicket(ticket);
                UserInfo user = "link".equals(req.getString("mode"))
                        ? users.linkGoogle(id, req.getString("username"), req.getString("password"), client.getRemoteIp())
                        : users.registerGoogle(id, req.getString("username"), req.getString("fullName"),
                        req.getString("studentId"));
                google.consumeTicket(ticket);
                return loginOk(req, client, user, " bằng Google (" + id.email + ")");
            }

            case REGISTER: {
                UserInfo user = users.register(req.getString("username"), req.getString("password"),
                        req.getString("fullName"), req.getString("studentId"));
                return Message.ok(req, "Đăng ký thành công! Bạn có thể đăng nhập bằng tài khoản " + user.getUsername())
                        .put("user", user);
            }

            case RESUME_SESSION: {
                Session s = sessions.resume(req.getString("token"), client);
                if (s == null) {
                    return Message.error(req, "Phiên đăng nhập không còn hiệu lực, vui lòng đăng nhập lại.");
                }
                Log.info("%s kết nối lại thành công từ %s (phiên %s)", s.getUser().getUsername(),
                        client.getRemoteIp(), s.getId());
                return Message.ok(req, "Đã khôi phục phiên").put("user", s.getUser());
            }

            default:
                return Message.error(req, "Lệnh không được hỗ trợ.");
        }
    }

    /** Tạo phiên đăng nhập và trả về thông tin cho client (dùng chung cho mật khẩu và Google). */
    private Message loginOk(Message req, ClientHandler client, UserInfo user, String how) {
        Session s = sessions.login(user, client);
        Log.info("%s đăng nhập%s từ %s (phiên %s)", user.getUsername(), how, client.getRemoteIp(), s.getId());
        return Message.ok(req, "Đăng nhập thành công")
                .put("user", user)
                .put("token", s.getToken())
                .put("sessionId", s.getId())
                .put("idleTimeoutMinutes", (int) (sessions.getIdleTimeoutMs() / 60000));
    }

    private Message handleAuthenticated(Message req, Command cmd, Session session, UserInfo user) {
        switch (cmd) {
            case LOGOUT:
                sessions.logout(session);
                Log.info("%s đăng xuất", user.getUsername());
                return Message.ok(req, "Đã đăng xuất");

            // ---------------- Người dùng ----------------
            case GET_ZONES:
                return Message.ok(req)
                        .putList("zones", content.listZones())
                        .putList("seats", content.listSeats());

            case GET_SEAT_MAP: {
                LocalDate date = req.get("date");
                return Message.ok(req).put("map", bookings.getSeatMap(user, date, req.getInt("slot", 0)));
            }

            case BOOK_SEAT: {
                LocalDate date = req.get("date");
                String rawCompanions = req.getString("companions");
                List<String> companions = rawCompanions == null ? null
                        : java.util.Arrays.asList(rawCompanions.split("[,;\\s]+"));
                Booking b = bookings.book(user, req.getString("seatId"), date, req.getInt("slot", 0),
                        req.getBool("onePlace"), companions);
                return Message.ok(req, "Đặt chỗ thành công! Mã đặt chỗ: " + b.getId()
                        + (b.isSharedPlace() ? "\nBạn ngồi ghép bàn " + b.getSeatId() + ", vị trí #" + b.getPlace() + "." : "")
                        + (b.getCompanions().isEmpty() ? "" : "\nĐã gửi lời mời đi cùng tới: " + b.getCompanionNames()
                        + ".\nHọ cần bấm \"Đồng ý\" trong ứng dụng thì bàn mới đủ người để check-in.")
                        + "\nNhớ check-in trong khoảng 15 phút trước/sau giờ bắt đầu ca.").put("booking", b);
            }

            case CANCEL_BOOKING: {
                Booking b = bookings.cancelByUser(user, req.getString("bookingId"));
                return Message.ok(req, "Đã hủy lượt đặt " + b.getId()
                        + (b.getReputationNote() != null ? ".\n" + b.getReputationNote() : "")).put("booking", b);
            }

            case CHECK_IN: {
                Booking b = bookings.checkIn(user, req.getString("bookingId"));
                return Message.ok(req, "Check-in thành công chỗ " + b.getSeatId() + ". Chúc bạn học tốt!"
                        + (b.getReputationNote() != null ? "\n" + b.getReputationNote() : "")).put("booking", b);
            }

            case CHECK_OUT: {
                Booking b = bookings.checkOut(user, req.getString("bookingId"));
                return Message.ok(req, "Đã trả chỗ " + b.getSeatId() + ". Cảm ơn bạn!").put("booking", b);
            }

            case GET_MY_BOOKINGS:
                return Message.ok(req).putList("bookings", bookings.myBookings(user.getUsername()))
                        .putList("invites", bookings.companionInvites(user.getUsername()));

            case RESPOND_COMPANION: {
                boolean accept = req.getBool("accept");
                Booking b = bookings.respondCompanion(user, req.getString("bookingId"), accept);
                return Message.ok(req, accept
                        ? "Bạn đã đồng ý đi cùng " + b.getFullName() + " ở " + b.describe() + ". Nhớ đến đúng giờ!"
                        : "Bạn đã từ chối đi cùng " + b.getFullName() + ". Bạn có thể tự đặt chỗ khác trong ca này.")
                        .put("booking", b);
            }

            case UPDATE_COMPANIONS: {
                String raw = req.getString("companions");
                Booking b = bookings.updateCompanions(user, req.getString("bookingId"),
                        raw == null ? null : java.util.Arrays.asList(raw.split("[,;\\s]+")));
                return Message.ok(req, "Đã cập nhật người đi cùng lượt " + b.getId() + ": " + b.getCompanionsLabel()
                        + (b.isCompanionsReady() ? "" : "\nNgười mới được mời cần bấm \"Đồng ý\" trong ứng dụng."))
                        .put("booking", b);
            }

            case GET_ANNOUNCEMENTS:
                return Message.ok(req).putList("announcements", content.listAnnouncements());

            case GET_MY_REPUTATION:
                return Message.ok(req).put("reputation", reputation.get(user.getUsername()));

            // ---------------- Quản trị: điểm uy tín ----------------
            case ADMIN_GET_STUDENTS:
                return Message.ok(req).putList("students", reputation.listStudents());

            case ADMIN_GET_REPUTATION:
                return Message.ok(req).put("reputation", reputation.get(req.getString("username")));

            case ADMIN_ADJUST_REPUTATION: {
                ReputationInfo r = reputation.adjust(user, req.getString("username"), req.getInt("delta", 0),
                        req.getBool("reset"), req.getString("reason"));
                return Message.ok(req, "Đã cập nhật điểm uy tín của " + r.getFullName() + ": " + r.getScore()
                        + " điểm (" + r.getLevel().getLabel() + ")").put("reputation", r);
            }

            // ---------------- Quản trị: nội dung ----------------
            case ADMIN_SAVE_ZONE: {
                Zone z = content.saveZone(user, req.get("zone"), req.getBool("isNew"));
                return Message.ok(req, "Đã lưu " + z.getDisplayName()).put("zone", z);
            }

            case ADMIN_DELETE_ZONE: {
                int n = content.deleteZone(user, req.getString("zoneId"));
                return Message.ok(req, "Đã xóa khu vực" + (n > 0 ? " (hủy " + n + " lượt đặt liên quan)" : ""));
            }

            case ADMIN_SAVE_SEAT: {
                Seat s = content.saveSeat(user, req.get("seat"), req.getBool("isNew"));
                return Message.ok(req, "Đã lưu chỗ " + s.getId()).put("seat", s);
            }

            case ADMIN_ADD_SEATS: {
                SeatType type = req.get("type");
                List<String> ids = content.addSeats(user, req.getString("zoneId"), req.getString("prefix"),
                        req.getInt("count", 0), type, req.getInt("capacity", 1), req.getBool("outlet"));
                return Message.ok(req, "Đã thêm " + ids.size() + " chỗ: " + String.join(", ", ids));
            }

            case ADMIN_DELETE_SEAT: {
                int n = content.deleteSeat(user, req.getString("seatId"));
                return Message.ok(req, "Đã xóa chỗ" + (n > 0 ? " (hủy " + n + " lượt đặt liên quan)" : ""));
            }

            case ADMIN_SET_SEAT_ACTIVE: {
                boolean active = req.getBool("active");
                int n = content.setSeatActive(user, req.getString("seatId"), active, req.getString("reason"));
                return Message.ok(req, (active ? "Đã mở lại chỗ" : "Đã chuyển chỗ sang bảo trì")
                        + (n > 0 ? " (hủy " + n + " lượt đặt và đã báo cho người đặt)" : ""));
            }

            case ADMIN_POST_ANNOUNCEMENT:
                content.postAnnouncement(user, req.getString("title"), req.getString("content"));
                return Message.ok(req, "Đã đăng thông báo tới mọi người dùng");

            case ADMIN_DELETE_ANNOUNCEMENT:
                content.deleteAnnouncement(user, req.getString("id"));
                return Message.ok(req, "Đã xóa thông báo");

            // ---------------- Quản trị: booking, phiên, hệ thống ----------------
            case ADMIN_GET_BOOKINGS: {
                LocalDate date = req.get("date");
                BookingStatus status = req.get("status");
                return Message.ok(req).putList("bookings", bookings.allBookings(date, status));
            }

            case ADMIN_BOOK_SEAT: {
                LocalDate date = req.get("date");
                BookingService.WalkInInfo guest = req.getBool("walkIn")
                        ? new BookingService.WalkInInfo(req.getString("guestName"), req.getString("guestStudentId"),
                        req.getString("guestPhone"), req.getString("guestNote"))
                        : null;
                Booking b = bookings.bookByAdmin(user, req.getString("seatId"), date, req.getInt("slot", 0), guest,
                        req.getBool("checkInNow"), req.getBool("onePlace"));
                String who = b.isWalkIn() ? " cho " + b.getFullName() : "";
                return Message.ok(req, "Đã đặt chỗ " + b.getSeatLabel() + who + ". Mã đặt chỗ: " + b.getId()
                        + (b.getStatus() == BookingStatus.CHECKED_IN ? " (đã check-in)" : "")).put("booking", b);
            }

            case ADMIN_CHECK_IN: {
                Booking b = bookings.checkInByAdmin(user, req.getString("bookingId"));
                return Message.ok(req, "Đã check-in lượt " + b.getId() + " - " + b.getFullName() + " ngồi chỗ "
                        + b.getSeatId() + (b.getReputationNote() != null ? " (" + b.getReputationNote() + ")" : ""))
                        .put("booking", b);
            }

            case ADMIN_CHECK_OUT: {
                Booking b = bookings.checkOutByAdmin(user, req.getString("bookingId"));
                return Message.ok(req, "Đã xác nhận trả chỗ " + b.getSeatId() + " (" + b.getId() + ")").put("booking", b);
            }

            case ADMIN_CANCEL_BOOKING: {
                Booking b = bookings.cancelByAdmin(user, req.getString("bookingId"), req.getString("reason"));
                return Message.ok(req, "Đã hủy lượt đặt " + b.getId() + " và thông báo cho " + b.getFullName());
            }

            case ADMIN_GET_SESSIONS:
                return Message.ok(req).putList("sessions", sessions.snapshot());

            case ADMIN_KICK_SESSION: {
                String id = req.getString("sessionId");
                if (id != null && id.equals(session.getId())) {
                    throw new ServiceException("Không thể tự ngắt phiên của chính mình. Hãy dùng Đăng xuất.");
                }
                boolean ok = sessions.kick(id, "Quản trị viên (" + user.getUsername()
                        + ") đã kết thúc phiên đăng nhập của bạn.");
                if (!ok) {
                    throw new ServiceException("Phiên " + id + " không còn tồn tại.");
                }
                return Message.ok(req, "Đã ngắt phiên " + id);
            }

            case ADMIN_GET_STATS:
                return Message.ok(req).put("stats", server.getStats());

            default:
                return Message.error(req, "Lệnh không được hỗ trợ: " + cmd);
        }
    }
}
