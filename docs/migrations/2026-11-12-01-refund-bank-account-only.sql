-- Retain saved bank/account destinations without inventing an account-holder name.
-- Verify a backup, select the intended schema, set @carrent_refund_bank_account_backup_confirmed=1.
-- One-shot, stop on error. The nullable-column change preserves all existing rows.
DELIMITER $$
CREATE PROCEDURE carrent_refund_bank_account_v2()
SQL SECURITY INVOKER
BEGIN
    IF DATABASE() IS NULL OR COALESCE(@carrent_refund_bank_account_backup_confirmed,0) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Select the intended schema and confirm a verified backup';
    END IF;
    IF FIND_IN_SET('STRICT_TRANS_TABLES',@@SESSION.sql_mode)=0 AND FIND_IN_SET('STRICT_ALL_TABLES',@@SESSION.sql_mode)=0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Refund accounts require strict SQL mode';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='refund_recipient_accounts' AND column_name='account_name' AND data_type='varchar'
        AND character_maximum_length=150) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Apply refund-accounts v1 before bank/account-only refunds';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='refund_recipient_accounts' AND column_name='account_name' AND is_nullable='YES') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Bank/account-only migration already applied';
    END IF;
    ALTER TABLE refund_recipient_accounts MODIFY COLUMN account_name VARCHAR(150) NULL;
END$$
CALL carrent_refund_bank_account_v2()$$
DROP PROCEDURE carrent_refund_bank_account_v2$$
DELIMITER ;
