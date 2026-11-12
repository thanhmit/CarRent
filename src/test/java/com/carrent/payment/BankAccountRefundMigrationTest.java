package com.carrent.payment;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BankAccountRefundMigrationTest {
    static final Path MIGRATION = Path.of("docs/migrations/2026-11-12-01-refund-bank-account-only.sql");

    @Test void bootstrapAcceptsASavedAccountWithoutInventingAnAccountName() throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(Path.of("scriptDB.sql"));
            db.execute("INSERT INTO refund_recipient_accounts VALUES(4,'970422','0000123456',NULL,'2026-11-12 00:00:00')");
            assertThat(db.number("SELECT COUNT(*) FROM refund_recipient_accounts WHERE account_name IS NULL")).isEqualTo(1);
            assertThatThrownBy(() -> db.execute("UPDATE refund_recipient_accounts SET account_name='   '"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test void migrationPreservesLegacyNamesAndMatchesFreshBootstrap() throws Exception {
        try (var db = PaymentMigrationTest.populatedDatabase(); var fresh = new PaymentTestDatabase()) {
            RefundAccountMigrationTest.migrate(db, true);
            db.execute("INSERT INTO refund_recipient_accounts VALUES(1,'970422','000123','LEGACY NAME','2026-10-30 03:00:00')");
            var legacy = db.legacySnapshot();
            var core = db.coreSchema();
            var row = savedRow(db);
            migrate(db, true);
            fresh.bootstrap(Path.of("scriptDB.sql"));
            assertThat(RefundAccountMigrationTest.schema(db)).isEqualTo(RefundAccountMigrationTest.schema(fresh));
            assertThat(savedRow(db)).isEqualTo(row);
            assertThat(db.legacySnapshot()).isEqualTo(legacy);
            assertThat(db.coreSchema()).isEqualTo(core);
            db.execute("INSERT INTO refund_recipient_accounts VALUES(2,'970436','000987',NULL,'2026-11-12 00:00:00')");
            assertThat(db.number("SELECT COUNT(*) FROM refund_recipient_accounts")).isEqualTo(2);
        }
    }

    @Test void migrationRequiresBackupAndRefusesReapplication() throws Exception {
        try (var db = PaymentMigrationTest.populatedDatabase()) {
            RefundAccountMigrationTest.migrate(db, true);
            String before = RefundAccountMigrationTest.schema(db);
            assertThatThrownBy(() -> migrate(db, false)).rootCause().hasMessageContaining("verified backup");
            assertThat(RefundAccountMigrationTest.schema(db)).isEqualTo(before);
            db.execute("DROP PROCEDURE carrent_refund_bank_account_v2");
            migrate(db, true);
            String after = RefundAccountMigrationTest.schema(db);
            assertThatThrownBy(() -> migrate(db, true)).rootCause().hasMessageContaining("already applied");
            assertThat(RefundAccountMigrationTest.schema(db)).isEqualTo(after);
        }
    }

    static void migrate(PaymentTestDatabase db, boolean confirmed) throws Exception {
        db.execute("SET @carrent_refund_bank_account_backup_confirmed=" + (confirmed ? "1" : "NULL"));
        db.run(Files.readString(MIGRATION).replaceAll("(?m)^DELIMITER[^\\r\\n]*", ""), "$$");
    }

    static String savedRow(PaymentTestDatabase db) throws Exception {
        try (var statement = db.connection.createStatement(); var rows = statement.executeQuery("SELECT CONCAT(bank_bin,':',account_number,':',account_name,':',verified_at) FROM refund_recipient_accounts WHERE user_id=1")) {
            rows.next(); return rows.getString(1);
        }
    }
}
