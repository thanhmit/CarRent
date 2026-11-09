package com.carrent.payment;

import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.ProviderPaymentProperties.PayOsChannel;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class LocalPayOsSetupTest {
    private PaymentTestDatabase db;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private MockEnvironment env;
    private ProviderPaymentProperties providers;

    @BeforeEach
    void prepare() throws Exception {
        db = new PaymentTestDatabase();
        db.bootstrap(Path.of("scriptDB.sql"));
        var source = new DriverManagerDataSource(db.url, db.username, db.password);
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        env = new MockEnvironment().withProperty("CARRENT_SEPAY_OWNER_ID", "2")
                .withProperty("CARRENT_SEPAY_ADMIN_ID", "1");
        var configured = new PayOsChannel(false, "fixture-client", "fixture-api-key", "fixture-checksum");
        providers = new ProviderPaymentProperties(Map.of(), Map.of("owner-payout", configured, "admin-payout", configured));
    }

    private LocalPayOsSetup setup() { return new LocalPayOsSetup(jdbc, transactions, env, providers); }

    @AfterEach
    void close() throws Exception { if (db != null) db.close(); }

    @Test
    void provisionsTwoDisabledPayoutMappingsWithSharedCredentialsWithoutChangingLegacyData() throws Exception {
        var original = db.legacySnapshot();
        var result = setup().configure();
        assertThat(result).hasSize(2);
        assertThat(result).extracting(LocalPayOsSetup.Channel::role).containsExactly("OWNER", "ADMIN");
        assertThat(jdbc.queryForList("SELECT credential_reference FROM payment_channels ORDER BY id", String.class))
                .containsExactly("owner-payout", "admin-payout");
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE provider='PAYOS' AND purpose='PAYOUT' AND enabled=0 AND bank_bin IS NULL AND account_number IS NULL"))
                .isEqualTo(2);
        assertThat(setup().configure()).isEqualTo(result);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isEqualTo(2);
        assertThat(db.legacySnapshot()).isEqualTo(original);
    }

    @ParameterizedTest @CsvSource(delimiter = '|', value = {
            "CARRENT_PAYOS_OWNER_ID|1", "CARRENT_PAYOS_ADMIN_ID|2",
            "CARRENT_PAYOS_OWNER_ID|9999", "CARRENT_PAYOS_OWNER_ID|0",
            "CARRENT_PAYOS_OWNER_ID|1' OR 1=1", "CARRENT_PAYOS_ADMIN_ID|9223372036854775808"})
    void invalidCollectorsNeverCreatePartialMappings(String key, String value) throws Exception {
        env.setProperty(key, value);
        assertThatThrownBy(() -> setup().configure()).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(value);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isZero();
    }

    @Test
    void rollsBackNewOwnerMappingInsteadOfRepointingAnExistingAdminReference() throws Exception {
        db.execute("""
                INSERT INTO payment_channels(user_id,owner_role,provider,purpose,credential_reference,enabled,created_at,updated_at)
                VALUES(3,'OWNER','PAYOS','PAYOUT','admin-payout',b'1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        assertThatThrownBy(() -> setup().configure()).isInstanceOf(IllegalStateException.class);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isEqualTo(1);
        assertThat(db.number("SELECT user_id FROM payment_channels")).isEqualTo(3);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE enabled=1")).isEqualTo(1);
    }

    @Test
    void rerunPreservesExistingActivationState() throws Exception {
        var result = setup().configure();
        db.execute("UPDATE payment_channels SET enabled=b'1' WHERE owner_role='OWNER'");
        assertThat(setup().configure()).isEqualTo(result);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE enabled=1")).isEqualTo(1);
    }

    @Test
    void inactiveCollectorCannotBeProvisioned() throws Exception {
        db.execute("UPDATE users SET status='BANNED' WHERE id=2");
        assertThatThrownBy(() -> setup().configure()).isInstanceOf(IllegalStateException.class);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isZero();
    }

    @Test
    void missingAdminCredentialsPreventBothInsertsAndNeverLeakOwnerKeys() throws Exception {
        providers = new ProviderPaymentProperties(Map.of(), Map.of("owner-payout", providers.payos().get("owner-payout")));
        assertThatThrownBy(() -> setup().configure()).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("fixture-api-key").hasMessageNotContaining("fixture-checksum");
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isZero();
    }

    @ParameterizedTest @CsvSource({"carrent.payment.maintenance.enabled", "carrent.rental.deadlines.enabled"})
    void refusesProvisioningWithFinancialSchedulersEnabled(String property) throws Exception {
        env.setProperty(property, "true");
        assertThatThrownBy(() -> setup().configure()).isInstanceOf(IllegalStateException.class);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isZero();
    }
}
