package com.carrent.payment;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSePaySetupTest {
    private PaymentTestDatabase db;
    private JdbcTemplate jdbc;
    private MockEnvironment environment;
    private LocalSePaySetup setup;

    @BeforeEach
    void prepare() throws Exception {
        db = new PaymentTestDatabase();
        db.bootstrap(Path.of("scriptDB.sql"));
        var source = new DriverManagerDataSource(db.url, db.username, db.password);
        jdbc = new JdbcTemplate(source);
        environment = new MockEnvironment()
                .withProperty("CARRENT_SEPAY_BANK_BIN", "970422")
                .withProperty("CARRENT_SEPAY_ACCOUNT_NUMBER", "000123456")
                .withProperty("CARRENT_SEPAY_OWNER_ID", "2")
                .withProperty("CARRENT_SEPAY_ADMIN_ID", "1")
                .withProperty("CARRENT_PUBLIC_BASE_URL", "https://fixture.ngrok-free.dev/");
        setup = new LocalSePaySetup(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), environment);
    }

    @AfterEach
    void close() throws Exception { if (db != null) db.close(); }

    @Test
    void sharedBankCreatesTwoDisabledChannelsPreservesLeadingZerosAndRerunsWithoutChangingIds() throws Exception {
        var original = db.legacySnapshot();
        var result = setup.configure();
        assertThat(result).hasSize(2);
        assertThat(result).extracting(LocalSePaySetup.Channel::role).containsExactly("OWNER", "ADMIN");
        assertThat(result).allSatisfy(channel -> assertThat(channel.url())
                .isEqualTo("https://fixture.ngrok-free.dev/webhooks/sepay/" + channel.id()));
        assertThat(jdbc.queryForList("SELECT account_number FROM payment_channels", String.class))
                .containsExactly("000123456", "000123456");
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE enabled=0 AND provider='SEPAY' AND purpose='COLLECT'"))
                .isEqualTo(2);
        assertThat(setup.configure()).isEqualTo(result);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isEqualTo(2);
        assertThat(db.legacySnapshot()).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "CARRENT_SEPAY_ACCOUNT_NUMBER|''", "CARRENT_SEPAY_ACCOUNT_NUMBER|123' OR 1=1",
            "CARRENT_SEPAY_ACCOUNT_NUMBER|12345678901234567890",
            "CARRENT_SEPAY_BANK_BIN|MB", "CARRENT_SEPAY_OWNER_ID|1",
            "CARRENT_SEPAY_ADMIN_ID|99999", "CARRENT_PUBLIC_BASE_URL|http://insecure.example",
            "CARRENT_PUBLIC_BASE_URL|https://user:password@example.com"
    })
    void invalidSettingsNeverCreatePartialChannelsOrDiscloseValues(String key, String value) throws Exception {
        environment.setProperty(key, value);
        assertThatThrownBy(setup::configure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CARRENT_");
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isZero();
    }

    @Test
    void existingAccountMismatchRollsBackNewOwnerChannelAndNeverRepointsExistingAdmin() throws Exception {
        db.execute("""
                INSERT INTO payment_channels(user_id,owner_role,provider,purpose,credential_reference,
                    bank_bin,account_number,enabled,created_at,updated_at)
                VALUES(1,'ADMIN','SEPAY','COLLECT','admin-live','970422','999999',b'1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        assertThatThrownBy(setup::configure).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("999999").hasMessageNotContaining("000123456");
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT account_number FROM payment_channels", String.class)).isEqualTo("999999");
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE enabled=1")).isEqualTo(1);
    }

    @Test
    void inactiveCollectorCannotReceiveAProvisionedChannel() throws Exception {
        db.execute("UPDATE users SET status='BANNED' WHERE id=2");
        assertThatThrownBy(setup::configure).isInstanceOf(IllegalStateException.class);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isZero();
    }

    @Test
    void rerunningNeverEnablesOrDisablesAnExistingChannel() throws Exception {
        var result = setup.configure();
        db.execute("UPDATE payment_channels SET enabled=b'1' WHERE owner_role='OWNER'");
        assertThat(setup.configure()).isEqualTo(result);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE owner_role='OWNER' AND enabled=1")).isEqualTo(1);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE owner_role='ADMIN' AND enabled=0")).isEqualTo(1);
    }

    @Test void explicitActivationEnablesOnlyValidatedSePayChannelsAndKeepsPayoutOff() throws Exception {
        environment.setActiveProfiles("sepay-activate");
        environment.setProperty("CARRENT_SEPAY_WEBHOOK_SECRET", "fixture-hmac-secret");
        db.execute("""
                INSERT INTO payment_channels(user_id,owner_role,provider,purpose,credential_reference,enabled,created_at,updated_at)
                VALUES(2,'OWNER','PAYOS','PAYOUT','owner-payout',b'0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        setup.run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE provider='SEPAY' AND enabled=1")).isEqualTo(2);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE provider='PAYOS' AND enabled=1")).isZero();
        setup.run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isEqualTo(3);
    }

    @Test void activationWithoutSecretDoesNotCreateOrEnableAnything() throws Exception {
        environment.setActiveProfiles("sepay-activate");
        assertThatThrownBy(() -> setup.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CARRENT_SEPAY_WEBHOOK_SECRET");
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels")).isZero();
    }

    @Test void activationMismatchRollsBackBothProvisioningAndEnablement() throws Exception {
        setup.configure();
        db.execute("UPDATE payment_channels SET account_number='999999' WHERE credential_reference='admin-live'");
        environment.setActiveProfiles("sepay-activate");
        environment.setProperty("CARRENT_SEPAY_WEBHOOK_SECRET", "fixture-hmac-secret");
        assertThatThrownBy(() -> setup.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.number("SELECT COUNT(*) FROM payment_channels WHERE enabled=1")).isZero();
    }
}
