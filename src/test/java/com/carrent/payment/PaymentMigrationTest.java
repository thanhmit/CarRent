package com.carrent.payment;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentMigrationTest {
    private static final Path LEGACY = Path.of("src/test/resources/payment/legacy-bootstrap.sql");

    @Test
    void migrationPreservesEveryLegacyRowAndAddsTheSevenFinancialTables() throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            var before = db.legacySnapshot();
            assertThat(db.number("SELECT COUNT(*) FROM payments")).isEqualTo(6);
            db.migrate(true);
            assertThat(db.legacySnapshot()).isEqualTo(before);
            assertThat(db.coreSchema()).hasSize(7);
            for (String table : PaymentTestDatabase.CORE) {
                assertThat(db.number("SELECT COUNT(*) FROM " + table)).isZero();
            }
        }
    }

    @Test
    void freshBootstrapMatchesMigrationAndCanBeRerunWithoutRewritingLegacy() throws Exception {
        try (var migrated = new PaymentTestDatabase(); var fresh = new PaymentTestDatabase()) {
            migrated.bootstrap(LEGACY);
            migrated.migrate(true);
            fresh.bootstrap(Path.of("scriptDB.sql"));
            assertThat(fresh.coreSchema()).isEqualTo(migrated.coreSchema());
            var before = fresh.legacySnapshot();
            fresh.bootstrap(Path.of("scriptDB.sql"));
            assertThat(fresh.legacySnapshot()).isEqualTo(before);
        }
    }

    @Test
    void migrationRequiresAnExplicitBackupConfirmation() throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            var before = db.legacySnapshot();
            assertThatThrownBy(() -> db.migrate(false)).rootCause().hasMessageContaining("Backup confirmation");
            assertNoCoreTables(db);
            assertThat(db.legacySnapshot()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ALTER TABLE payments MODIFY amount DECIMAL(19,0) NOT NULL|source columns",
        "ALTER TABLE payments DROP FOREIGN KEY fk_payments_booking|source foreign keys",
        "ALTER TABLE payments ADD UNIQUE KEY wrong_unique (transaction_reference)|primary or unique keys",
        "ALTER TABLE payments DROP CHECK chk_payments_amount|source CHECK constraints",
        "ALTER TABLE payments DROP CHECK chk_payments_amount, ADD CONSTRAINT chk_payments_amount CHECK (amount >= 0)|source CHECK constraints",
        "RENAME TABLE notifications TO old_notifications|seven InnoDB",
        "SET SESSION sql_mode = ''|strict SQL mode"
    })
    void rejectsIncompatibleSourceBeforeCreatingFinancialTables(String alteration, String message) throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            db.execute(alteration);
            assertThatThrownBy(() -> db.migrate(true)).rootCause().hasMessageContaining(message);
            assertNoCoreTables(db);
        }
    }

    @Test
    void rejectsPartialSchemaAndDoesNotDropOrRepairIt() throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            db.execute("CREATE TABLE payment_channels (id BIGINT PRIMARY KEY)");
            db.execute("INSERT INTO payment_channels VALUES (43)");
            assertThatThrownBy(() -> db.migrate(true)).rootCause().hasMessageContaining("already exists or is partial");
            assertThat(db.number("SELECT id FROM payment_channels")).isEqualTo(43);
            assertThat(db.number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")).isEqualTo(8);
        }
    }

    @Test
    void refusesToReapplyACompletedMigration() throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            db.migrate(true);
            var schema = db.coreSchema();
            assertThatThrownBy(() -> db.migrate(true)).rootCause().hasMessageContaining("already exists or is partial");
            assertThat(db.coreSchema()).isEqualTo(schema);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE payment_obligations SET amount_due=0 WHERE id=201",
        "UPDATE payment_obligations SET amount_settled=-1 WHERE id=201",
        "UPDATE payment_obligations SET amount_settled=600001 WHERE id=201",
        "UPDATE payment_obligations SET subject_type='SUBSCRIPTION_ORDER' WHERE id=201",
        "UPDATE payment_obligations SET business_key='SUBSCRIPTION:1' WHERE id=201",
        "UPDATE payment_obligations SET payer_user_id=collector_user_id WHERE id=201",
        "UPDATE payment_intents SET amount=0 WHERE id=301",
        "UPDATE payment_intents SET expires_at=created_at WHERE id=301",
        "UPDATE payment_receipts SET allocated_amount=600001 WHERE id=501",
        "UPDATE payment_receipts SET channel_id=102 WHERE id=501",
        "UPDATE payment_receipts SET provider_transaction_id='9002' WHERE id=501",
        "UPDATE payment_receipts SET intent_id=999999 WHERE id=501",
        "UPDATE webhook_inbox SET raw_payload=X'' WHERE id=401",
        "UPDATE webhook_inbox SET provider_transaction_id='009001' WHERE id=401",
        "UPDATE webhook_inbox SET provider_transaction_id='uuid-v2' WHERE id=401",
        "UPDATE webhook_inbox SET lease_owner='worker' WHERE id=401",
        "UPDATE refunds SET amount=-1 WHERE id=601",
        "UPDATE refunds SET due_at='2026-10-29 12:00:00' WHERE id=601",
        "UPDATE refunds SET account_number='not-an-account', bank_bin='970422' WHERE id=601",
        "UPDATE payout_attempts SET amount=0 WHERE id=701",
        "UPDATE payout_attempts SET attempt_no=0 WHERE id=701",
        "UPDATE payout_attempts SET bank_bin='abc123' WHERE id=701",
        "UPDATE payment_channels SET provider='PAYOS' WHERE id=101",
        "UPDATE payment_channels SET owner_role='CUSTOMER' WHERE id=101",
        "DELETE FROM payment_receipts WHERE id=501"
    })
    void databaseRejectsInvalidFinancialData(String sql) throws Exception {
        try (var db = populatedDatabase()) {
            assertThatThrownBy(() -> db.execute(sql)).isInstanceOf(java.sql.SQLException.class)
                    .satisfies(error -> assertThat(((java.sql.SQLException) error).getErrorCode())
                            .isIn(3819, 1451, 1452, 1265));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO payment_obligations (business_key,purpose,subject_type,subject_id,payer_user_id,collector_user_id,amount_due,created_at,updated_at) SELECT business_key,purpose,subject_type,subject_id,payer_user_id,collector_user_id,amount_due,created_at,updated_at FROM payment_obligations WHERE id=201",
        "INSERT INTO payment_intents (obligation_id,code,channel_id,amount,created_at,expires_at) SELECT obligation_id,code,channel_id,amount,created_at,expires_at FROM payment_intents WHERE id=301",
        "INSERT INTO webhook_inbox (channel_id,provider_transaction_id,raw_payload,received_at) SELECT channel_id,provider_transaction_id,raw_payload,received_at FROM webhook_inbox WHERE id=401",
        "INSERT INTO payment_receipts (channel_id,provider_transaction_id,inbox_id,amount,credited_at,created_at) SELECT channel_id,provider_transaction_id,inbox_id,amount,credited_at,created_at FROM payment_receipts WHERE id=501",
        "INSERT INTO refunds (business_key,receipt_id,payout_channel_id,recipient_user_id,amount,reason,created_at,updated_at) SELECT business_key,receipt_id,payout_channel_id,recipient_user_id,amount,reason,created_at,updated_at FROM refunds WHERE id=601",
        "INSERT INTO payout_attempts (refund_id,attempt_no,payout_channel_id,reference_id,idempotency_key,amount,bank_bin,account_number,created_at,updated_at) SELECT refund_id,2,payout_channel_id,'another-reference',idempotency_key,amount,bank_bin,account_number,created_at,updated_at FROM payout_attempts WHERE id=701",
        "INSERT INTO payout_attempts (refund_id,attempt_no,payout_channel_id,reference_id,idempotency_key,amount,bank_bin,account_number,created_at,updated_at) SELECT refund_id,2,payout_channel_id,reference_id,'another-key',amount,bank_bin,account_number,created_at,updated_at FROM payout_attempts WHERE id=701",
        "INSERT INTO payout_attempts (refund_id,attempt_no,payout_channel_id,reference_id,idempotency_key,amount,bank_bin,account_number,created_at,updated_at) SELECT refund_id,attempt_no,payout_channel_id,'another-reference','another-key',amount,bank_bin,account_number,created_at,updated_at FROM payout_attempts WHERE id=701"
    })
    void uniqueConstraintsRejectReplayedBusinessAndProviderIdentities(String sql) throws Exception {
        try (var db = populatedDatabase()) {
            assertThatThrownBy(() -> db.execute(sql)).isInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    void supportsSubscriptionSubjectsAndIndependentCollectorTransactionIds() throws Exception {
        try (var db = populatedDatabase()) {
            db.execute("INSERT INTO payment_obligations (business_key,purpose,subject_type,subject_id,payer_user_id,collector_user_id,amount_due,created_at,updated_at) SELECT 'SUBSCRIPTION:87','SUBSCRIPTION','SUBSCRIPTION_ORDER',87,c.user_id,a.id,5000,'2026-10-29 10:00:00','2026-10-29 10:00:00' FROM payment_channels c JOIN users a ON a.role='ADMIN' WHERE c.id=101");
            db.execute("INSERT INTO payment_channels (id,user_id,owner_role,provider,purpose,credential_reference,bank_bin,account_number,created_at,updated_at) SELECT 103,id,'ADMIN','SEPAY','COLLECT','admin-collect','970422','000123456','2026-10-29 10:00:00','2026-10-29 10:00:00' FROM users WHERE role='ADMIN' LIMIT 1");
            db.execute("INSERT INTO webhook_inbox (channel_id,provider_transaction_id,raw_payload,received_at) VALUES (103,'9001',X'00FF','2026-10-29 10:00:00')");
            db.execute("INSERT INTO payment_receipts (channel_id,provider_transaction_id,inbox_id,amount,credited_at,created_at) SELECT 103,'9001',id,5000,'2026-10-29 10:00:00','2026-10-29 10:00:00' FROM webhook_inbox WHERE channel_id=103");
            assertThat(db.number("SELECT COUNT(*) FROM payment_receipts WHERE provider_transaction_id='9001'")).isEqualTo(2);
            assertThat(db.number("SELECT amount_due FROM payment_obligations WHERE business_key='SUBSCRIPTION:87'")).isEqualTo(5000);
        }
    }

    static PaymentTestDatabase populatedDatabase() throws Exception {
        var db = new PaymentTestDatabase();
        try {
            db.bootstrap(LEGACY);
            db.migrate(true);
            db.run("""
                INSERT INTO payment_channels (id,user_id,owner_role,provider,purpose,credential_reference,bank_bin,account_number,created_at,updated_at)
                    SELECT 101,id,'OWNER','SEPAY','COLLECT','owner-collect','970422','000123456','2026-10-29 10:00:00','2026-10-29 10:00:00' FROM users WHERE role='OWNER' ORDER BY id LIMIT 1;
                INSERT INTO payment_channels (id,user_id,owner_role,provider,purpose,credential_reference,created_at,updated_at)
                    SELECT 102,user_id,'OWNER','PAYOS','PAYOUT','owner-payout','2026-10-29 10:00:00','2026-10-29 10:00:00' FROM payment_channels WHERE id=101;
                INSERT INTO payment_obligations (id,business_key,purpose,subject_type,subject_id,payer_user_id,collector_user_id,amount_due,created_at,updated_at)
                    SELECT 201,'BOOKING:1:ADVANCE','ADVANCE','BOOKING',1,u.id,c.user_id,600000,'2026-10-29 10:00:00','2026-10-29 10:00:00' FROM users u JOIN payment_channels c ON c.id=101 WHERE u.role='CUSTOMER' ORDER BY u.id LIMIT 1;
                INSERT INTO payment_intents (id,obligation_id,code,channel_id,amount,created_at,expires_at) VALUES (301,201,'CRTEST01',101,600000,'2026-10-29 10:00:00','2026-10-29 10:15:00');
                INSERT INTO webhook_inbox (id,channel_id,provider_transaction_id,raw_payload,received_at) VALUES (401,101,'9001',X'7B00FF7D','2026-10-29 10:01:00');
                INSERT INTO payment_receipts (id,channel_id,provider_transaction_id,inbox_id,intent_id,amount,allocated_amount,credited_at,created_at,status) VALUES (501,101,'9001',401,301,600000,600000,'2026-10-29 10:00:59','2026-10-29 10:01:00','APPLIED');
                INSERT INTO refunds (id,business_key,receipt_id,payout_channel_id,recipient_user_id,amount,reason,created_at,updated_at)
                    SELECT 601,'DUPLICATE:501',501,102,payer_user_id,600000,'DUPLICATE_PAYMENT','2026-10-29 10:02:00','2026-10-29 10:02:00' FROM payment_obligations WHERE id=201;
                INSERT INTO payout_attempts (id,refund_id,attempt_no,payout_channel_id,reference_id,idempotency_key,amount,bank_bin,account_number,status,created_at,updated_at) VALUES (701,601,1,102,'refund:601:attempt:1','refund-601-attempt-1',600000,'970422','000123456','UNKNOWN','2026-10-29 10:03:00','2026-10-29 10:03:00');
                """, ";");
            return db;
        } catch (Exception failure) {
            db.close();
            throw failure;
        }
    }

    private static void assertNoCoreTables(PaymentTestDatabase db) throws Exception {
        assertThat(db.number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('payment_channels','payment_obligations','payment_intents','webhook_inbox','payment_receipts','refunds','payout_attempts')")).isZero();
    }
}
