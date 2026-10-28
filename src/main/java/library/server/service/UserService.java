package library.server.service;

import library.common.Role;
import library.common.UserInfo;
import library.server.Log;
import library.server.db.Database;
import library.server.db.Sql;
import library.server.db.UserDao;
import library.server.db.UserDao.UserRecord;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Đăng ký, đăng nhập (bảng users trong MySQL), chống dò mật khẩu (khóa tạm sau 5 lần sai). */
public class UserService {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{3,20}");
    private static final Pattern STUDENT_ID = Pattern.compile("\\d{6,12}");
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_MILLIS = 60_000;

    private final Database db;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static final class Attempt {
        int failures;
        long lockedUntil;
    }

    public UserService(Database db) {
        this.db = db;
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    public UserInfo authenticate(String rawUsername, String password, String ip) {
        String username = normalizeUsername(rawUsername);
        if (username.isEmpty() || password == null || password.isEmpty()) {
            throw new ServiceException("Vui lòng nhập tên đăng nhập và mật khẩu.");
        }
        long now = System.currentTimeMillis();
        Attempt attempt = attempts.get(username);
        if (attempt != null) {
            synchronized (attempt) {
                if (attempt.lockedUntil > now) {
                    long seconds = (attempt.lockedUntil - now) / 1000 + 1;
                    throw new ServiceException("Tài khoản tạm khóa do nhập sai mật khẩu nhiều lần. "
                            + "Vui lòng thử lại sau " + seconds + " giây.");
                }
            }
        }

        UserRecord user = db.query(c -> UserDao.find(c, username));
        // Việc băm mật khẩu (tốn CPU) làm sau khi đã trả kết nối CSDL về pool
        if (user == null || !PasswordHasher.verify(password, user.salt, user.passwordHash)) {
            registerFailure(username, ip);
            throw new ServiceException("Sai tên đăng nhập hoặc mật khẩu.");
        }
        attempts.remove(username);
        return user.toInfo();
    }

    private void registerFailure(String username, String ip) {
        Attempt a = attempts.computeIfAbsent(username, k -> new Attempt());
        synchronized (a) {
            a.failures++;
            Log.warn("Đăng nhập sai lần %d cho '%s' từ %s", a.failures, username, ip);
            if (a.failures >= MAX_FAILED_ATTEMPTS) {
                a.failures = 0;
                a.lockedUntil = System.currentTimeMillis() + LOCK_MILLIS;
                Log.warn("Tạm khóa đăng nhập tài khoản '%s' trong %d giây", username, LOCK_MILLIS / 1000);
            }
        }
    }

    public UserInfo register(String rawUsername, String password, String rawFullName, String rawStudentId) {
        String username = normalizeUsername(rawUsername);
        String fullName = rawFullName == null ? "" : rawFullName.trim().replaceAll("\\s+", " ");
        String studentId = rawStudentId == null ? "" : rawStudentId.trim();

        if (!USERNAME.matcher(username).matches()) {
            throw new ServiceException("Tên đăng nhập gồm 3-20 ký tự: chữ thường không dấu, số, dấu _ hoặc dấu chấm.");
        }
        if (password == null || password.length() < 6) {
            throw new ServiceException("Mật khẩu phải có ít nhất 6 ký tự.");
        }
        if (fullName.length() < 2 || fullName.length() > 60) {
            throw new ServiceException("Vui lòng nhập họ tên hợp lệ (2-60 ký tự).");
        }
        if (!STUDENT_ID.matcher(studentId).matches()) {
            throw new ServiceException("Mã số sinh viên phải gồm 6-12 chữ số.");
        }

        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(password, salt);
        UserInfo info = db.tx(c -> {
            if (UserDao.find(c, username) != null) {
                throw new ServiceException("Tên đăng nhập '" + username + "' đã tồn tại.");
            }
            if (UserDao.studentIdExists(c, studentId)) {
                throw new ServiceException("Mã số sinh viên " + studentId + " đã được đăng ký.");
            }
            try {
                UserDao.insert(c, username, fullName, studentId, Role.STUDENT, salt, hash);
            } catch (SQLException e) { // hai người đăng ký cùng lúc cùng tên / cùng MSSV
                if (Sql.isDuplicate(e, "PRIMARY") || Sql.isDuplicate(e, "uq_users_student_id")) {
                    throw new ServiceException("Tên đăng nhập hoặc mã số sinh viên đã được đăng ký.");
                }
                throw e;
            }
            return new UserInfo(username, fullName, studentId, Role.STUDENT);
        });
        Log.info("Đăng ký tài khoản mới: %s - %s (%s)", username, fullName, studentId);
        return info;
    }

    // ------------------------------------------------------------------ Đăng nhập bằng Google

    /** Tài khoản đã liên kết với tài khoản Google này, hoặc null nếu chưa liên kết. */
    public UserInfo findByGoogle(library.server.auth.GoogleIdentity id) {
        UserRecord r = db.query(c -> UserDao.findByGoogleSub(c, id.sub));
        return r == null ? null : r.toInfo();
    }

    /**
     * Tạo tài khoản SINH VIÊN mới gắn với tài khoản Google (không có mật khẩu dùng được - chỉ đăng nhập qua Google).
     */
    public UserInfo registerGoogle(library.server.auth.GoogleIdentity id, String rawUsername, String rawFullName,
                                   String rawStudentId) {
        String username = normalizeUsername(rawUsername);
        String fullName = rawFullName == null ? "" : rawFullName.trim().replaceAll("\\s+", " ");
        String studentId = rawStudentId == null ? "" : rawStudentId.trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new ServiceException("Tên đăng nhập gồm 3-20 ký tự: chữ thường không dấu, số, dấu _ hoặc dấu chấm.");
        }
        if (fullName.length() < 2 || fullName.length() > 60) {
            throw new ServiceException("Vui lòng nhập họ tên hợp lệ (2-60 ký tự).");
        }
        if (!STUDENT_ID.matcher(studentId).matches()) {
            throw new ServiceException("Mã số sinh viên phải gồm 6-12 chữ số.");
        }
        // Mật khẩu ngẫu nhiên không ai biết → tài khoản này chỉ đăng nhập được bằng Google
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(PasswordHasher.newSalt() + PasswordHasher.newSalt(), salt);
        UserInfo info = db.tx(c -> {
            UserRecord linked = UserDao.findByGoogleSub(c, id.sub);
            if (linked != null) {
                throw new ServiceException("Tài khoản Google " + id.email + " đã liên kết với tài khoản '"
                        + linked.username + "'.");
            }
            if (UserDao.find(c, username) != null) {
                throw new ServiceException("Tên đăng nhập '" + username + "' đã tồn tại, hãy chọn tên khác.");
            }
            if (UserDao.studentIdExists(c, studentId)) {
                throw new ServiceException("Mã số sinh viên " + studentId + " đã được đăng ký. Nếu đó là tài khoản "
                        + "của bạn, hãy chọn \"Liên kết với tài khoản đã có\".");
            }
            try {
                UserDao.insert(c, username, fullName, studentId, Role.STUDENT, salt, hash);
                UserDao.linkGoogle(c, username, id.sub, id.email);
            } catch (SQLException e) {
                if (Sql.isDuplicate(e, "PRIMARY") || Sql.isDuplicate(e, "uq_users_student_id")
                        || Sql.isDuplicate(e, "uq_users_google")) {
                    throw new ServiceException("Tên đăng nhập, mã số sinh viên hoặc tài khoản Google đã được đăng ký.");
                }
                throw e;
            }
            return new UserInfo(username, fullName, studentId, Role.STUDENT);
        });
        Log.info("Đăng ký tài khoản mới bằng Google: %s - %s (%s, %s)", username, fullName, studentId, id.email);
        return info;
    }

    /** Liên kết tài khoản Google với tài khoản đã có (phải nhập đúng mật khẩu của tài khoản đó). */
    public UserInfo linkGoogle(library.server.auth.GoogleIdentity id, String rawUsername, String password, String ip) {
        UserInfo user = authenticate(rawUsername, password, ip);
        db.tx(c -> {
            UserRecord linked = UserDao.findByGoogleSub(c, id.sub);
            if (linked != null && !linked.username.equals(user.getUsername())) {
                throw new ServiceException("Tài khoản Google " + id.email + " đã liên kết với tài khoản '"
                        + linked.username + "'.");
            }
            String current = UserDao.googleSubOf(c, user.getUsername());
            if (current != null && !current.equals(id.sub)) {
                throw new ServiceException("Tài khoản '" + user.getUsername()
                        + "' đã liên kết với một tài khoản Google khác.");
            }
            UserDao.linkGoogle(c, user.getUsername(), id.sub, id.email);
            return null;
        });
        Log.info("Liên kết tài khoản %s với Google %s", user.getUsername(), id.email);
        return user;
    }

    public int countUsers() {
        return db.query(UserDao::count);
    }
}
