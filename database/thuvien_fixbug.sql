-- =====================================================================
--  CSDL cho "Thư viện Fix Bug - Đặt chỗ học trực tuyến" - Nhóm Fix Bug
--  Dùng cho MySQL 5.7+ / 8.x hoặc MariaDB 10.3+ (XAMPP).
--  Cách dùng: phpMyAdmin → Import file này, hoặc MySQL Workbench → File → Run SQL Script.
--  (Không bắt buộc: server tự tạo database, bảng và dữ liệu mẫu khi chạy lần đầu.)
--  Mật khẩu mẫu: admin / admin123 ; thinh, truong, bao / 123456 (đã băm PBKDF2 + salt).
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `thuvien_fixbug` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `thuvien_fixbug`;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS users (
  username      VARCHAR(20)  NOT NULL PRIMARY KEY,
  full_name     VARCHAR(60)  NOT NULL,
  student_id    VARCHAR(12)  NULL,
  role          VARCHAR(10)  NOT NULL,
  salt          VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  created_at    DATETIME     NOT NULL,
  reputation    INT          NOT NULL DEFAULT 80,
  locked_until  DATE         NULL,
  UNIQUE KEY uq_users_student_id (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS zones (
  id          VARCHAR(4)   NOT NULL PRIMARY KEY,
  name        VARCHAR(60)  NOT NULL,
  floor       INT          NOT NULL,
  description VARCHAR(200) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seats (
  id         VARCHAR(10)  NOT NULL PRIMARY KEY,
  zone_id    VARCHAR(4)   NOT NULL,
  type       VARCHAR(10)  NOT NULL,
  capacity   INT          NOT NULL,
  has_outlet BOOLEAN      NOT NULL DEFAULT FALSE,
  active     BOOLEAN      NOT NULL DEFAULT TRUE,
  note       VARCHAR(100) NULL,
  CONSTRAINT fk_seats_zone FOREIGN KEY (zone_id) REFERENCES zones(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bookings (
  id            INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(20)  NOT NULL,
  full_name     VARCHAR(60)  NOT NULL,
  student_id    VARCHAR(12)  NULL,
  seat_id       VARCHAR(10)  NOT NULL,
  zone_id       VARCHAR(4)   NOT NULL,
  zone_name     VARCHAR(80)  NOT NULL,
  booking_date  DATE         NOT NULL,
  slot          TINYINT      NOT NULL,
  status        VARCHAR(12)  NOT NULL,
  live          TINYINT      NULL,
  owner_live    TINYINT      NULL,
  walk_in       BOOLEAN      NOT NULL DEFAULT FALSE,
  phone         VARCHAR(15)  NULL,
  guest_note    VARCHAR(200) NULL,
  created_at    DATETIME     NOT NULL,
  checked_in_at DATETIME     NULL,
  finished_at   DATETIME     NULL,
  note          VARCHAR(255) NULL,
  CONSTRAINT fk_bookings_user FOREIGN KEY (username) REFERENCES users(username),
  UNIQUE KEY uq_seat_live (seat_id, booking_date, slot, live),
  UNIQUE KEY uq_owner_live (username, booking_date, slot, owner_live),
  KEY idx_bookings_date (booking_date, slot),
  KEY idx_bookings_live (live, booking_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reputation_log (
  id          INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username    VARCHAR(20)  NOT NULL,
  delta       INT          NOT NULL,
  score_after INT          NOT NULL,
  reason      VARCHAR(255) NOT NULL,
  booking_id  INT          NULL,
  created_at  DATETIME     NOT NULL,
  CONSTRAINT fk_replog_user FOREIGN KEY (username) REFERENCES users(username),
  KEY idx_replog_user (username, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS announcements (
  id         INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
  title      VARCHAR(120) NOT NULL,
  content    TEXT         NOT NULL,
  author     VARCHAR(80)  NOT NULL,
  created_at DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- Tài khoản
INSERT IGNORE INTO users (username, full_name, student_id, role, salt, password_hash, created_at) VALUES
  ('admin', 'Quản trị viên Thư viện Fix Bug', NULL, 'ADMIN', 'z4PU3H3NHQLlf7RQqvErwA==', 'l0mypUKOrpLuU3sDLySlOpiLY+Ucrb5AMQ8Xi16ue5Q=', NOW()),
  ('thinh', 'Nguyễn Văn Thịnh', '2280603107', 'STUDENT', '4nuvkdpQw/Iac2UNwSuJ2w==', 'bFyo1iHofUIeMlNh6zqR3dVYZDRFA/Ns8umLqLOADps=', NOW()),
  ('truong', 'Nguyễn Đan Trường', '2280603486', 'STUDENT', 'tou5eynsJAp/40E/pkPssQ==', '8hzVSyZayGxbHIwSSfwpBGS52XHQbm8l7sU7T9OnxVc=', NOW()),
  ('bao', 'Chu Nguyễn Gia Bảo', '2380600118', 'STUDENT', 'rhHmPQrcuV7CMT3T5gUU4g==', 'gJbemb7tjQ4KzcjrdXJqQSsOoLrX1pV1zee6CH3drTg=', NOW());

-- ---------------------------------------------------------------- Khu vực
INSERT IGNORE INTO zones (id, name, floor, description) VALUES
  ('A', 'Khu đọc yên tĩnh', 1, 'Giữ trật tự tuyệt đối, phù hợp tự học và ôn thi.'),
  ('B', 'Khu học nhóm', 2, 'Bàn lớn cho nhóm 4-6 người, được phép thảo luận nhỏ.'),
  ('C', 'Khu máy tính', 2, 'Máy tính có Internet, tra cứu cơ sở dữ liệu và tài liệu số.'),
  ('D', 'Phòng học riêng', 3, 'Phòng kín có bảng và máy chiếu, dành cho nhóm thuyết trình.');

-- ---------------------------------------------------------------- Chỗ ngồi
INSERT IGNORE INTO seats (id, zone_id, type, capacity, has_outlet, active, note) VALUES
  ('A01', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A02', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A03', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A04', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A05', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A06', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A07', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A08', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A09', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A10', 'A', 'SINGLE', 1, TRUE, TRUE, NULL),
  ('A11', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A12', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A13', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A14', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A15', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A16', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A17', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A18', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A19', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('A20', 'A', 'SINGLE', 1, FALSE, TRUE, NULL),
  ('B01', 'B', 'GROUP', 6, TRUE, TRUE, NULL),
  ('B02', 'B', 'GROUP', 6, TRUE, TRUE, NULL),
  ('B03', 'B', 'GROUP', 6, TRUE, TRUE, NULL),
  ('B04', 'B', 'GROUP', 6, TRUE, TRUE, NULL),
  ('B05', 'B', 'GROUP', 4, TRUE, TRUE, NULL),
  ('B06', 'B', 'GROUP', 4, TRUE, TRUE, NULL),
  ('B07', 'B', 'GROUP', 4, TRUE, TRUE, NULL),
  ('B08', 'B', 'GROUP', 4, TRUE, TRUE, NULL),
  ('C01', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C02', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C03', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C04', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C05', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C06', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C07', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C08', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C09', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C10', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C11', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('C12', 'C', 'COMPUTER', 1, TRUE, TRUE, NULL),
  ('D01', 'D', 'ROOM', 8, TRUE, TRUE, NULL),
  ('D02', 'D', 'ROOM', 8, TRUE, TRUE, NULL),
  ('D03', 'D', 'ROOM', 8, TRUE, TRUE, NULL),
  ('D04', 'D', 'ROOM', 8, TRUE, TRUE, NULL);

-- ---------------------------------------------------------------- Thông báo
INSERT INTO announcements (title, content, author, created_at)
SELECT 'Chào mừng đến với Thư viện Fix Bug', 'Thư viện Fix Bug mở cửa từ 07:00 đến 21:00, chia thành 5 ca học.\n\nQuy định:\n• Mỗi sinh viên được đặt tối đa 3 ca trong một ngày, đặt trước tối đa 7 ngày.\n• Check-in trong khoảng 15 phút trước đến 15 phút sau giờ bắt đầu ca.\n• Không check-in đúng giờ, hệ thống sẽ tự động hủy và nhường chỗ cho người khác.\n• Nếu không đến được, vui lòng hủy lượt đặt sớm.', 'Quản trị viên Thư viện Fix Bug', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM announcements);

-- ---------------------------------------------------------------- Truy vấn tham khảo
-- Sơ đồ chỗ ngày mai, ca 1:
--   SELECT s.id, s.zone_id, b.username, b.status FROM seats s
--   LEFT JOIN bookings b ON b.seat_id = s.id AND b.booking_date = CURDATE() + INTERVAL 1 DAY AND b.slot = 1 AND b.live = 1
--   ORDER BY s.zone_id, s.id;
-- Thống kê lượt đặt theo trạng thái hôm nay:
--   SELECT status, COUNT(*) FROM bookings WHERE booking_date = CURDATE() GROUP BY status;
