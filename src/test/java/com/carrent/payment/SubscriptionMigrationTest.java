package com.carrent.payment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionMigrationTest {
    static final Path MIGRATION = Path.of("docs/migrations/2026-10-27-03-owner-subscriptions.sql");

    @Test void incrementalMigrationMatchesBootstrapAndPreservesLegacyAndFinancialRows() throws Exception {
        try (var db=PaymentMigrationTest.populatedDatabase(); var fresh=new PaymentTestDatabase()) {
            var legacy=db.legacySnapshot(); var core=db.coreSchema();
            var financialRows=coreRows(db);
            migrate(db,true);
            fresh.bootstrap(Path.of("scriptDB.sql"));
            assertThat(schema(db)).isEqualTo(schema(fresh));
            assertThat(db.legacySnapshot()).isEqualTo(legacy);
            assertThat(db.coreSchema()).isEqualTo(core);
            assertThat(coreRows(db)).isEqualTo(financialRows);
            assertThat(db.number("SELECT COUNT(*) FROM subscription_orders")).isZero();
            assertThat(db.number("SELECT COUNT(*) FROM owner_subscriptions")).isZero();
            var seeded=fresh.legacySnapshot();
            fresh.bootstrap(Path.of("scriptDB.sql"));
            assertThat(fresh.legacySnapshot()).isEqualTo(seeded);
            assertThat(fresh.number("SELECT COUNT(*) FROM owner_subscriptions")).isZero();
        }
    }

    @Test void requiresBackupAndRefusesCompletedOrPartialSchemaWithoutRepairingIt() throws Exception {
        try (var db=PaymentMigrationTest.populatedDatabase()) {
            var legacy=db.legacySnapshot();
            assertThatThrownBy(() -> migrate(db,false)).rootCause().hasMessageContaining("verified backup");
            assertThat(db.number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('subscription_orders','owner_subscriptions')")).isZero();
            db.execute("DROP PROCEDURE carrent_owner_subscriptions_v1");
            migrate(db,true);
            assertThatThrownBy(() -> migrate(db,true)).rootCause().hasMessageContaining("already exists or is partial");
            assertThat(db.legacySnapshot()).isEqualTo(legacy);
        }
        try (var db=PaymentMigrationTest.populatedDatabase()) {
            db.execute("CREATE TABLE owner_subscriptions (owner_id BIGINT PRIMARY KEY)");
            db.execute("INSERT INTO owner_subscriptions VALUES (2)");
            assertThatThrownBy(() -> migrate(db,true)).rootCause().hasMessageContaining("already exists or is partial");
            assertThat(db.number("SELECT owner_id FROM owner_subscriptions")).isEqualTo(2);
        }
    }

    @Test void requiresPaymentCoreAndStrictModeBeforeCreatingTables() throws Exception {
        try (var db=new PaymentTestDatabase()) {
            db.bootstrap(Path.of("src/test/resources/payment/legacy-bootstrap.sql"));
            assertThatThrownBy(() -> migrate(db,true)).rootCause().hasMessageContaining("payment-core v1");
        }
        try (var db=PaymentMigrationTest.populatedDatabase()) {
            db.execute("SET SESSION sql_mode=''");
            assertThatThrownBy(() -> migrate(db,true)).rootCause().hasMessageContaining("strict SQL mode");
        }
    }

    @Test void rejectsIncompatibleSourceKeyBeforeCreatingEitherTable() throws Exception {
        try (var db=PaymentMigrationTest.populatedDatabase()) {
            db.execute("RENAME TABLE users TO old_users");
            db.execute("CREATE TABLE users (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
            assertThatThrownBy(() -> migrate(db,true)).rootCause().hasMessageContaining("source BIGINT primary keys");
            assertThat(db.number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('subscription_orders','owner_subscriptions')")).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings={
        "UPDATE subscription_orders SET plan_code='UNKNOWN' WHERE id=1",
        "UPDATE subscription_orders SET quota_snapshot=0 WHERE id=1",
        "UPDATE subscription_orders SET price_snapshot=0 WHERE id=1",
        "UPDATE subscription_orders SET duration_days=31 WHERE id=1",
        "UPDATE subscription_orders SET owner_id=999999 WHERE id=1",
        "UPDATE subscription_orders SET obligation_id=999999 WHERE id=1",
        "UPDATE subscription_orders SET expires_at=created_at WHERE id=1",
        "UPDATE subscription_orders SET status='INVALID' WHERE id=1",
        "UPDATE subscription_orders SET status='APPLIED' WHERE id=1",
        "UPDATE subscription_orders SET applied_at=created_at WHERE id=1",
        "UPDATE owner_subscriptions SET quota=0 WHERE owner_id=2",
        "UPDATE owner_subscriptions SET plan_code='UNKNOWN' WHERE owner_id=2",
        "UPDATE owner_subscriptions SET valid_until=valid_from WHERE owner_id=2",
        "UPDATE owner_subscriptions SET last_applied_order_id=999999 WHERE owner_id=2",
        "DELETE FROM subscription_orders WHERE id=2",
        "DELETE FROM users WHERE id=2"
    })
    void databaseRejectsInvalidSnapshotsStateAndEntitlementReferences(String sql) throws Exception {
        try (var db=populated()) {
            assertThatThrownBy(() -> db.execute(sql)).isInstanceOf(SQLException.class);
        }
    }

    @Test void ownerEntitlementAndObligationCanOnlyBeLinkedOnce() throws Exception {
        try (var db=populated()) {
            assertThatThrownBy(() -> db.execute("UPDATE subscription_orders SET obligation_id=202 WHERE id=1"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> db.execute("INSERT INTO owner_subscriptions SELECT * FROM owner_subscriptions WHERE owner_id=2"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> db.execute("INSERT INTO owner_subscriptions SELECT 3,plan_code,quota,valid_from,valid_until,last_applied_order_id,created_at,updated_at FROM owner_subscriptions WHERE owner_id=2"))
                    .isInstanceOf(SQLException.class);
            assertThat(db.number("SELECT COUNT(*) FROM subscription_orders WHERE obligation_id IS NULL")).isEqualTo(1);
        }
    }

    private static PaymentTestDatabase populated() throws Exception {
        var db=PaymentMigrationTest.populatedDatabase();
        try {
            migrate(db,true);
            db.execute("INSERT INTO subscription_orders(id,owner_id,plan_code,quota_snapshot,price_snapshot,duration_days,created_at,updated_at,expires_at) VALUES(1,2,'SMALL',4,5000,30,'2026-11-02 00:00:00','2026-11-02 00:00:00','2026-11-02 00:15:00')");
            db.execute("INSERT INTO subscription_orders(id,owner_id,plan_code,quota_snapshot,price_snapshot,duration_days,created_at,updated_at,expires_at) VALUES(2,2,'SMALL',4,5000,30,'2026-11-02 00:00:00','2026-11-02 00:00:00','2026-11-02 00:15:00')");
            db.execute("INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,collector_user_id,amount_due,created_at,updated_at) VALUES(202,'SUBSCRIPTION:2','SUBSCRIPTION','SUBSCRIPTION_ORDER',2,2,1,5000,'2026-11-02 00:00:00','2026-11-02 00:00:00')");
            db.execute("UPDATE subscription_orders SET obligation_id=202,status='APPLIED',applied_at='2026-11-02 00:01:00',updated_at='2026-11-02 00:01:00' WHERE id=2");
            db.execute("INSERT INTO owner_subscriptions(owner_id,plan_code,quota,valid_from,valid_until,last_applied_order_id,created_at,updated_at) VALUES(2,'SMALL',4,'2026-11-02 00:01:00','2026-12-02 00:01:00',2,'2026-11-02 00:01:00','2026-11-02 00:01:00')");
            return db;
        } catch(Exception error) { db.close(); throw error; }
    }

    static void migrate(PaymentTestDatabase db, boolean confirmed) throws Exception {
        db.execute("SET @carrent_owner_subscriptions_backup_confirmed="+(confirmed ? "1" : "NULL"));
        db.run(Files.readString(MIGRATION).replaceAll("(?m)^DELIMITER[^\\r\\n]*", ""),"$$");
    }

    private static Map<String,String> schema(PaymentTestDatabase db) throws Exception {
        var schema=new LinkedHashMap<String,String>();
        for(String table:new String[]{"subscription_orders","owner_subscriptions"}) {
            try(var s=db.connection.createStatement(); var rows=s.executeQuery("SHOW CREATE TABLE "+table)) {
                rows.next(); schema.put(table,rows.getString(2).replaceAll(" AUTO_INCREMENT=\\d+", ""));
            }
        }
        return schema;
    }

    private static Map<String,java.util.List<String>> coreRows(PaymentTestDatabase db) throws Exception {
        var snapshot=new LinkedHashMap<String,java.util.List<String>>();
        for(String table:PaymentTestDatabase.CORE) {
            var values=new java.util.ArrayList<String>();
            try(var s=db.connection.createStatement(); var rows=s.executeQuery("SELECT * FROM "+table+" ORDER BY id")) {
                while(rows.next()) for(int i=1;i<=rows.getMetaData().getColumnCount();i++) values.add(rows.getString(i));
            }
            snapshot.put(table,values);
        }
        return snapshot;
    }
}
