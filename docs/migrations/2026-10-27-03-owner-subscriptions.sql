-- P8.1: schema only; no entitlement grants or changes to existing rows.
-- Back up and verify the selected schema, then set @carrent_owner_subscriptions_backup_confirmed=1.
-- One-shot; stop on error, never use mysql --force. DDL implicitly commits.
DELIMITER $$
CREATE PROCEDURE carrent_owner_subscriptions_v1()
SQL SECURITY INVOKER
BEGIN
    IF DATABASE() IS NULL OR COALESCE(@carrent_owner_subscriptions_backup_confirmed, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select the intended schema and confirm a verified backup';
    END IF;
    IF FIND_IN_SET('STRICT_TRANS_TABLES', @@SESSION.sql_mode) = 0 AND FIND_IN_SET('STRICT_ALL_TABLES', @@SESSION.sql_mode) = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Owner subscriptions require strict SQL mode';
    END IF;
    IF VERSION() LIKE '%MariaDB%' OR CAST(SUBSTRING_INDEX(VERSION(), '.', 1) AS UNSIGNED) < 8
       OR (SUBSTRING_INDEX(VERSION(), '.', 2) = '8.0' AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(), '.', 3), '.', -1) AS UNSIGNED) < 16) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Owner subscriptions require MySQL 8.0.16 or newer';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name IN ('users','payment_channels','payment_obligations','payment_intents','webhook_inbox','payment_receipts','refunds','payout_attempts')
        AND table_type='BASE TABLE' AND engine='InnoDB') <> 8 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Apply payment-core v1 before owner subscriptions';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name IN ('users','payment_obligations') AND column_name='id'
        AND column_type='bigint' AND is_nullable='NO' AND column_key='PRI') <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Owner subscriptions require compatible source BIGINT primary keys';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name IN ('subscription_orders','owner_subscriptions')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Owner subscription schema already exists or is partial; inspect before proceeding';
    END IF;
    CREATE TABLE subscription_orders (
        id BIGINT NOT NULL AUTO_INCREMENT,
        owner_id BIGINT NOT NULL,
        plan_code VARCHAR(20) NOT NULL,
        quota_snapshot INT NOT NULL,
        price_snapshot BIGINT NOT NULL,
        duration_days INT NOT NULL,
        obligation_id BIGINT NULL,
        status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        created_at DATETIME(6) NOT NULL,
        updated_at DATETIME(6) NOT NULL,
        expires_at DATETIME(6) NOT NULL,
        applied_at DATETIME(6) NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uk_subscription_orders_obligation (obligation_id),
        KEY idx_subscription_orders_owner_status (owner_id, status),
        KEY idx_subscription_orders_expiry (status, expires_at),
        CONSTRAINT fk_subscription_orders_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT,
        CONSTRAINT fk_subscription_orders_obligation FOREIGN KEY (obligation_id) REFERENCES payment_obligations(id) ON DELETE RESTRICT,
        CONSTRAINT chk_subscription_orders_plan CHECK (plan_code IN ('SMALL','STANDARD','BUSINESS')),
        CONSTRAINT chk_subscription_orders_snapshot CHECK (quota_snapshot > 0 AND price_snapshot > 0 AND duration_days = 30),
        CONSTRAINT chk_subscription_orders_status CHECK (status IN ('PENDING','APPLIED','EXPIRED','CANCELLED','REFUND_PENDING')),
        CONSTRAINT chk_subscription_orders_expiry CHECK (expires_at > created_at),
        CONSTRAINT chk_subscription_orders_applied CHECK (
            (status = 'APPLIED' AND applied_at IS NOT NULL AND obligation_id IS NOT NULL AND applied_at >= created_at)
            OR (status <> 'APPLIED' AND applied_at IS NULL))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    CREATE TABLE owner_subscriptions (
        owner_id BIGINT NOT NULL,
        plan_code VARCHAR(20) NOT NULL,
        quota INT NOT NULL,
        valid_from DATETIME(6) NOT NULL,
        valid_until DATETIME(6) NOT NULL,
        last_applied_order_id BIGINT NOT NULL,
        created_at DATETIME(6) NOT NULL,
        updated_at DATETIME(6) NOT NULL,
        PRIMARY KEY (owner_id),
        UNIQUE KEY uk_owner_subscriptions_last_order (last_applied_order_id),
        CONSTRAINT fk_owner_subscriptions_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT,
        CONSTRAINT fk_owner_subscriptions_order FOREIGN KEY (last_applied_order_id) REFERENCES subscription_orders(id) ON DELETE RESTRICT,
        CONSTRAINT chk_owner_subscriptions_plan CHECK (plan_code IN ('SMALL','STANDARD','BUSINESS')),
        CONSTRAINT chk_owner_subscriptions_quota CHECK (quota > 0),
        CONSTRAINT chk_owner_subscriptions_period CHECK (valid_until > valid_from)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
END$$
CALL carrent_owner_subscriptions_v1()$$
DROP PROCEDURE carrent_owner_subscriptions_v1$$
DELIMITER ;
