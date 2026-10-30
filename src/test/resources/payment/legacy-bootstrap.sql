-- CarRent local development bootstrap — MySQL 8.0.16+.
-- Run from the project root: mysql -h localhost -P 3306 -u root -p < scriptDB.sql
-- PowerShell: use mysql --execute="source C:/Users/Admin/Documents/hoc/Car/scriptDB.sql"
-- No DROP/TRUNCATE, no disabled FK checks. Existing rows/passwords are preserved.
-- This bootstraps the agreed schema; it does not migrate an incompatible schema.
-- Seed IDs are reserved for demo records. Application inserts use AUTO_INCREMENT.
-- Admin: admin@carrent.local / CarRentAdmin@2026!
-- Other demo accounts: owner1, owner2, customer1, customer2, inactive, banned
-- Password for those accounts: CarRent@2026!
-- Passwords below are BCrypt cost 10. Demo credentials are for local use only.
-- Companion images: uploads/seed-car-1.png ... seed-car-6.png (P2.3 adds route).

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET SESSION time_zone = '+07:00';
CREATE DATABASE IF NOT EXISTS carrent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE carrent;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NULL,
    avatar_url VARCHAR(255) NULL,
    role ENUM('ADMIN','CUSTOMER','OWNER') NOT NULL,
    status ENUM('ACTIVE','INACTIVE','BANNED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_role_status (role, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS car_categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_car_categories_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cars (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    brand VARCHAR(50) NOT NULL,
    model VARCHAR(50) NOT NULL,
    manufacture_year INT NOT NULL,
    license_plate VARCHAR(20) NULL,
    color VARCHAR(30) NULL,
    seat_count INT NOT NULL,
    transmission ENUM('MANUAL','AUTOMATIC') NOT NULL,
    fuel_type ENUM('GASOLINE','DIESEL','ELECTRIC','HYBRID') NOT NULL,
    description TEXT NULL,
    price_per_day BIGINT NOT NULL,
    province VARCHAR(100) NOT NULL,
    district VARCHAR(100) NULL,
    address_detail VARCHAR(255) NULL,
    approval_status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
    listing_status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    rejection_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_cars_owner (owner_id),
    KEY idx_cars_category (category_id),
    KEY idx_cars_visibility (approval_status, listing_status, province),
    CONSTRAINT fk_cars_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cars_category FOREIGN KEY (category_id) REFERENCES car_categories (id) ON DELETE RESTRICT,
    CONSTRAINT chk_cars_price CHECK (price_per_day > 0),
    CONSTRAINT chk_cars_seats CHECK (seat_count BETWEEN 2 AND 15),
    CONSTRAINT chk_cars_year CHECK (manufacture_year >= 1990)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS car_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    car_id BIGINT NOT NULL,
    image_url VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_car_images_car (car_id),
    CONSTRAINT fk_car_images_car FOREIGN KEY (car_id) REFERENCES cars (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS bookings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    car_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    price_per_day_snapshot BIGINT NOT NULL,
    total_amount BIGINT NOT NULL,
    pickup_address VARCHAR(300) NOT NULL,
    status ENUM('PENDING','CONFIRMED','REJECTED','CANCELLED','COMPLETED') NOT NULL DEFAULT 'PENDING',
    customer_note VARCHAR(500) NULL,
    owner_note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_bookings_car_dates (car_id, status, start_date, end_date),
    KEY idx_bookings_customer (customer_id),
    CONSTRAINT fk_bookings_customer FOREIGN KEY (customer_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_car FOREIGN KEY (car_id) REFERENCES cars (id) ON DELETE RESTRICT,
    CONSTRAINT chk_bookings_dates CHECK (end_date > start_date),
    CONSTRAINT chk_bookings_total CHECK (price_per_day_snapshot > 0 AND total_amount > 0 AND total_amount = price_per_day_snapshot * DATEDIFF(end_date, start_date))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    booking_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    method ENUM('MOCK_CARD','MOCK_BANK_TRANSFER','CASH_SIMULATED') NOT NULL,
    status ENUM('PENDING','PAID','FAILED','REFUNDED') NOT NULL DEFAULT 'PENDING',
    transaction_reference VARCHAR(50) NOT NULL,
    paid_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_payments_booking_status (booking_id, status),
    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE RESTRICT,
    CONSTRAINT chk_payments_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(150) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    type VARCHAR(50) NOT NULL,
    is_read BIT(1) NOT NULL DEFAULT b'0',
    related_entity_type VARCHAR(30) NULL,
    related_entity_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_notifications_user_read (user_id, is_read, created_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- DDL auto-commits in MySQL; the seed itself is one transaction.
START TRANSACTION;
SET @seed_today = CURRENT_DATE();
SET @admin_hash = '$2a$10$774dawIQIq8licat60zD6.mRCSWKUc4g6TE1GiT0yZ7a75NbG9sam';
SET @demo_hash = '$2a$10$yIjE.Z5IX.LM0ND9cvruB.0KBZdCBQb8/Wl1dY5y0gG/BaoW9WD6a';

INSERT INTO users (id, full_name, email, password_hash, phone, role, status) VALUES
    (1, 'System Admin', 'admin@carrent.local', @admin_hash, '0900000001', 'ADMIN', 'ACTIVE'),
    (2, 'Nguyễn Minh An', 'owner1', @demo_hash, '0900000002', 'OWNER', 'ACTIVE'),
    (3, 'Trần Thu Hà', 'owner2', @demo_hash, '0900000003', 'OWNER', 'ACTIVE'),
    (4, 'Lê Hoàng Nam', 'customer1', @demo_hash, '0900000004', 'CUSTOMER', 'ACTIVE'),
    (5, 'Phạm Ngọc Linh', 'customer2', @demo_hash, '0900000005', 'CUSTOMER', 'ACTIVE'),
    (6, 'Tài khoản ngừng hoạt động', 'inactive', @demo_hash, NULL, 'CUSTOMER', 'INACTIVE'),
    (7, 'Tài khoản bị khóa', 'banned', @demo_hash, NULL, 'CUSTOMER', 'BANNED')
ON DUPLICATE KEY UPDATE id = users.id;

INSERT INTO car_categories (id, name, description) VALUES
    (1, 'SEDAN', 'Xe sedan phù hợp di chuyển hằng ngày.'),
    (2, 'SUV', 'Xe gầm cao cho gia đình và hành trình dài.'),
    (3, 'MPV', 'Xe đa dụng rộng rãi cho nhiều hành khách.'),
    (4, 'LUXURY', 'Xe cao cấp với tiện nghi thoải mái.'),
    (5, 'ECONOMY', 'Xe nhỏ gọn và tiết kiệm chi phí.'),
    (6, 'ELECTRIC', 'Xe điện vận hành êm ái.')
ON DUPLICATE KEY UPDATE id = car_categories.id;

SET @admin_id = (SELECT id FROM users WHERE email = 'admin@carrent.local');
SET @owner1_id = (SELECT id FROM users WHERE email = 'owner1');
SET @owner2_id = (SELECT id FROM users WHERE email = 'owner2');
SET @customer1_id = (SELECT id FROM users WHERE email = 'customer1');
SET @customer2_id = (SELECT id FROM users WHERE email = 'customer2');

INSERT INTO cars (id, owner_id, category_id, brand, model, manufacture_year, license_plate, color, seat_count, transmission, fuel_type, description, price_per_day, province, district, address_detail, approval_status, listing_status, rejection_reason) VALUES
    (1, @owner1_id, (SELECT id FROM car_categories WHERE name='SEDAN'), 'Toyota', 'Vios', 2023, '51A-100.01', 'Trắng', 5, 'AUTOMATIC', 'GASOLINE', 'Xe mẫu: sedan 5 chỗ, phù hợp đi phố.', 600000, 'TP. Hồ Chí Minh', 'Quận 1', '10 Nguyễn Huệ', 'APPROVED', 'ACTIVE', NULL),
    (2, @owner1_id, (SELECT id FROM car_categories WHERE name='SUV'), 'Ford', 'Everest', 2024, '51A-100.02', 'Đen', 7, 'AUTOMATIC', 'DIESEL', 'Xe mẫu: SUV 7 chỗ cho gia đình.', 1100000, 'TP. Hồ Chí Minh', 'Quận 3', '20 Võ Văn Tần', 'APPROVED', 'ACTIVE', NULL),
    (3, @owner2_id, (SELECT id FROM car_categories WHERE name='MPV'), 'Mitsubishi', 'Xpander', 2022, '30A-100.03', 'Bạc', 7, 'MANUAL', 'GASOLINE', 'Xe mẫu đang chờ quản trị viên duyệt.', 800000, 'Hà Nội', 'Cầu Giấy', '30 Trần Thái Tông', 'PENDING', 'ACTIVE', NULL),
    (4, @owner2_id, (SELECT id FROM car_categories WHERE name='LUXURY'), 'Mercedes-Benz', 'C200', 2023, '30A-100.04', 'Xanh', 5, 'AUTOMATIC', 'HYBRID', 'Xe mẫu bị từ chối để kiểm tra giao diện trạng thái.', 2000000, 'Hà Nội', 'Ba Đình', '40 Kim Mã', 'REJECTED', 'INACTIVE', 'Vui lòng bổ sung thông tin giấy tờ xe.'),
    (5, @owner1_id, (SELECT id FROM car_categories WHERE name='ECONOMY'), 'Kia', 'Morning', 2021, '51A-100.05', 'Đỏ', 5, 'MANUAL', 'GASOLINE', 'Xe mẫu đã duyệt nhưng chủ xe đang ngừng đăng tin.', 400000, 'Đà Nẵng', 'Hải Châu', '50 Bạch Đằng', 'APPROVED', 'INACTIVE', NULL),
    (6, @owner2_id, (SELECT id FROM car_categories WHERE name='ELECTRIC'), 'VinFast', 'VF 8', 2024, '30A-100.06', 'Trắng', 5, 'AUTOMATIC', 'ELECTRIC', 'Xe điện mẫu cho các luồng tìm kiếm và thanh toán.', 1200000, 'Hà Nội', 'Nam Từ Liêm', '60 Phạm Hùng', 'APPROVED', 'ACTIVE', NULL)
ON DUPLICATE KEY UPDATE id = cars.id;

INSERT INTO car_images (id, car_id, image_url) VALUES
    (1, 1, '/uploads/seed-car-1.png'), (2, 2, '/uploads/seed-car-2.png'),
    (3, 3, '/uploads/seed-car-3.png'), (4, 4, '/uploads/seed-car-4.png'),
    (5, 5, '/uploads/seed-car-5.png'), (6, 6, '/uploads/seed-car-6.png')
ON DUPLICATE KEY UPDATE id = car_images.id;

INSERT INTO bookings (id, customer_id, car_id, start_date, end_date, price_per_day_snapshot, total_amount, pickup_address, status, customer_note, owner_note, created_at, updated_at) VALUES
    (1, @customer1_id, 1, DATE_ADD(@seed_today, INTERVAL 7 DAY), DATE_ADD(@seed_today, INTERVAL 10 DAY), 600000, 1800000, '10 Nguyễn Huệ, Quận 1, TP. Hồ Chí Minh', 'PENDING', 'Đơn mẫu chờ chủ xe xác nhận.', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    (2, @customer1_id, 2, DATE_ADD(@seed_today, INTERVAL 14 DAY), DATE_ADD(@seed_today, INTERVAL 17 DAY), 1100000, 3300000, '20 Võ Văn Tần, Quận 3, TP. Hồ Chí Minh', 'CONFIRMED', 'Đơn mẫu có thanh toán thất bại rồi thử lại thành công.', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    (3, @customer2_id, 6, DATE_ADD(@seed_today, INTERVAL 21 DAY), DATE_ADD(@seed_today, INTERVAL 23 DAY), 1200000, 2400000, '60 Phạm Hùng, Nam Từ Liêm, Hà Nội', 'CONFIRMED', 'Đơn mẫu chưa thanh toán thành công; dùng thử payment.', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    (4, @customer2_id, 1, DATE_SUB(@seed_today, INTERVAL 10 DAY), DATE_SUB(@seed_today, INTERVAL 7 DAY), 600000, 1800000, '10 Nguyễn Huệ, Quận 1, TP. Hồ Chí Minh', 'COMPLETED', 'Đơn mẫu đã hoàn tất.', NULL, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 12 DAY), DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 6 DAY)),
    (5, @customer2_id, 2, DATE_ADD(@seed_today, INTERVAL 28 DAY), DATE_ADD(@seed_today, INTERVAL 30 DAY), 1100000, 2200000, '20 Võ Văn Tần, Quận 3, TP. Hồ Chí Minh', 'CANCELLED', 'Đơn mẫu đã hủy và hoàn tiền giả lập.', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    (6, @customer1_id, 6, DATE_ADD(@seed_today, INTERVAL 7 DAY), DATE_ADD(@seed_today, INTERVAL 9 DAY), 1200000, 2400000, '60 Phạm Hùng, Nam Từ Liêm, Hà Nội', 'REJECTED', 'Đơn mẫu bị từ chối.', 'Chủ xe không thể bàn giao trong thời gian này.', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE id = bookings.id;

INSERT INTO payments (id, booking_id, amount, method, status, transaction_reference, paid_at, created_at, updated_at) VALUES
    (1, 2, 3300000, 'MOCK_CARD', 'FAILED', 'MCK-1791583200000-000001', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    (2, 2, 3300000, 'MOCK_BANK_TRANSFER', 'PAID', 'MCK-1791583200000-000002', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    (3, 3, 2400000, 'CASH_SIMULATED', 'PENDING', 'MCK-1791583200000-000003', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    (4, 4, 1800000, 'MOCK_CARD', 'PAID', 'MCK-1790546400000-000004', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 11 DAY), DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 11 DAY), DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 11 DAY)),
    (5, 5, 2200000, 'MOCK_BANK_TRANSFER', 'REFUNDED', 'MCK-1791583200000-000005', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    (6, 3, 2400000, 'MOCK_CARD', 'FAILED', 'MCK-1791583200000-000006', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE id = payments.id;

INSERT INTO notifications (id, user_id, title, message, type, is_read, related_entity_type, related_entity_id, created_at) VALUES
    (1, @admin_id, 'Xe mới chờ duyệt', 'Mitsubishi Xpander đang chờ được kiểm duyệt.', 'CAR_PENDING_APPROVAL', b'0', 'CAR', 3, CURRENT_TIMESTAMP(6)),
    (2, @owner1_id, 'Xe đã được duyệt', 'Toyota Vios đã được duyệt và có thể nhận đơn thuê.', 'CAR_APPROVED', b'1', 'CAR', 1, CURRENT_TIMESTAMP(6)),
    (3, @owner2_id, 'Xe chưa được duyệt', 'Mercedes-Benz C200 cần bổ sung thông tin giấy tờ xe.', 'CAR_REJECTED', b'0', 'CAR', 4, CURRENT_TIMESTAMP(6)),
    (4, @owner1_id, 'Có yêu cầu thuê xe mới', 'Toyota Vios có đơn thuê mới đang chờ xác nhận.', 'BOOKING_CREATED', b'0', 'BOOKING', 1, CURRENT_TIMESTAMP(6)),
    (5, @customer1_id, 'Đơn thuê đã được xác nhận', 'Chủ xe đã xác nhận đơn thuê Ford Everest.', 'BOOKING_CONFIRMED', b'1', 'BOOKING', 2, CURRENT_TIMESTAMP(6)),
    (6, @customer1_id, 'Đơn thuê bị từ chối', 'Chủ xe không thể bàn giao VinFast VF 8 trong thời gian đã chọn.', 'BOOKING_REJECTED', b'0', 'BOOKING', 6, CURRENT_TIMESTAMP(6)),
    (7, @owner1_id, 'Đơn thuê đã hủy', 'Khách hàng đã hủy đơn thuê; tiền được hoàn đầy đủ bằng hình thức giả lập.', 'BOOKING_CANCELLED', b'0', 'BOOKING', 5, CURRENT_TIMESTAMP(6)),
    (8, @customer2_id, 'Đơn thuê đã hoàn tất', 'Cảm ơn bạn đã sử dụng Toyota Vios.', 'BOOKING_COMPLETED', b'1', 'BOOKING', 4, DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 6 DAY)),
    (9, @customer1_id, 'Thanh toán thành công', 'Thanh toán giả lập 3.300.000 VND đã được ghi nhận.', 'PAYMENT_PAID', b'1', 'PAYMENT', 2, CURRENT_TIMESTAMP(6)),
    (10, @customer2_id, 'Thanh toán thất bại', 'Lần thanh toán thử nghiệm thất bại; bạn có thể thử lại.', 'PAYMENT_FAILED', b'0', 'PAYMENT', 6, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE id = notifications.id;

COMMIT;

SELECT 'users' AS table_name, COUNT(*) AS row_count FROM users
UNION ALL SELECT 'car_categories', COUNT(*) FROM car_categories
UNION ALL SELECT 'cars', COUNT(*) FROM cars
UNION ALL SELECT 'car_images', COUNT(*) FROM car_images
UNION ALL SELECT 'bookings', COUNT(*) FROM bookings
UNION ALL SELECT 'payments', COUNT(*) FROM payments
UNION ALL SELECT 'notifications', COUNT(*) FROM notifications;
