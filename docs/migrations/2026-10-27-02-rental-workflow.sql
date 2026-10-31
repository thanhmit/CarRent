-- CarRent Phase 6.1 rental workflow migration, MySQL 8.0.16+.
-- Run only after an explicit backup and while the application is stopped.
-- DDL statements below implicitly commit in MySQL; this script never selects a schema,
-- drops or repairs an incompatible source, seeds data, or runs at application startup.
DELIMITER $$
CREATE PROCEDURE carrent_rental_workflow_v1()
SQL SECURITY INVOKER
BEGIN
    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select the intended schema explicitly before rental workflow migration';
    END IF;
    IF COALESCE(@carrent_rental_workflow_backup_confirmed, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Backup confirmation is required before rental workflow migration';
    END IF;
    IF FIND_IN_SET('STRICT_TRANS_TABLES', @@SESSION.sql_mode) = 0
       AND FIND_IN_SET('STRICT_ALL_TABLES', @@SESSION.sql_mode) = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow requires strict SQL mode';
    END IF;
    IF VERSION() LIKE '%MariaDB%' OR CAST(SUBSTRING_INDEX(VERSION(), '.', 1) AS UNSIGNED) < 8
       OR (SUBSTRING_INDEX(VERSION(), '.', 2) = '8.0'
           AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 3), '.', -1) AS UNSIGNED) < 16) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow requires MySQL 8.0.16 or newer with enforced CHECK constraints';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name IN ('payment_channels', 'payment_obligations', 'payment_intents', 'webhook_inbox',
                           'payment_receipts', 'refunds', 'payout_attempts')
        AND table_type = 'BASE TABLE' AND engine = 'InnoDB') <> 7 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow requires payment-core v1 before this migration';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name IN ('booking_inspections', 'inspection_images', 'inspection_events')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow already exists or is partial; inspect schema before proceeding';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name IN ('users', 'car_categories', 'cars', 'car_images', 'bookings', 'payments', 'notifications')
        AND table_type = 'BASE TABLE' AND engine = 'InnoDB') <> 7
       OR (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
           AND table_name IN ('users', 'car_categories', 'cars', 'car_images', 'bookings', 'payments', 'notifications')) <> 70 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow source contract requires the seven approved legacy tables';
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
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow source contract: source columns differ from the approved schema';
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
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow source contract: source primary or unique keys differ';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r
        ON r.constraint_schema = k.constraint_schema AND r.table_name = k.table_name AND r.constraint_name = k.constraint_name
        WHERE k.constraint_schema = DATABASE() AND k.referenced_table_schema = DATABASE() AND (
        (k.table_name = 'cars' AND k.constraint_name = 'fk_cars_owner' AND k.column_name = 'owner_id' AND k.referenced_table_name = 'users' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'cars' AND k.constraint_name = 'fk_cars_category' AND k.column_name = 'category_id' AND k.referenced_table_name = 'car_categories' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'car_images' AND k.constraint_name = 'fk_car_images_car' AND k.column_name = 'car_id' AND k.referenced_table_name = 'cars' AND k.referenced_column_name = 'id' AND r.delete_rule = 'CASCADE') OR (k.table_name = 'bookings' AND k.constraint_name = 'fk_bookings_customer' AND k.column_name = 'customer_id' AND k.referenced_table_name = 'users' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'bookings' AND k.constraint_name = 'fk_bookings_car' AND k.column_name = 'car_id' AND k.referenced_table_name = 'cars' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'payments' AND k.constraint_name = 'fk_payments_booking' AND k.column_name = 'booking_id' AND k.referenced_table_name = 'bookings' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT') OR (k.table_name = 'notifications' AND k.constraint_name = 'fk_notifications_user' AND k.column_name = 'user_id' AND k.referenced_table_name = 'users' AND k.referenced_column_name = 'id' AND r.delete_rule = 'RESTRICT'))) <> 7 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow source contract: source foreign keys differ';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.table_constraints t JOIN information_schema.check_constraints c
        ON c.constraint_schema = t.constraint_schema AND c.constraint_name = t.constraint_name
        WHERE t.constraint_schema = DATABASE() AND t.constraint_type = 'CHECK' AND t.enforced = 'YES' AND (
        (t.table_name = 'cars' AND t.constraint_name = 'chk_cars_price' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'price_per_day>0') OR (t.table_name = 'cars' AND t.constraint_name = 'chk_cars_seats' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'seat_countbetween2and15') OR (t.table_name = 'cars' AND t.constraint_name = 'chk_cars_year' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'manufacture_year>=1990') OR (t.table_name = 'bookings' AND t.constraint_name = 'chk_bookings_dates' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'end_date>start_date') OR (t.table_name = 'bookings' AND t.constraint_name = 'chk_bookings_total' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'price_per_day_snapshot>0andtotal_amount>0andtotal_amount=price_per_day_snapshot*to_daysend_date-to_daysstart_date') OR (t.table_name = 'payments' AND t.constraint_name = 'chk_payments_amount' AND REGEXP_REPLACE(LOWER(c.check_clause), '[[:space:]`()]+', '') = 'amount>0'))) <> 6 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow source contract: source CHECK constraints differ';
    END IF;

    IF (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics WHERE table_schema = DATABASE()
          AND table_name = 'bookings' AND index_name = 'idx_bookings_car_dates' AND non_unique = 1)
          IS NULL
       OR (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        FROM information_schema.statistics WHERE table_schema = DATABASE()
          AND table_name = 'bookings' AND index_name = 'idx_bookings_car_dates' AND non_unique = 1)
          <> 'car_id,status,start_date,end_date' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rental workflow source contract: booking conflict index differs';
    END IF;

    ALTER TABLE bookings
        MODIFY status ENUM('PENDING','CONFIRMED','REJECTED','CANCELLED','COMPLETED','EXPIRED') NOT NULL DEFAULT 'PENDING',
        ADD COLUMN workflow_version VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LEGACY',
        ADD COLUMN inspection_required BOOLEAN NOT NULL DEFAULT FALSE,
        ADD COLUMN vehicle_received_at DATETIME(6) NULL,
        ADD COLUMN vehicle_returned_at DATETIME(6) NULL,
        ADD COLUMN owner_accepted_at DATETIME(6) NULL,
        ADD COLUMN before_accepted_at DATETIME(6) NULL,
        ADD COLUMN hold_expires_at DATETIME(6) NULL,
        ADD COLUMN advance_amount BIGINT NULL,
        ADD COLUMN balance_amount BIGINT NULL,
        ADD COLUMN advance_paid_at DATETIME(6) NULL,
        ADD COLUMN contract_confirmed_by BIGINT NULL,
        ADD COLUMN contract_confirmed_at DATETIME(6) NULL,
        ADD COLUMN cancelled_at DATETIME(6) NULL,
        ADD COLUMN cancellation_fee BIGINT NULL,
        ADD COLUMN after_reminder_sent_at DATETIME(6) NULL,
        ADD COLUMN balance_overdue_notified_at DATETIME(6) NULL,
        ADD KEY idx_bookings_custody (car_id, inspection_required, vehicle_returned_at, vehicle_received_at),
        ADD CONSTRAINT fk_bookings_contract_actor FOREIGN KEY (contract_confirmed_by) REFERENCES users (id) ON DELETE RESTRICT,
        ADD CONSTRAINT chk_bookings_workflow_version CHECK (workflow_version IN ('LEGACY', 'VIETQR_V1')),
        ADD CONSTRAINT chk_bookings_custody CHECK (
            (inspection_required = 0 AND vehicle_received_at IS NULL AND vehicle_returned_at IS NULL)
            OR (inspection_required = 1 AND (vehicle_returned_at IS NULL
                OR (vehicle_received_at IS NOT NULL AND vehicle_returned_at >= vehicle_received_at)))
        ),
        ADD CONSTRAINT chk_bookings_contract_actor CHECK (
            (contract_confirmed_by IS NULL AND contract_confirmed_at IS NULL AND vehicle_received_at IS NULL)
            OR (contract_confirmed_by IS NOT NULL AND contract_confirmed_at IS NOT NULL
                AND vehicle_received_at IS NOT NULL AND contract_confirmed_at = vehicle_received_at)
        ),
        ADD CONSTRAINT chk_bookings_phase_money CHECK (
            (advance_amount IS NULL OR advance_amount > 0)
            AND (balance_amount IS NULL OR balance_amount > 0)
            AND (cancellation_fee IS NULL OR cancellation_fee >= 0)
        );

    CREATE TABLE booking_inspections (
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
        CONSTRAINT chk_inspection_submission CHECK (
            (status = 'DRAFT' AND submitted_at IS NULL) OR (status <> 'DRAFT' AND submitted_at IS NOT NULL)
        ),
        CONSTRAINT chk_inspection_odometer CHECK (odometer_km IS NULL OR odometer_km BETWEEN 0 AND 9999999),
        CONSTRAINT chk_inspection_energy CHECK (energy_percent IS NULL OR energy_percent BETWEEN 0 AND 100)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE inspection_images (
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
        CONSTRAINT chk_inspection_images_storage CHECK (
            storage_key REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.](jpg|png)$'
            AND ((storage_key LIKE '%.jpg' AND media_type = 'image/jpeg')
                 OR (storage_key LIKE '%.png' AND media_type = 'image/png'))
        )
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE inspection_events (
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
        CONSTRAINT chk_inspection_event_action CHECK (action IN (
            'SUBMITTED', 'PICKUP_CONFIRMED', 'RETURN_CONFIRMED', 'DISPUTED',
            'RETURN_NOT_RECEIVED', 'RESOLVED', 'ADDENDUM')),
        CONSTRAINT chk_inspection_event_note CHECK (
            action IN ('SUBMITTED', 'PICKUP_CONFIRMED', 'RETURN_CONFIRMED')
            OR (note IS NOT NULL AND CHAR_LENGTH(TRIM(note)) BETWEEN 1 AND 2000)
        )
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
END$$
CALL carrent_rental_workflow_v1()$$
DROP PROCEDURE carrent_rental_workflow_v1$$
DELIMITER ;
