-- CarRent payment-core v1 / P4.3. One-shot, manually operated migration.
-- Read docs/migrations/README.md first. Select the schema with mysql --database.
-- Backup and verify it, then SET @carrent_payment_core_backup_confirmed = 1 in this session.
-- Never use mysql --force: stop at the first error. MySQL DDL implicitly commits.
-- No USE, DROP TABLE, seed, legacy UPDATE or automatic application startup execution.
DELIMITER $$
CREATE PROCEDURE carrent_payment_core_v1()
SQL SECURITY INVOKER
BEGIN
    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select the intended schema explicitly before migration';
    END IF;
    IF COALESCE(@carrent_payment_core_backup_confirmed, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Backup confirmation is required before payment-core migration';
    END IF;
    IF FIND_IN_SET('STRICT_TRANS_TABLES', @@SESSION.sql_mode) = 0 AND FIND_IN_SET('STRICT_ALL_TABLES', @@SESSION.sql_mode) = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Payment core requires strict SQL mode';
    END IF;
    IF VERSION() LIKE '%MariaDB%' OR CAST(SUBSTRING_INDEX(VERSION(), '.', 1) AS UNSIGNED) < 8
       OR (SUBSTRING_INDEX(VERSION(), '.', 2) = '8.0' AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 3), '.', -1) AS UNSIGNED) < 16) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Payment core requires MySQL 8.0.16 or newer with enforced CHECK constraints';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name IN ('users', 'car_categories', 'cars', 'car_images', 'bookings', 'payments', 'notifications') AND table_type = 'BASE TABLE' AND engine = 'InnoDB') <> 7 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy preflight failed: seven InnoDB source tables required';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name IN ('payment_channels', 'payment_obligations', 'payment_intents', 'webhook_inbox', 'payment_receipts', 'refunds', 'payout_attempts')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Payment core already exists or is partial; inspect schema before proceeding';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name IN ('users', 'car_categories', 'cars', 'car_images', 'bookings', 'payments', 'notifications')) <> 70
       OR (SELECT COUNT(*) FROM information_schema.columns c JOIN (
        SELECT 'users' AS table_name, 'id' AS column_name, 'bigint' AS column_type, 'NO' AS is_nullable
        UNION ALL SELECT 'users', 'full_name', 'varchar(100)', 'NO'
        UNION ALL SELECT 'users', 'email', 'varchar(150)', 'NO'
        UNION ALL SELECT 'users', 'password_hash', 'varchar(255)', 'NO'
        UNION ALL SELECT 'users', 'phone', 'varchar(20)', 'YES'
        UNION ALL SELECT 'users', 'avatar_url', 'varchar(255)', 'YES'
        UNION ALL SELECT 'users', 'role', 'enum(''ADMIN'',''CUSTOMER'',''OWNER'')', 'NO'
        UNION ALL SELECT 'users', 'status', 'enum(''ACTIVE'',''INACTIVE'',''BANNED'')', 'NO'
        UNION ALL SELECT 'users', 'created_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'users', 'updated_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'car_categories', 'id', 'bigint', 'NO'
        UNION ALL SELECT 'car_categories', 'name', 'varchar(50)', 'NO'
        UNION ALL SELECT 'car_categories', 'description', 'varchar(255)', 'YES'
        UNION ALL SELECT 'car_categories', 'created_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'cars', 'id', 'bigint', 'NO'
        UNION ALL SELECT 'cars', 'owner_id', 'bigint', 'NO'
        UNION ALL SELECT 'cars', 'category_id', 'bigint', 'NO'
        UNION ALL SELECT 'cars', 'brand', 'varchar(50)', 'NO'
        UNION ALL SELECT 'cars', 'model', 'varchar(50)', 'NO'
        UNION ALL SELECT 'cars', 'manufacture_year', 'int', 'NO'
        UNION ALL SELECT 'cars', 'license_plate', 'varchar(20)', 'YES'
        UNION ALL SELECT 'cars', 'color', 'varchar(30)', 'YES'
        UNION ALL SELECT 'cars', 'seat_count', 'int', 'NO'
        UNION ALL SELECT 'cars', 'transmission', 'enum(''MANUAL'',''AUTOMATIC'')', 'NO'
        UNION ALL SELECT 'cars', 'fuel_type', 'enum(''GASOLINE'',''DIESEL'',''ELECTRIC'',''HYBRID'')', 'NO'
        UNION ALL SELECT 'cars', 'description', 'text', 'YES'
        UNION ALL SELECT 'cars', 'price_per_day', 'bigint', 'NO'
        UNION ALL SELECT 'cars', 'province', 'varchar(100)', 'NO'
        UNION ALL SELECT 'cars', 'district', 'varchar(100)', 'YES'
        UNION ALL SELECT 'cars', 'address_detail', 'varchar(255)', 'YES'
        UNION ALL SELECT 'cars', 'approval_status', 'enum(''PENDING'',''APPROVED'',''REJECTED'')', 'NO'
        UNION ALL SELECT 'cars', 'listing_status', 'enum(''ACTIVE'',''INACTIVE'')', 'NO'
        UNION ALL SELECT 'cars', 'rejection_reason', 'varchar(500)', 'YES'
        UNION ALL SELECT 'cars', 'created_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'cars', 'updated_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'car_images', 'id', 'bigint', 'NO'
        UNION ALL SELECT 'car_images', 'car_id', 'bigint', 'NO'
        UNION ALL SELECT 'car_images', 'image_url', 'varchar(255)', 'NO'
        UNION ALL SELECT 'car_images', 'created_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'bookings', 'id', 'bigint', 'NO'
        UNION ALL SELECT 'bookings', 'customer_id', 'bigint', 'NO'
        UNION ALL SELECT 'bookings', 'car_id', 'bigint', 'NO'
        UNION ALL SELECT 'bookings', 'start_date', 'date', 'NO'
        UNION ALL SELECT 'bookings', 'end_date', 'date', 'NO'
        UNION ALL SELECT 'bookings', 'price_per_day_snapshot', 'bigint', 'NO'
        UNION ALL SELECT 'bookings', 'total_amount', 'bigint', 'NO'
        UNION ALL SELECT 'bookings', 'pickup_address', 'varchar(300)', 'NO'
        UNION ALL SELECT 'bookings', 'status', 'enum(''PENDING'',''CONFIRMED'',''REJECTED'',''CANCELLED'',''COMPLETED'')', 'NO'
        UNION ALL SELECT 'bookings', 'customer_note', 'varchar(500)', 'YES'
        UNION ALL SELECT 'bookings', 'owner_note', 'varchar(500)', 'YES'
        UNION ALL SELECT 'bookings', 'created_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'bookings', 'updated_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'payments', 'id', 'bigint', 'NO'
        UNION ALL SELECT 'payments', 'booking_id', 'bigint', 'NO'
        UNION ALL SELECT 'payments', 'amount', 'bigint', 'NO'
        UNION ALL SELECT 'payments', 'method', 'enum(''MOCK_CARD'',''MOCK_BANK_TRANSFER'',''CASH_SIMULATED'')', 'NO'
        UNION ALL SELECT 'payments', 'status', 'enum(''PENDING'',''PAID'',''FAILED'',''REFUNDED'')', 'NO'
        UNION ALL SELECT 'payments', 'transaction_reference', 'varchar(50)', 'NO'
        UNION ALL SELECT 'payments', 'paid_at', 'datetime(6)', 'YES'
        UNION ALL SELECT 'payments', 'created_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'payments', 'updated_at', 'datetime(6)', 'NO'
        UNION ALL SELECT 'notifications', 'id', 'bigint', 'NO'
        UNION ALL SELECT 'notifications', 'user_id', 'bigint', 'NO'
        UNION ALL SELECT 'notifications', 'title', 'varchar(150)', 'NO'
        UNION ALL SELECT 'notifications', 'message', 'varchar(1000)', 'NO'
        UNION ALL SELECT 'notifications', 'type', 'varchar(50)', 'NO'
        UNION ALL SELECT 'notifications', 'is_read', 'bit(1)', 'NO'
        UNION ALL SELECT 'notifications', 'related_entity_type', 'varchar(30)', 'YES'
        UNION ALL SELECT 'notifications', 'related_entity_id', 'bigint', 'YES'
        UNION ALL SELECT 'notifications', 'created_at', 'datetime(6)', 'NO'
       ) expected ON BINARY c.table_name = BINARY expected.table_name AND BINARY c.column_name = BINARY expected.column_name
         AND BINARY c.column_type = BINARY expected.column_type AND c.is_nullable = expected.is_nullable
       WHERE c.table_schema = DATABASE()) <> 70 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy preflight failed: source columns differ from the approved schema';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name IN ('users', 'car_categories', 'cars', 'car_images', 'bookings', 'payments', 'notifications') AND column_name = 'id' AND column_key = 'PRI' AND extra = 'auto_increment') <> 7
       OR (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name IN ('users', 'car_categories', 'cars', 'car_images', 'bookings', 'payments', 'notifications') AND index_name = 'PRIMARY') <> 7
       OR (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name IN ('users', 'car_categories', 'cars', 'car_images', 'bookings', 'payments', 'notifications') AND non_unique = 0 AND index_name <> 'PRIMARY') <> 2
       OR (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND non_unique = 0
        AND ((table_name = 'users' AND index_name = 'uk_users_email' AND column_name = 'email')
          OR (table_name = 'car_categories' AND index_name = 'uk_car_categories_name' AND column_name = 'name'))) <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy preflight failed: source primary or unique keys differ';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r
        ON r.constraint_schema = k.constraint_schema AND r.table_name = k.table_name AND r.constraint_name = k.constraint_name
        WHERE k.constraint_schema = DATABASE() AND k.referenced_table_schema = DATABASE() AND (
        (k.table_name = 'cars' AND k.constraint_name = 'fk_cars_owner' AND k.column_name = 'owner_id' AND k.referenced_table_name = 'users' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'cars' AND k.constraint_name = 'fk_cars_category' AND k.column_name = 'category_id' AND k.referenced_table_name = 'car_categories' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'car_images' AND k.constraint_name = 'fk_car_images_car' AND k.column_name = 'car_id' AND k.referenced_table_name = 'cars' AND k.referenced_column_name = 'id' AND r.delete_rule = 'CASCADE') OR (k.table_name = 'bookings' AND k.constraint_name = 'fk_bookings_customer' AND k.column_name = 'customer_id' AND k.referenced_table_name = 'users' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'bookings' AND k.constraint_name = 'fk_bookings_car' AND k.column_name = 'car_id' AND k.referenced_table_name = 'cars' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'payments' AND k.constraint_name = 'fk_payments_booking' AND k.column_name = 'booking_id' AND k.referenced_table_name = 'bookings' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'notifications' AND k.constraint_name = 'fk_notifications_user' AND k.column_name = 'user_id' AND k.referenced_table_name = 'users' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT'))) <> 7 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy preflight failed: source foreign keys differ';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.table_constraints t JOIN information_schema.check_constraints c
        ON c.constraint_schema = t.constraint_schema AND c.constraint_name = t.constraint_name
        WHERE t.constraint_schema = DATABASE() AND t.constraint_type = 'CHECK' AND t.enforced = 'YES' AND (
        (t.table_name = 'cars' AND t.constraint_name = 'chk_cars_price' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'price_per_day>0') OR (t.table_name = 'cars' AND t.constraint_name = 'chk_cars_seats' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'seat_countbetween2and15') OR (t.table_name = 'cars' AND t.constraint_name = 'chk_cars_year' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'manufacture_year>=1990') OR (t.table_name = 'bookings' AND t.constraint_name = 'chk_bookings_dates' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'end_date>start_date') OR (t.table_name = 'bookings' AND t.constraint_name = 'chk_bookings_total' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'price_per_day_snapshot>0andtotal_amount>0andtotal_amount=price_per_day_snapshot*to_daysend_date-to_daysstart_date') OR (t.table_name = 'payments' AND t.constraint_name = 'chk_payments_amount' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'amount>0'))) <> 6 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Legacy preflight failed: source CHECK constraints differ';
    END IF;

CREATE TABLE payment_channels (
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

CREATE TABLE payment_obligations (
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

CREATE TABLE payment_intents (
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

CREATE TABLE webhook_inbox (
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

CREATE TABLE payment_receipts (
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

CREATE TABLE refunds (
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

CREATE TABLE payout_attempts (
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
END$$
CALL carrent_payment_core_v1()$$
DROP PROCEDURE carrent_payment_core_v1$$
DELIMITER ;
