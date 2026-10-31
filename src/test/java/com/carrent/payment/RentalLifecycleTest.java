package com.carrent.payment;

import com.carrent.booking.BookingRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RentalLifecycleTest {
    private static final Path LEGACY = Path.of("src/test/resources/payment/legacy-bootstrap.sql");
    private static final Path MIGRATION = Path.of("docs/migrations/2026-10-27-02-rental-workflow.sql");
    private static final List<String> RENTAL_TABLES = List.of(
            "booking_inspections", "inspection_images", "inspection_events");

    @Test
    void migrationPreservesLegacyRowsAddsRentalSchemaAndKeepsExistingRowsLegacy() throws Exception {
        try (var db = PaymentMigrationTest.populatedDatabase()) {
            var columns = originalColumns(db);
            var before = snapshot(db, columns);
            var coreBefore = db.coreSchema();
            migrate(db, true);

            assertThat(snapshot(db, columns)).isEqualTo(before);
            assertThat(db.coreSchema()).isEqualTo(coreBefore);
            for (String table : RENTAL_TABLES) assertThat(db.number("SELECT COUNT(*) FROM " + table)).isZero();
            assertThat(db.number("SELECT COUNT(*) FROM bookings")).isEqualTo(6);
            assertThat(db.number("SELECT COUNT(*) FROM bookings WHERE id=1 AND status='PENDING'"
                    + " AND workflow_version='LEGACY' AND inspection_required=0")).isEqualTo(1);
            assertThat(db.number("SELECT COUNT(*) FROM payments")).isEqualTo(6);
            assertThat(db.number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
                    + " AND table_name IN ('booking_inspections','inspection_images','inspection_events')")).isEqualTo(3);
            assertThat(db.number("SELECT COUNT(*) FROM bookings WHERE workflow_version='LEGACY' AND inspection_required=0"
                    + " AND vehicle_received_at IS NULL AND vehicle_returned_at IS NULL AND owner_accepted_at IS NULL"
                    + " AND before_accepted_at IS NULL AND hold_expires_at IS NULL AND advance_amount IS NULL"
                    + " AND balance_amount IS NULL AND advance_paid_at IS NULL AND contract_confirmed_by IS NULL"
                    + " AND contract_confirmed_at IS NULL AND cancelled_at IS NULL AND cancellation_fee IS NULL"
                    + " AND after_reminder_sent_at IS NULL AND balance_overdue_notified_at IS NULL")).isEqualTo(6);
            String ddl = showCreate(db, "bookings");
            assertThat(ddl).contains("EXPIRED", "vehicle_received_at", "vehicle_returned_at",
                    "idx_bookings_custody", "chk_bookings_custody");
            assertThat(showCreate(db, "booking_inspections")).contains("uk_inspection_booking_phase",
                    "chk_inspection_submission", "chk_inspection_odometer", "chk_inspection_energy");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ALTER TABLE bookings MODIFY pickup_address VARCHAR(299) NOT NULL|source contract",
        "ALTER TABLE bookings MODIFY created_at DATETIME(6) NULL|source contract",
        "ALTER TABLE users MODIFY phone VARCHAR(21) NULL|source contract",
        "ALTER TABLE bookings DROP FOREIGN KEY fk_bookings_car|source contract",
        "ALTER TABLE bookings DROP CHECK chk_bookings_dates|source contract",
        "ALTER TABLE bookings DROP CHECK chk_bookings_total, ADD CONSTRAINT chk_bookings_total CHECK(total_amount > 0)|source contract",
        "ALTER TABLE bookings ADD UNIQUE KEY wrong_unique (customer_note)|source contract",
        "ALTER TABLE bookings RENAME INDEX idx_bookings_car_dates TO wrong_dates_index|source contract",
        "RENAME TABLE notifications TO old_notifications|source contract",
        "ALTER TABLE bookings ADD COLUMN workflow_version VARCHAR(20)|source contract",
        "SET SESSION sql_mode=''|strict SQL mode"
    })
    void preflightRejectsSourceDriftWithoutAddingColumnsOrTables(String change, String message) throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            db.migrate(true);
            db.execute(change);
            var before = showCreate(db, "bookings");
            assertThatThrownBy(() -> migrate(db, true)).rootCause().hasMessageContaining(message);
            assertThat(showCreate(db, "bookings")).isEqualTo(before);
            for (String table : RENTAL_TABLES) assertThat(db.number("SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema=DATABASE() AND table_name='" + table + "'")).isZero();
        }
    }

    @Test
    void backupGateAndPartialOrCompletedSchemaNeverRepairOrOverwriteData() throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            db.migrate(true);
            var before = showCreate(db, "bookings");
            assertThatThrownBy(() -> migrate(db, false)).rootCause().hasMessageContaining("Backup confirmation");
            assertThat(showCreate(db, "bookings")).isEqualTo(before);
            db.execute("DROP PROCEDURE carrent_rental_workflow_v1");
            db.execute("CREATE TABLE inspection_events(id BIGINT PRIMARY KEY)");
            db.execute("INSERT INTO inspection_events VALUES(43)");
            assertThatThrownBy(() -> migrate(db, true)).rootCause().hasMessageContaining("already exists or is partial");
            assertThat(db.number("SELECT id FROM inspection_events")).isEqualTo(43);
            assertThat(showCreate(db, "bookings")).isEqualTo(before);
        }
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            assertThatThrownBy(() -> migrate(db, true)).rootCause().hasMessageContaining("payment-core v1");
        }
        try (var db = migratedDatabase()) {
            var before = showCreate(db, "bookings");
            assertThatThrownBy(() -> migrate(db, true)).rootCause().hasMessageContaining("already exists or is partial");
            assertThat(showCreate(db, "bookings")).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE bookings SET inspection_required=1,vehicle_returned_at='2026-10-30 03:00:00' WHERE id=1",
        "UPDATE bookings SET inspection_required=1,vehicle_received_at='2026-10-30 03:00:00',vehicle_returned_at='2026-10-30 02:59:59' WHERE id=1",
        "UPDATE bookings SET contract_confirmed_by=2 WHERE id=1",
        "UPDATE bookings SET contract_confirmed_by=2,contract_confirmed_at='2026-10-30 03:00:00' WHERE id=1",
        "UPDATE bookings SET inspection_required=1,vehicle_received_at='2026-10-30 03:00:00' WHERE id=1",
        "UPDATE bookings SET workflow_version='UNKNOWN' WHERE id=1",
        "UPDATE bookings SET advance_amount=0 WHERE id=1",
        "UPDATE bookings SET balance_amount=-1 WHERE id=1",
        "UPDATE bookings SET cancellation_fee=-1 WHERE id=1",
        "UPDATE booking_inspections SET status='SUBMITTED' WHERE id=101",
        "UPDATE booking_inspections SET submitted_at='2026-10-30 03:00:00' WHERE id=101",
        "UPDATE booking_inspections SET odometer_km=-1 WHERE id=101",
        "UPDATE booking_inspections SET odometer_km=10000000 WHERE id=101",
        "UPDATE booking_inspections SET energy_percent=101 WHERE id=101",
        "UPDATE inspection_images SET size_bytes=0 WHERE id=201",
        "UPDATE inspection_images SET size_bytes=5242881 WHERE id=201",
        "UPDATE inspection_images SET storage_key='../private.png' WHERE id=201",
        "UPDATE inspection_images SET media_type='image/svg+xml' WHERE id=201",
        "UPDATE inspection_events SET action='UNKNOWN' WHERE id=301",
        "UPDATE inspection_events SET action='DISPUTED',note='   ' WHERE id=301",
        "DELETE FROM booking_inspections WHERE id=101",
        "DELETE FROM bookings WHERE id=1"
    })
    void databaseRejectsInvalidRentalData(String sql) throws Exception {
        try (var db = migratedDatabase()) {
            db.run("""
                INSERT INTO booking_inspections(id,booking_id,phase,author_id) VALUES(101,1,'BEFORE',2);
                INSERT INTO inspection_images(id,inspection_id,storage_key,media_type,size_bytes)
                  VALUES(201,101,'04b9af27-f55a-4dbd-9765-7b0303d1eacc.jpg','image/jpeg',1);
                INSERT INTO inspection_events(id,inspection_id,actor_id,action) VALUES(301,101,2,'SUBMITTED');
                """, ";");
            assertThatThrownBy(() -> db.execute(sql)).isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void databaseEnforcesInspectionIdentityForeignKeysAndAllowsDraftsAndSubmission() throws Exception {
        try (var db = migratedDatabase()) {
            db.execute("INSERT INTO booking_inspections(id,booking_id,phase,author_id) VALUES(101,1,'BEFORE',2)");
            assertThatThrownBy(() -> db.execute("INSERT INTO booking_inspections(booking_id,phase,author_id)"
                    + " VALUES(1,'BEFORE',2)")).isInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
            assertThatThrownBy(() -> db.execute("INSERT INTO booking_inspections(booking_id,phase,author_id)"
                    + " VALUES(999999,'AFTER',4)")).isInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
            assertThatThrownBy(() -> db.execute("INSERT INTO inspection_images(inspection_id,storage_key,media_type,size_bytes)"
                    + " VALUES(999999,'04b9af27-f55a-4dbd-9765-7b0303d1eacc.png','image/png',1)"))
                    .isInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
            db.execute("UPDATE booking_inspections SET status='SUBMITTED',submitted_at='2026-10-30 03:00:00',"
                    + "odometer_km=9999999,energy_percent=100 WHERE id=101");
            assertThat(db.number("SELECT COUNT(*) FROM booking_inspections WHERE status='SUBMITTED'")).isEqualTo(1);
            db.execute("UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1,contract_confirmed_by=2,"
                    + "contract_confirmed_at='2026-10-30 03:00:00',vehicle_received_at='2026-10-30 03:00:00',"
                    + "vehicle_returned_at='2026-10-30 03:00:00' WHERE id=1");
        }
    }

    @Test
    void migrationRejectsAnIncompatibleSourceBeforeChangingSchema() throws Exception {
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(LEGACY);
            db.migrate(true);
            db.execute("ALTER TABLE bookings MODIFY total_amount DECIMAL(19,0) NOT NULL");

            assertThatThrownBy(() -> migrate(db, true)).rootCause().hasMessageContaining("source contract");
            assertThat(db.number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
                    + " AND table_name IN ('booking_inspections','inspection_images','inspection_events')")).isZero();
            assertThat(db.number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                    + " AND table_name='bookings' AND column_name='vehicle_received_at'")).isZero();
        }
    }

    @Test
    void bootstrapMatchesMigratedRentalSchemaAndJpaMapsExpired() throws Exception {
        try (var migrated = new PaymentTestDatabase(); var fresh = new PaymentTestDatabase()) {
            migrated.bootstrap(LEGACY);
            migrated.migrate(true);
            migrate(migrated, true);
            fresh.bootstrap(Path.of("scriptDB.sql"));

            for (String table : List.of("bookings", "booking_inspections", "inspection_images", "inspection_events")) {
                assertThat(normalize(showCreate(migrated, table))).isEqualTo(normalize(showCreate(fresh, table)));
            }

            try (var app = new SpringApplicationBuilder(com.carrent.CarRentApplication.class)
                    .run("--server.port=0", "--spring.datasource.url=" + migrated.url,
                            "--spring.datasource.username=" + migrated.username,
                            "--spring.datasource.password=" + migrated.password,
                            "--spring.jpa.hibernate.ddl-auto=validate", "--spring.main.banner-mode=off",
                            "--carrent.payment.maintenance.enabled=false", "--logging.level.root=WARN")) {
                var bookings = app.getBean(BookingRepository.class);
                var tx = new TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                migrated.execute("UPDATE bookings SET status='EXPIRED' WHERE id=1");
                tx.executeWithoutResult(status -> {
                    assertThat(bookings.findById(1L).orElseThrow().getStatus())
                            .isEqualTo(com.carrent.booking.BookingStatus.EXPIRED);
                    assertThat(bookings.findConflictsForUpdate(1L, LocalDate.now().plusDays(7),
                            LocalDate.now().plusDays(10))).isEmpty();
                });
            }
        }
    }

    static void migrate(PaymentTestDatabase db, boolean confirmed) throws Exception {
        db.execute("SET @carrent_rental_workflow_backup_confirmed=" + (confirmed ? "1" : "NULL"));
        db.run(Files.readString(MIGRATION).replaceAll("(?m)^DELIMITER[^\\r\\n]*", ""), "$$");
    }

    static PaymentTestDatabase migratedDatabase() throws Exception {
        var db = new PaymentTestDatabase();
        try {
            db.bootstrap(LEGACY);
            db.migrate(true);
            migrate(db, true);
            return db;
        } catch (Exception failure) {
            db.close();
            throw failure;
        }
    }

    private static Map<String, List<String>> originalColumns(PaymentTestDatabase db) throws Exception {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>(PaymentTestDatabase.LEGACY);
        tables.addAll(PaymentTestDatabase.CORE);
        for (String table : tables) {
            List<String> names = new ArrayList<>();
            try (var s = db.connection.createStatement(); var rows = s.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
                var metadata = rows.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++) names.add(metadata.getColumnName(i));
            }
            columns.put(table, names);
        }
        return columns;
    }

    private static Map<String, String> snapshot(PaymentTestDatabase db, Map<String, List<String>> columns) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (var table : columns.entrySet()) {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var s = db.connection.createStatement(); var rows = s.executeQuery(
                    "SELECT " + String.join(",", table.getValue()) + " FROM " + table.getKey() + " ORDER BY id")) {
                while (rows.next()) for (int i = 1; i <= table.getValue().size(); i++) {
                    String value = rows.getString(i);
                    byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                    hash.update((value == null ? "NULL:" : bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                    hash.update(bytes);
                }
            }
            hashes.put(table.getKey(), HexFormat.of().formatHex(hash.digest()));
        }
        return hashes;
    }

    private static String showCreate(PaymentTestDatabase db, String table) throws Exception {
        try (var statement = db.connection.createStatement(); var rows = statement.executeQuery("SHOW CREATE TABLE " + table)) {
            rows.next();
            return rows.getString(2);
        }
    }

    private static String normalize(String ddl) {
        return ddl.replaceAll(" AUTO_INCREMENT=\\d+", "").replaceAll("\\s+", " ").trim();
    }
}
