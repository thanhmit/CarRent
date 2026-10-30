package com.carrent.payment;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundAccountMigrationTest {
    static final Path MIGRATION = Path.of("docs/migrations/2026-10-30-01-refund-accounts.sql");

    @Test void incrementalMigrationMatchesBootstrapAndPreservesAllExistingRowsAndCoreTables() throws Exception {
        try (var migrated = PaymentMigrationTest.populatedDatabase(); var fresh = new PaymentTestDatabase()) {
            var legacy = migrated.legacySnapshot();
            var core = migrated.coreSchema();
            migrate(migrated, true);
            fresh.bootstrap(Path.of("scriptDB.sql"));
            assertThat(schema(migrated)).isEqualTo(schema(fresh));
            assertThat(migrated.legacySnapshot()).isEqualTo(legacy);
            assertThat(migrated.coreSchema()).isEqualTo(core);
            assertThat(migrated.number("SELECT COUNT(*) FROM refund_recipient_accounts")).isZero();
        }
    }

    @Test void requiresBackupConfirmationAndRefusesToReapplyWithoutChangingData() throws Exception {
        try (var db=PaymentMigrationTest.populatedDatabase()) {
            assertThatThrownBy(() -> migrate(db,false)).rootCause().hasMessageContaining("verified backup");
            assertThat(db.number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='refund_recipient_accounts'"))
                    .isZero();
            db.execute("DROP PROCEDURE carrent_refund_accounts_v1");
            migrate(db,true);
            db.execute("INSERT INTO refund_recipient_accounts VALUES(1,'970422','000123','VERIFIED NAME','2026-10-30 03:00:00')");
            assertThatThrownBy(() -> migrate(db,true)).rootCause().hasMessageContaining("already exist");
            assertThat(db.number("SELECT COUNT(*) FROM refund_recipient_accounts WHERE account_number='000123'"))
                    .isEqualTo(1);
        }
    }

    @Test void requiresThePaymentCoreAndStrictSqlModeBeforeCreatingTheNewTable() throws Exception {
        try (var db=new PaymentTestDatabase()) {
            db.bootstrap(Path.of("src/test/resources/payment/legacy-bootstrap.sql"));
            assertThatThrownBy(() -> migrate(db,true)).rootCause().hasMessageContaining("payment-core v1");
        }
        try (var db=PaymentMigrationTest.populatedDatabase()) {
            db.execute("SET SESSION sql_mode=''");
            assertThatThrownBy(() -> migrate(db,true)).rootCause().hasMessageContaining("strict SQL mode");
        }
    }

    @Test void savedAccountsEnforceRecipientForeignKeyBankFormatAndNonblankName() throws Exception {
        try (var db=PaymentMigrationTest.populatedDatabase()) {
            migrate(db,true);
            for(String row : new String[]{"999999,'970422','123','NAME'", "1,'abc123','123','NAME'",
                    "1,'970422','12 3','NAME'", "1,'970422','123','   '"}) {
                assertThatThrownBy(() -> db.execute("INSERT INTO refund_recipient_accounts VALUES("+row+",'2026-10-30 03:00:00')"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            assertThat(db.number("SELECT COUNT(*) FROM refund_recipient_accounts")).isZero();
        }
    }

    static void migrate(PaymentTestDatabase db, boolean confirmed) throws Exception {
        db.execute("SET @carrent_refund_accounts_backup_confirmed=" + (confirmed ? "1" : "NULL"));
        db.run(Files.readString(MIGRATION).replaceAll("(?m)^DELIMITER[^\\r\\n]*", ""), "$$");
    }

    static String schema(PaymentTestDatabase db) throws Exception {
        try (var s=db.connection.createStatement(); var rows=s.executeQuery("SHOW CREATE TABLE refund_recipient_accounts")) {
            rows.next(); return rows.getString(2);
        }
    }
}
