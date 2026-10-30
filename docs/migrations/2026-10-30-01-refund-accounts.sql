-- P4.7: optional saved recipient accounts, after payment-core v1.
-- Back up and verify the selected schema, then set @carrent_refund_accounts_backup_confirmed=1.
-- One-shot; stop on error, never use mysql --force. DDL implicitly commits.
DELIMITER $$
CREATE PROCEDURE carrent_refund_accounts_v1()
SQL SECURITY INVOKER
BEGIN
    IF DATABASE() IS NULL OR COALESCE(@carrent_refund_accounts_backup_confirmed, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select the intended schema and confirm a verified backup';
    END IF;
    IF FIND_IN_SET('STRICT_TRANS_TABLES', @@SESSION.sql_mode) = 0 AND FIND_IN_SET('STRICT_ALL_TABLES', @@SESSION.sql_mode) = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refund accounts require strict SQL mode';
    END IF;
    IF VERSION() LIKE '%MariaDB%' OR CAST(SUBSTRING_INDEX(VERSION(), '.', 1) AS UNSIGNED) < 8
       OR (SUBSTRING_INDEX(VERSION(), '.', 2) = '8.0' AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 3), '.', -1) AS UNSIGNED) < 16) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refund accounts require MySQL 8.0.16 or newer';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name IN ('users','payment_channels','payment_obligations','payment_intents','webhook_inbox','payment_receipts','refunds','payout_attempts')
        AND table_type='BASE TABLE' AND engine='InnoDB') <> 8 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Apply payment-core v1 before refund accounts';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='refund_recipient_accounts') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refund accounts already exist; inspect schema before proceeding';
    END IF;
    CREATE TABLE refund_recipient_accounts (
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
END$$
CALL carrent_refund_accounts_v1()$$
DROP PROCEDURE carrent_refund_accounts_v1$$
DELIMITER ;
