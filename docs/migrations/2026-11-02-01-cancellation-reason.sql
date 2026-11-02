-- P6.11 upgrade for an existing Phase 6 schema without cancellation_reason.
-- Stop the application and confirm a backup first. Never run at startup.
-- Fresh bootstrap includes this column. Legacy upgrades apply this after Phase 6.
DELIMITER $$
CREATE PROCEDURE carrent_cancellation_reason_v1()
SQL SECURITY INVOKER
BEGIN
    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select the intended schema explicitly';
    END IF;
    IF COALESCE(@carrent_cancellation_backup_confirmed, 0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Backup confirmation is required before cancellation migration';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='bookings' AND column_name='cancellation_reason') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Cancellation reason already exists; inspect schema';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='bookings' AND column_name IN ('workflow_version','cancelled_at','cancellation_fee')) <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Phase 6 schema is required before cancellation migration';
    END IF;
    ALTER TABLE bookings ADD COLUMN cancellation_reason VARCHAR(500) NULL AFTER cancellation_fee;
END$$
CALL carrent_cancellation_reason_v1()$$
DROP PROCEDURE carrent_cancellation_reason_v1$$
DELIMITER ;
