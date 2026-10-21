package library.server.db;

import library.common.Reputation;
import library.server.Log;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Cấu trúc các bảng MySQL. Server tự chạy các lệnh này khi khởi động (CREATE TABLE IF NOT EXISTS),
 * nội dung giống file database/thuvien_fixbug.sql.
 *
 * <p>Điểm đáng chú ý: cột {@code live} của bảng bookings = 1 khi lượt đặt còn hiệu lực và = NULL khi đã kết thúc.
 * MySQL cho phép nhiều giá trị NULL trong UNIQUE KEY, nên khóa {@code uq_seat_live(seat_id, booking_date, slot, live)}
 * đảm bảo ở MỨC CSDL: một chỗ trong một ca chỉ có tối đa 1 lượt còn hiệu lực - kể cả khi nhiều luồng cùng ghi.</p>
 *
 * <p>Cột {@code owner_live} = 1 chỉ với lượt sinh viên TỰ đặt còn hiệu lực; khóa
 * {@code uq_owner_live(username, booking_date, slot, owner_live)} bảo đảm mỗi sinh viên giữ 1 chỗ mỗi ca.
 * Lượt do quản trị viên đặt (cho mình hoặc hộ sinh viên tại quầy) có owner_live = NULL nên không bị giới hạn.</p>
 */
public final class Schema {

    private static final String OPTIONS = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    public static final String[] TABLES = {
            "CREATE TABLE IF NOT EXISTS users (\n"
                    + "  username      VARCHAR(20)  NOT NULL PRIMARY KEY,\n"
                    + "  full_name     VARCHAR(60)  NOT NULL,\n"
                    + "  student_id    VARCHAR(12)  NULL,\n"
                    + "  role          VARCHAR(10)  NOT NULL,\n"
                    + "  salt          VARCHAR(64)  NOT NULL,\n"
                    + "  password_hash VARCHAR(128) NOT NULL,\n"
                    + "  created_at    DATETIME     NOT NULL,\n"
                    + "  reputation    INT          NOT NULL DEFAULT " + Reputation.START + ",\n"
                    + "  locked_until  DATE         NULL,\n"
                    + "  UNIQUE KEY uq_users_student_id (student_id)\n"
                    + ")" + OPTIONS,

            "CREATE TABLE IF NOT EXISTS zones (\n"
                    + "  id          VARCHAR(4)   NOT NULL PRIMARY KEY,\n"
                    + "  name        VARCHAR(60)  NOT NULL,\n"
                    + "  floor       INT          NOT NULL,\n"
                    + "  description VARCHAR(200) NULL\n"
                    + ")" + OPTIONS,

            "CREATE TABLE IF NOT EXISTS seats (\n"
                    + "  id         VARCHAR(10)  NOT NULL PRIMARY KEY,\n"
                    + "  zone_id    VARCHAR(4)   NOT NULL,\n"
                    + "  type       VARCHAR(10)  NOT NULL,\n"
                    + "  capacity   INT          NOT NULL,\n"
                    + "  has_outlet BOOLEAN      NOT NULL DEFAULT FALSE,\n"
                    + "  active     BOOLEAN      NOT NULL DEFAULT TRUE,\n"
                    + "  note       VARCHAR(100) NULL,\n"
                    + "  CONSTRAINT fk_seats_zone FOREIGN KEY (zone_id) REFERENCES zones(id) ON DELETE CASCADE\n"
                    + ")" + OPTIONS,

            "CREATE TABLE IF NOT EXISTS bookings (\n"
                    + "  id            INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,\n"
                    + "  username      VARCHAR(20)  NOT NULL,\n"
                    + "  full_name     VARCHAR(60)  NOT NULL,\n"
                    + "  student_id    VARCHAR(12)  NULL,\n"
                    + "  seat_id       VARCHAR(10)  NOT NULL,\n"
                    + "  zone_id       VARCHAR(4)   NOT NULL,\n"
                    + "  zone_name     VARCHAR(80)  NOT NULL,\n"
                    + "  booking_date  DATE         NOT NULL,\n"
                    + "  slot          TINYINT      NOT NULL,\n"
                    + "  status        VARCHAR(12)  NOT NULL,\n"
                    + "  live          TINYINT      NULL,\n"
                    + "  owner_live    TINYINT      NULL,\n"
                    + "  walk_in       BOOLEAN      NOT NULL DEFAULT FALSE,\n"
                    + "  phone         VARCHAR(15)  NULL,\n"
                    + "  guest_note    VARCHAR(200) NULL,\n"
                    + "  created_at    DATETIME     NOT NULL,\n"
                    + "  checked_in_at DATETIME     NULL,\n"
                    + "  finished_at   DATETIME     NULL,\n"
                    + "  note          VARCHAR(255) NULL,\n"
                    + "  CONSTRAINT fk_bookings_user FOREIGN KEY (username) REFERENCES users(username),\n"
                    + "  UNIQUE KEY uq_seat_live (seat_id, booking_date, slot, live),\n"
                    + "  UNIQUE KEY uq_owner_live (username, booking_date, slot, owner_live),\n"
                    + "  KEY idx_bookings_date (booking_date, slot),\n"
                    + "  KEY idx_bookings_live (live, booking_date)\n"
                    + ")" + OPTIONS,

            "CREATE TABLE IF NOT EXISTS reputation_log (\n"
                    + "  id          INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,\n"
                    + "  username    VARCHAR(20)  NOT NULL,\n"
                    + "  delta       INT          NOT NULL,\n"
                    + "  score_after INT          NOT NULL,\n"
                    + "  reason      VARCHAR(255) NOT NULL,\n"
                    + "  booking_id  INT          NULL,\n"
                    + "  created_at  DATETIME     NOT NULL,\n"
                    + "  CONSTRAINT fk_replog_user FOREIGN KEY (username) REFERENCES users(username),\n"
                    + "  KEY idx_replog_user (username, created_at)\n"
                    + ")" + OPTIONS,

            "CREATE TABLE IF NOT EXISTS announcements (\n"
                    + "  id         INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,\n"
                    + "  title      VARCHAR(120) NOT NULL,\n"
                    + "  content    TEXT         NOT NULL,\n"
                    + "  author     VARCHAR(80)  NOT NULL,\n"
                    + "  created_at DATETIME     NOT NULL\n"
                    + ")" + OPTIONS
    };

