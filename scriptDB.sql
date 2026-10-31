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
    status ENUM('PENDING','CONFIRMED','REJECTED','CANCELLED','COMPLETED','EXPIRED') NOT NULL DEFAULT 'PENDING',
    customer_note VARCHAR(500) NULL,
    owner_note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    workflow_version VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LEGACY',
    inspection_required BOOLEAN NOT NULL DEFAULT FALSE,
    vehicle_received_at DATETIME(6) NULL,
    vehicle_returned_at DATETIME(6) NULL,
    owner_accepted_at DATETIME(6) NULL,
    before_accepted_at DATETIME(6) NULL,
    hold_expires_at DATETIME(6) NULL,
    advance_amount BIGINT NULL,
    balance_amount BIGINT NULL,
    advance_paid_at DATETIME(6) NULL,
    contract_confirmed_by BIGINT NULL,
    contract_confirmed_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    cancellation_fee BIGINT NULL,
    after_reminder_sent_at DATETIME(6) NULL,
    balance_overdue_notified_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_bookings_car_dates (car_id, status, start_date, end_date),
    KEY idx_bookings_customer (customer_id),
    KEY idx_bookings_custody (car_id, inspection_required, vehicle_returned_at, vehicle_received_at),
    CONSTRAINT fk_bookings_customer FOREIGN KEY (customer_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_car FOREIGN KEY (car_id) REFERENCES cars (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_contract_actor FOREIGN KEY (contract_confirmed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_bookings_dates CHECK (end_date > start_date),
    CONSTRAINT chk_bookings_total CHECK (price_per_day_snapshot > 0 AND total_amount > 0 AND total_amount = price_per_day_snapshot * DATEDIFF(end_date, start_date)),
    CONSTRAINT chk_bookings_workflow_version CHECK (workflow_version IN ('LEGACY', 'VIETQR_V1')),
    CONSTRAINT chk_bookings_custody CHECK ((inspection_required = 0 AND vehicle_received_at IS NULL AND vehicle_returned_at IS NULL) OR (inspection_required = 1 AND (vehicle_returned_at IS NULL OR (vehicle_received_at IS NOT NULL AND vehicle_returned_at >= vehicle_received_at)))),
    CONSTRAINT chk_bookings_contract_actor CHECK ((contract_confirmed_by IS NULL AND contract_confirmed_at IS NULL AND vehicle_received_at IS NULL) OR (contract_confirmed_by IS NOT NULL AND contract_confirmed_at IS NOT NULL AND vehicle_received_at IS NOT NULL AND contract_confirmed_at = vehicle_received_at)),
    CONSTRAINT chk_bookings_phase_money CHECK ((advance_amount IS NULL OR advance_amount > 0) AND (balance_amount IS NULL OR balance_amount > 0) AND (cancellation_fee IS NULL OR cancellation_fee >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS booking_inspections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    booking_id BIGINT NOT NULL,
    phase ENUM('BEFORE','AFTER') NOT NULL,
    author_id BIGINT NOT NULL,
    status ENUM('DRAFT','SUBMITTED','ACCEPTED','DISPUTED','RESOLVED') NOT NULL DEFAULT 'DRAFT',
    condition_note VARCHAR(2000) NULL,
    visible_damage VARCHAR(1000) NULL,
    odometer_km INT NULL,
    energy_percent TINYINT UNSIGNED NULL,
    accessories_note VARCHAR(500) NULL,
    submitted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_booking_phase (booking_id, phase),
    KEY idx_inspection_status (status, submitted_at, id),
    CONSTRAINT fk_inspections_booking FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inspections_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_inspection_submission CHECK ((status = 'DRAFT' AND submitted_at IS NULL) OR (status <> 'DRAFT' AND submitted_at IS NOT NULL)),
    CONSTRAINT chk_inspection_odometer CHECK (odometer_km IS NULL OR odometer_km BETWEEN 0 AND 9999999),
    CONSTRAINT chk_inspection_energy CHECK (energy_percent IS NULL OR energy_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS inspection_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inspection_id BIGINT NOT NULL,
    storage_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_type VARCHAR(30) NOT NULL,
    size_bytes INT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_images_storage_key (storage_key),
    KEY idx_inspection_images_inspection (inspection_id, id),
    CONSTRAINT fk_inspection_images_inspection FOREIGN KEY (inspection_id) REFERENCES booking_inspections (id) ON DELETE RESTRICT,
    CONSTRAINT chk_inspection_images_size CHECK (size_bytes BETWEEN 1 AND 5242880),
    CONSTRAINT chk_inspection_images_storage CHECK (storage_key REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.](jpg|png)$' AND ((storage_key LIKE '%.jpg' AND media_type = 'image/jpeg') OR (storage_key LIKE '%.png' AND media_type = 'image/png')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS inspection_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inspection_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    note VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_inspection_events_inspection (inspection_id, created_at, id),
    CONSTRAINT fk_inspection_events_inspection FOREIGN KEY (inspection_id) REFERENCES booking_inspections (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inspection_events_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_inspection_event_action CHECK (action IN ('SUBMITTED', 'PICKUP_CONFIRMED', 'RETURN_CONFIRMED', 'DISPUTED', 'RETURN_NOT_RECEIVED', 'RESOLVED', 'ADDENDUM')),
    CONSTRAINT chk_inspection_event_note CHECK (action IN ('SUBMITTED', 'PICKUP_CONFIRMED', 'RETURN_CONFIRMED') OR (note IS NOT NULL AND CHAR_LENGTH(TRIM(note)) BETWEEN 1 AND 2000))
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

-- Payment-core v1: new records use UTC DATETIME(6) values supplied by the application.
CREATE TABLE IF NOT EXISTS payment_channels (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    owner_role ENUM('OWNER','ADMIN') NOT NULL,
    provider ENUM('SEPAY','PAYOS') NOT NULL,
    purpose ENUM('COLLECT','PAYOUT') NOT NULL,
    credential_reference VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bank_bin VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NULL,
    account_number VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    enabled BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_channels_credential (provider, credential_reference),
    KEY idx_payment_channels_user_purpose (user_id, purpose, enabled),
    CONSTRAINT fk_payment_channels_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_channels_provider CHECK ((provider = 'SEPAY' AND purpose = 'COLLECT') OR (provider = 'PAYOS' AND purpose = 'PAYOUT')),
    CONSTRAINT chk_payment_channels_reference CHECK (CHAR_LENGTH(TRIM(credential_reference)) > 0),
    CONSTRAINT chk_payment_channels_bank CHECK ((bank_bin IS NULL AND account_number IS NULL AND purpose = 'PAYOUT') OR (bank_bin IS NOT NULL AND account_number IS NOT NULL AND bank_bin REGEXP '^[0-9]{6}$' AND account_number REGEXP '^[0-9]{1,50}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_obligations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose ENUM('ADVANCE','BALANCE','SUBSCRIPTION') NOT NULL,
    subject_type ENUM('BOOKING','SUBSCRIPTION_ORDER') NOT NULL,
    subject_id BIGINT NOT NULL,
    payer_user_id BIGINT NOT NULL,
    collector_user_id BIGINT NOT NULL,
    amount_due BIGINT NOT NULL,
    amount_settled BIGINT NOT NULL DEFAULT 0,
    due_at DATETIME(6) NULL,
    status ENUM('OPEN','SETTLED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_obligations_business (business_key),
    KEY idx_payment_obligations_subject (subject_type, subject_id),
    KEY idx_payment_obligations_due (status, due_at),
    CONSTRAINT fk_payment_obligations_payer FOREIGN KEY (payer_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_obligations_collector FOREIGN KEY (collector_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_obligations_money CHECK (amount_due > 0 AND amount_settled >= 0 AND amount_settled <= amount_due),
    CONSTRAINT chk_payment_obligations_subject CHECK (subject_id > 0 AND ((purpose IN ('ADVANCE','BALANCE') AND subject_type = 'BOOKING') OR (purpose = 'SUBSCRIPTION' AND subject_type = 'SUBSCRIPTION_ORDER'))),
    CONSTRAINT chk_payment_obligations_business CHECK (business_key = CASE WHEN purpose = 'SUBSCRIPTION' THEN CONCAT('SUBSCRIPTION:', subject_id) ELSE CONCAT('BOOKING:', subject_id, ':', purpose) END),
    CONSTRAINT chk_payment_obligations_parties CHECK (payer_user_id <> collector_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_intents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    obligation_id BIGINT NOT NULL,
    code VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status ENUM('ACTIVE','RECEIVED','EXPIRED','CANCELLED','REVIEW_REQUIRED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_intents_code (code),
    UNIQUE KEY uk_payment_intents_id_channel (id, channel_id),
    KEY idx_payment_intents_obligation_status (obligation_id, status),
    KEY idx_payment_intents_expiry (status, expires_at),
    CONSTRAINT fk_payment_intents_obligation FOREIGN KEY (obligation_id) REFERENCES payment_obligations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_intents_channel FOREIGN KEY (channel_id) REFERENCES payment_channels (id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_intents_money CHECK (amount > 0),
    CONSTRAINT chk_payment_intents_code CHECK (CHAR_LENGTH(TRIM(code)) > 0),
    CONSTRAINT chk_payment_intents_expiry CHECK (expires_at > created_at AND (cancelled_at IS NULL OR cancelled_at >= created_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS webhook_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    channel_id BIGINT NOT NULL,
    provider_transaction_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    raw_payload LONGBLOB NOT NULL,
    received_at DATETIME(6) NOT NULL,
    status ENUM('RECEIVED','PROCESSING','PROCESSED','REVIEW_REQUIRED') NOT NULL DEFAULT 'RECEIVED',
    lease_owner VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    processed_at DATETIME(6) NULL,
    last_error VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_inbox_transaction (channel_id, provider_transaction_id),
    UNIQUE KEY uk_webhook_inbox_identity (id, channel_id, provider_transaction_id),
    KEY idx_webhook_inbox_retry (status, next_retry_at, lease_until),
    CONSTRAINT fk_webhook_inbox_channel FOREIGN KEY (channel_id) REFERENCES payment_channels (id) ON DELETE RESTRICT,
    CONSTRAINT chk_webhook_inbox_transaction CHECK (provider_transaction_id REGEXP '^[1-9][0-9]*$'),
    CONSTRAINT chk_webhook_inbox_payload CHECK (OCTET_LENGTH(raw_payload) > 0),
    CONSTRAINT chk_webhook_inbox_retry CHECK (retry_count >= 0),
    CONSTRAINT chk_webhook_inbox_lease CHECK ((lease_owner IS NULL AND lease_until IS NULL) OR (lease_owner IS NOT NULL AND CHAR_LENGTH(TRIM(lease_owner)) > 0 AND lease_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    channel_id BIGINT NOT NULL,
    provider_transaction_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    inbox_id BIGINT NOT NULL,
    intent_id BIGINT NULL,
    amount BIGINT NOT NULL,
    allocated_amount BIGINT NOT NULL DEFAULT 0,
    credited_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status ENUM('APPLIED','REFUND_REQUIRED','REVIEW_REQUIRED') NOT NULL DEFAULT 'REVIEW_REQUIRED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_receipts_transaction (channel_id, provider_transaction_id),
    UNIQUE KEY uk_payment_receipts_inbox (inbox_id),
    CONSTRAINT fk_payment_receipts_inbox FOREIGN KEY (inbox_id, channel_id, provider_transaction_id) REFERENCES webhook_inbox (id, channel_id, provider_transaction_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_receipts_intent FOREIGN KEY (intent_id, channel_id) REFERENCES payment_intents (id, channel_id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_receipts_money CHECK (amount > 0 AND allocated_amount >= 0 AND allocated_amount <= amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_key VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_id BIGINT NOT NULL,
    payout_channel_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    reason ENUM('CUSTOMER_CANCEL','OWNER_CANCEL','LATE_ADVANCE','SYSTEM_CONFIRMATION_DELAY','DUPLICATE_PAYMENT','EXCESS_BALANCE','LATE_SUBSCRIPTION','CANCELLED_SUBSCRIPTION','INCOMPATIBLE_SUBSCRIPTION') NOT NULL,
    bank_bin VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NULL,
    account_number VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    account_name VARCHAR(150) NULL,
    ready_at DATETIME(6) NULL,
    due_at DATETIME(6) NULL,
    status ENUM('WAITING_RECIPIENT','READY','WAITING_FUNDS','PROCESSING','SUCCEEDED','REVIEW_REQUIRED') NOT NULL DEFAULT 'WAITING_RECIPIENT',
    lease_owner VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    next_retry_at DATETIME(6) NULL,
    overdue_notified_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refunds_business (business_key),
    KEY idx_refunds_receipt (receipt_id),
    KEY idx_refunds_retry (status, next_retry_at, lease_until),
    KEY idx_refunds_due (status, due_at),
    CONSTRAINT fk_refunds_receipt FOREIGN KEY (receipt_id) REFERENCES payment_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_channel FOREIGN KEY (payout_channel_id) REFERENCES payment_channels (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_recipient FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_refunds_money CHECK (amount > 0),
    CONSTRAINT chk_refunds_business CHECK (CHAR_LENGTH(TRIM(business_key)) > 0),
    CONSTRAINT chk_refunds_bank CHECK ((bank_bin IS NULL AND account_number IS NULL) OR (bank_bin IS NOT NULL AND account_number IS NOT NULL AND bank_bin REGEXP '^[0-9]{6}$' AND account_number REGEXP '^[0-9]{1,50}$')),
    CONSTRAINT chk_refunds_deadline CHECK ((ready_at IS NULL AND due_at IS NULL) OR (ready_at IS NOT NULL AND due_at IS NOT NULL AND due_at = ready_at + INTERVAL 1 HOUR)),
    CONSTRAINT chk_refunds_lease CHECK ((lease_owner IS NULL AND lease_until IS NULL) OR (lease_owner IS NOT NULL AND CHAR_LENGTH(TRIM(lease_owner)) > 0 AND lease_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payout_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    payout_channel_id BIGINT NOT NULL,
    reference_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_payout_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    amount BIGINT NOT NULL,
    bank_bin VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_number VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_name VARCHAR(150) NULL,
    status ENUM('PREPARED','PROCESSING','UNKNOWN','SUCCEEDED','NOT_SENT') NOT NULL DEFAULT 'PREPARED',
    submitted_at DATETIME(6) NULL,
    resolved_at DATETIME(6) NULL,
    next_reconcile_at DATETIME(6) NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payout_attempts_number (refund_id, attempt_no),
    UNIQUE KEY uk_payout_attempts_reference (reference_id),
    UNIQUE KEY uk_payout_attempts_idempotency (idempotency_key),
    UNIQUE KEY uk_payout_attempts_provider (payout_channel_id, provider_payout_id),
    KEY idx_payout_attempts_reconcile (status, next_reconcile_at),
    CONSTRAINT fk_payout_attempts_refund FOREIGN KEY (refund_id) REFERENCES refunds (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payout_attempts_channel FOREIGN KEY (payout_channel_id) REFERENCES payment_channels (id) ON DELETE RESTRICT,
    CONSTRAINT chk_payout_attempts_money CHECK (amount > 0 AND attempt_no > 0),
    CONSTRAINT chk_payout_attempts_reference CHECK (CHAR_LENGTH(TRIM(reference_id)) > 0 AND CHAR_LENGTH(TRIM(idempotency_key)) > 0 AND (provider_payout_id IS NULL OR CHAR_LENGTH(TRIM(provider_payout_id)) > 0)),
    CONSTRAINT chk_payout_attempts_bank CHECK (bank_bin REGEXP '^[0-9]{6}$' AND account_number REGEXP '^[0-9]{1,50}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refund_recipient_accounts (
    user_id BIGINT NOT NULL,
    bank_bin VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_number VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_name VARCHAR(150) NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_refund_accounts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_refund_accounts_bank CHECK (bank_bin REGEXP '^[0-9]{6}$' AND account_number REGEXP '^[0-9]{1,50}$'),
    CONSTRAINT chk_refund_accounts_name CHECK (CHAR_LENGTH(TRIM(account_name)) > 0)
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
