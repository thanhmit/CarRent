package com.carrent.payment;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CancellationReasonMigrationTest {
    static void migrate(PaymentTestDatabase db) throws Exception {
        db.execute("SET @carrent_cancellation_backup_confirmed=1");
        db.run(Files.readString(Path.of("docs/migrations/2026-11-02-01-cancellation-reason.sql"))
                .replaceAll("(?m)^DELIMITER[^\\r\\n]*", ""), "$$");
    }
    @Test void upgradeRequiresBackupAndPreservesExistingBookingData() throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(Path.of("scriptDB.sql"));
            db.execute("ALTER TABLE bookings DROP COLUMN cancellation_reason");
            long rows = db.number("SELECT COUNT(*) FROM bookings");
            String sql = Files.readString(Path.of("docs/migrations/2026-11-02-01-cancellation-reason.sql"))
                    .replaceAll("(?m)^DELIMITER[^\\r\\n]*", "");
            assertThatThrownBy(() -> db.run(sql, "$$")).rootCause().hasMessageContaining("Backup confirmation");
            db.execute("DROP PROCEDURE IF EXISTS carrent_cancellation_reason_v1");
            db.execute("SET @carrent_cancellation_backup_confirmed=1");
            db.run(sql, "$$");
            assertThat(db.number("SELECT COUNT(*) FROM bookings")).isEqualTo(rows);
            assertThat(db.number("SELECT COUNT(*) FROM bookings WHERE cancellation_reason IS NOT NULL")).isZero();
            assertThatThrownBy(() -> db.run(sql, "$$")).rootCause().hasMessageContaining("already exists");
        }
    }
}
