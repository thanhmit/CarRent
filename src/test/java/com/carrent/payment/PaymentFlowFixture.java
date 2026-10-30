package com.carrent.payment;

import com.carrent.booking.Booking;
import com.carrent.car.Car;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.servlet.Filter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

final class PaymentFlowFixture implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");
    static final String SECRET = "offline-payment-fixture-secret";
    final PaymentTestDatabase db;
    ConfigurableApplicationContext app;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    MutableClock clock;
    MockMvc mvc;
    PaymentObligationService payments;
    EntityManager em;
    final boolean verifierEnabled;
    final Class<?>[] extraConfiguration;

    PaymentFlowFixture() throws Exception { this(true); }

    PaymentFlowFixture(boolean handlersEnabled) throws Exception { this(handlersEnabled, true); }

    PaymentFlowFixture(boolean handlersEnabled, boolean verifierEnabled, Class<?>... extraConfiguration) throws Exception {
        this.verifierEnabled = verifierEnabled;
        this.extraConfiguration = extraConfiguration;
        db = new PaymentTestDatabase();
        try {
            db.bootstrap(Path.of("scriptDB.sql"));
            boot(handlersEnabled);
        } catch (Throwable ex) {
            db.close();
            throw ex;
        }
    }

    void restart(boolean handlersEnabled) {
        Instant now = clock.instant();
        app.close();
        boot(handlersEnabled);
        clock.at.set(now);
    }

    private void boot(boolean handlersEnabled) {
            app = new SpringApplicationBuilder(com.carrent.CarRentApplication.class, Configuration.class)
                    .sources(extraConfiguration)
                    .run("--server.port=0", "--spring.datasource.url=" + db.url,
                            "--spring.datasource.username=" + db.username, "--spring.datasource.password=" + db.password,
                            "--spring.jpa.hibernate.ddl-auto=validate", "--spring.main.banner-mode=off",
                            "--logging.level.root=WARN", "--logging.level.org.hibernate.SQL=OFF", "--debug=false",
                            "--fixture.payment-handlers.enabled=" + handlersEnabled,
                            "--fixture.refund-verifier.enabled=" + verifierEnabled,
                            "--carrent.payment.providers.payos.owner-payout-fixture.enabled=true",
                            "--carrent.payment.providers.payos.owner-payout-fixture.client-id=fixture-owner",
                            "--carrent.payment.providers.payos.owner-payout-fixture.api-key=fixture-owner-api-key",
                            "--carrent.payment.providers.payos.owner-payout-fixture.checksum-key=fixture-owner-checksum-key",
                            "--carrent.payment.providers.payos.admin-payout-fixture.enabled=true",
                            "--carrent.payment.providers.payos.admin-payout-fixture.client-id=fixture-admin",
                            "--carrent.payment.providers.payos.admin-payout-fixture.api-key=fixture-admin-api-key",
                            "--carrent.payment.providers.payos.admin-payout-fixture.checksum-key=fixture-admin-checksum-key",
                            "--carrent.payment.providers.sepay.owner-fixture.enabled=true",
                            "--carrent.payment.providers.sepay.owner-fixture.webhook-secret=" + SECRET,
                            "--carrent.payment.providers.sepay.admin-fixture.enabled=true",
                            "--carrent.payment.providers.sepay.admin-fixture.webhook-secret=another-fixture-secret");
        jdbc = app.getBean(JdbcTemplate.class);
        tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        clock = app.getBean(MutableClock.class);
        em = app.getBean(EntityManager.class);
        payments = app.getBean(PaymentObligationService.class);
        mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app)
                .addFilters(app.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    void reset() {
        jdbc.update("DELETE FROM refund_recipient_accounts");
        for (String table : new String[]{"payout_attempts", "refunds", "payment_receipts", "webhook_inbox",
                "payment_intents", "payment_obligations", "payment_channels"}) jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM notifications WHERE type IN ('PAYMENT_FIXTURE','REFUND_WAITING_RECIPIENT','REFUND_SUCCEEDED','REFUND_WAITING_FUNDS')");
        jdbc.update("UPDATE bookings SET status='PENDING' WHERE id=1");
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id IN (1,2,4)");
        clock.at.set(NOW);
        app.getBean(FixtureState.class).failApply = false;
        app.getBean(FixtureState.class).verifier = (channel, bank, account) ->
                new com.carrent.payment.provider.RefundDestinationVerifier.Destination(bank, account, "NGUYEN TEST");
        var at = java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        for (long[] row : new long[][]{{101,2}, {102,1}}) jdbc.update("""
                INSERT INTO payment_channels(id,user_id,owner_role,provider,purpose,credential_reference,
                bank_bin,account_number,enabled,created_at,updated_at)
                VALUES(?,?,?,'SEPAY','COLLECT',?,'970422',?,1,?,?)
                """, row[0], row[1], row[1] == 2 ? "OWNER" : "ADMIN",
                row[1] == 2 ? "owner-fixture" : "admin-fixture",
                row[1] == 2 ? "000123456" : "000999999", at, at);
        jdbc.update("""
                INSERT INTO payment_channels(id,user_id,owner_role,provider,purpose,credential_reference,enabled,created_at,updated_at)
                VALUES(103,2,'OWNER','PAYOS','PAYOUT','owner-payout-fixture',1,?,?),(104,1,'ADMIN','PAYOS','PAYOUT','admin-payout-fixture',1,?,?)
                """, at, at, at, at);
        jdbc.update("""
                INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,
                payer_user_id,collector_user_id,amount_due,amount_settled,status,created_at,updated_at)
                VALUES(201,'BOOKING:1:ADVANCE','ADVANCE','BOOKING',1,4,2,600000,0,'OPEN',?,?)
                """, at, at);
    }

    CustomUserPrincipal actor(long id) {
        return tx.execute(s -> CustomUserPrincipal.from(em.find(User.class, id)));
    }

    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    org.springframework.http.HttpHeaders signed(byte[] body) throws Exception {
        return signed(body, SECRET);
    }

    org.springframework.http.HttpHeaders signed(byte[] body, String secret) throws Exception {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-SePay-Timestamp", timestamp);
        headers.set("X-SePay-Signature", "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body)));
        return headers;
    }

    long receive(long transactionId, String code, String amount) throws Exception {
        byte[] body = SePayInboxTest.body(transactionId, code, amount);
        app.getBean(SePayWebhookService.class).receive(101L, signed(body), body);
        return jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?", Long.class,
                Long.toString(transactionId));
    }

    @Override public void close() throws Exception { try { app.close(); } finally { db.close(); } }

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> at = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return at.get(); }
    }

    static final class FixtureState {
        volatile boolean failApply;
        volatile com.carrent.payment.provider.RefundDestinationVerifier verifier;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock fixtureClock() { return new MutableClock(); }
        @Bean FixtureState fixtureState() { return new FixtureState(); }
        @Bean @Primary
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="fixture.refund-verifier.enabled", havingValue="true")
        com.carrent.payment.provider.RefundDestinationVerifier fixtureDestinationVerifier(FixtureState state) {
            return (channel, bank, account) -> {
                if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw new AssertionError("Provider verification must run outside a DB transaction");
                }
                return state.verifier.verify(channel, bank, account);
            };
        }
        @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="fixture.payment-handlers.enabled", havingValue="true")
        PaymentDomainHandler fixtureAdvance(EntityManager em, JdbcTemplate jdbc, FixtureState state) {
            return handler(PaymentSubject.BOOKING_ADVANCE, em, jdbc, state);
        }
        @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="fixture.payment-handlers.enabled", havingValue="true")
        PaymentDomainHandler fixtureBalance(EntityManager em, JdbcTemplate jdbc, FixtureState state) {
            return handler(PaymentSubject.BOOKING_BALANCE, em, jdbc, state);
        }
        @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="fixture.payment-handlers.enabled", havingValue="true")
        PaymentDomainHandler fixtureSubscription(EntityManager em, JdbcTemplate jdbc, FixtureState state) {
            return handler(PaymentSubject.SUBSCRIPTION, em, jdbc, state);
        }
        private PaymentDomainHandler handler(PaymentSubject subject, EntityManager em, JdbcTemplate jdbc,
                                             FixtureState state) {
            return new PaymentDomainHandler() {
                public PaymentSubject subject() { return subject; }
                public void lockSubject(Long id) {
                    if (subject == PaymentSubject.SUBSCRIPTION) {
                        em.refresh(em.find(User.class, 2L), LockModeType.PESSIMISTIC_WRITE);
                    } else {
                        Booking b = em.find(Booking.class, id);
                        em.refresh(em.find(Car.class, b.getCar().getId()), LockModeType.PESSIMISTIC_WRITE);
                        em.refresh(b, LockModeType.PESSIMISTIC_WRITE);
                    }
                }
                public void apply(Long receiptId) {
                    jdbc.update("""
                            INSERT INTO notifications(user_id,title,message,type,is_read,created_at)
                            VALUES(4,'Fixture','Applied receipt','PAYMENT_FIXTURE',0,CURRENT_TIMESTAMP(6))
                            """);
                    if (state.failApply) throw new IllegalStateException("Injected notification failure");
                }
                public void expire(Long intentId) { closeObligation(intentId, PaymentObligationStatus.EXPIRED); }
                public void cancel(Long intentId) {
                    if (subject == PaymentSubject.BOOKING_ADVANCE) {
                        closeObligation(intentId, PaymentObligationStatus.CANCELLED);
                    }
                }
                private void closeObligation(Long id, PaymentObligationStatus status) {
                    if (subject != PaymentSubject.BOOKING_BALANCE) {
                        em.find(PaymentIntent.class, id).getObligation().setStatus(status);
                    }
                }
            };
        }
    }
}