    private Schema() {
    }

    static void create(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String sql : TABLES) {
                st.executeUpdate(sql);
            }
        }
    }

    /**
     * Nâng cấp CSDL tạo bởi phiên bản trước (chưa có chức năng đặt tại quầy): thêm cột mới và đổi khóa
     * uq_user_live → uq_owner_live. Từng bước đều kiểm tra trước nên chạy lại nhiều lần vẫn an toàn,
     * dữ liệu cũ được giữ nguyên.
     *
     * @return true nếu có thay đổi cấu trúc
     */
    static boolean migrate(Connection c) throws SQLException {
        boolean changed = false;
        try (Statement st = c.createStatement()) {
            // v3: điểm uy tín
            if (!hasColumn(c, "users", "reputation")) {
                st.executeUpdate("ALTER TABLE users ADD COLUMN reputation INT NOT NULL DEFAULT " + Reputation.START);
                changed = true;
            }
            // v4: điểm khởi đầu đổi từ 100 xuống 80 - sinh viên chưa từng được cộng / trừ điểm về mức khởi đầu mới
            String def = columnDefault(c, "users", "reputation");
            if (def != null && !def.replaceAll("[^0-9]", "").equals(String.valueOf(Reputation.START))) {
                st.executeUpdate("ALTER TABLE users ALTER COLUMN reputation SET DEFAULT " + Reputation.START);
                int n = st.executeUpdate("UPDATE users SET reputation = " + Reputation.START
                        + " WHERE role = 'STUDENT' AND reputation > " + Reputation.START
                        + " AND NOT EXISTS (SELECT 1 FROM reputation_log r WHERE r.username = users.username)");
                Log.info("Điểm uy tín khởi đầu đổi thành %d: đã cập nhật %d tài khoản sinh viên chưa có lịch sử điểm",
                        Reputation.START, n);
                changed = true;
            }
            if (!hasColumn(c, "users", "locked_until")) {
                st.executeUpdate("ALTER TABLE users ADD COLUMN locked_until DATE NULL");
                changed = true;
            }
            // v2: đặt chỗ tại quầy
            if (!hasColumn(c, "bookings", "owner_live")) {
                st.executeUpdate("ALTER TABLE bookings ADD COLUMN owner_live TINYINT NULL");
                st.executeUpdate("UPDATE bookings SET owner_live = live"); // lượt cũ đều do sinh viên tự đặt
                changed = true;
            }
            if (!hasColumn(c, "bookings", "walk_in")) {
                st.executeUpdate("ALTER TABLE bookings ADD COLUMN walk_in BOOLEAN NOT NULL DEFAULT FALSE");
                changed = true;
            }
            if (!hasColumn(c, "bookings", "phone")) {
                st.executeUpdate("ALTER TABLE bookings ADD COLUMN phone VARCHAR(15) NULL");
                changed = true;
            }
            if (!hasColumn(c, "bookings", "guest_note")) {
                st.executeUpdate("ALTER TABLE bookings ADD COLUMN guest_note VARCHAR(200) NULL");
                changed = true;
            }
            // Tạo khóa mới TRƯỚC rồi mới xóa khóa cũ: MySQL cần một chỉ mục bắt đầu bằng username cho khóa ngoại.
            if (!hasIndex(c, "bookings", "uq_owner_live")) {
                st.executeUpdate("ALTER TABLE bookings ADD UNIQUE KEY uq_owner_live (username, booking_date, slot, owner_live)");
                changed = true;
            }
            if (hasIndex(c, "bookings", "uq_user_live")) {
                try {
                    st.executeUpdate("ALTER TABLE bookings DROP INDEX uq_user_live");
                } catch (SQLException e) {
                    st.executeUpdate("ALTER TABLE bookings DROP CONSTRAINT uq_user_live");
                }
                changed = true;
            }
        }
        return changed;
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String columnDefault(Connection c, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return rs.getString("COLUMN_DEF");
                }
            }
        }
        return null;
    }

    private static boolean hasIndex(Connection c, String table, String index) throws SQLException {
        try (ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && name.toLowerCase().startsWith(index.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
}
