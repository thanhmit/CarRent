package com.carrent.payment;

import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.SePayWebhookVerifier;
import java.time.Clock;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SePaySharedAccountTest {
    static PaymentFlowFixture f;

    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.payment.providers.sepay.owner-fixture.gateway", "MBBank");
        try { f = new PaymentFlowFixture(true, true, SharedWebhook.class); }
        finally { System.clearProperty("carrent.payment.providers.sepay.owner-fixture.gateway"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.reset();
        f.jdbc.update("UPDATE payment_channels SET account_number='000123456' WHERE id=102");
        f.jdbc.update("""
                INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,
                    payer_user_id,collector_user_id,amount_due,amount_settled,status,created_at,updated_at)
                VALUES(202,'SUBSCRIPTION:1','SUBSCRIPTION','SUBSCRIPTION_ORDER',1,2,1,5000,0,'OPEN',
                    '2026-10-30 03:00:00','2026-10-30 03:00:00')
                """);
    }

    @Test void oneWebhookRoutesSubscriptionToAdminWhileRentalKeepsOwnerCollector() throws Exception {
        var rental = f.payments.openAttempt(201L, f.actor(4));
        var subscription = f.payments.openAttempt(202L, f.actor(2));
        receive(101, 91001, subscription.code(), "5000");
        receive(101, 91002, rental.code(), "600000");
        processAll();

        assertThat(f.payments.status(subscription.id(), f.actor(2)).status()).isEqualTo("RECEIVED");
        assertThat(f.payments.status(rental.id(), f.actor(4)).status()).isEqualTo("RECEIVED");
        assertThat(f.jdbc.queryForObject("SELECT channel_id FROM payment_receipts WHERE provider_transaction_id='91001'", Long.class))
                .isEqualTo(102L);
        assertThat(f.jdbc.queryForObject("SELECT channel_id FROM payment_receipts WHERE provider_transaction_id='91002'", Long.class))
                .isEqualTo(101L);
    }

    @Test void replayThroughBothUrlsKeepsOneReceiptAndFirstPayloadEvenWhenCodeChanges() throws Exception {
        var rental = f.payments.openAttempt(201L, f.actor(4));
        var subscription = f.payments.openAttempt(202L, f.actor(2));
        receive(101, 91001, subscription.code(), "5000");
        receive(102, 91001, subscription.code(), "5000");
        receive(101, 91001, rental.code(), "600000");
        processAll();
        assertThat(f.count("webhook_inbox")).isEqualTo(1);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.payments.status(subscription.id(), f.actor(2)).status()).isEqualTo("RECEIVED");
        assertThat(f.payments.status(rental.id(), f.actor(4)).status()).isEqualTo("ACTIVE");
    }

    @Test void concurrentCrossChannelDeliveriesCannotAllocateOneBankTransactionToTwoOrders() throws Exception {
        var rental = f.payments.openAttempt(201L, f.actor(4));
        var subscription = f.payments.openAttempt(202L, f.actor(2));
        var start = new CountDownLatch(1);
        var first = CompletableFuture.runAsync(() -> concurrentReceive(start, 101, subscription.code(), "5000"));
        var second = CompletableFuture.runAsync(() -> concurrentReceive(start, 102, rental.code(), "600000"));
        start.countDown();
        first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        processAll();
        assertThat(f.count("webhook_inbox")).isEqualTo(1);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE status='SETTLED'", Long.class))
                .isEqualTo(1L);
    }

    @ParameterizedTest @ValueSource(strings = {"bank", "account", "secret"})
    void matchingCodeDoesNotGrantAccessToAnotherReceivingAccountOrWebhookSecret(String mismatch) throws Exception {
        var subscription = f.payments.openAttempt(202L, f.actor(2));
        switch (mismatch) {
            case "bank" -> f.jdbc.update("UPDATE payment_channels SET bank_bin='970423' WHERE id=102");
            case "account" -> f.jdbc.update("UPDATE payment_channels SET account_number='999999' WHERE id=102");
            default -> f.jdbc.update("UPDATE payment_channels SET credential_reference='isolated-fixture' WHERE id=102");
        }
        receive(101, 91001, subscription.code(), "5000");
        processAll();
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=202", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REVIEW_REQUIRED");
    }

    @Test void unknownPaymentIsReviewedOnceAcrossBothWebhookUrls() throws Exception {
        receive(101, 91001, null, "5000");
        receive(102, 91001, null, "5000");
        processAll();
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(f.jdbc.queryForObject("SELECT SUM(amount_settled) FROM payment_obligations", Long.class)).isZero();
    }

    @Test void existingIntentStillReceivesMoneyAfterItsCollectorChannelStopsNewCheckout() throws Exception {
        var subscription = f.payments.openAttempt(202L, f.actor(2));
        f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=102");
        receive(101, 91001, subscription.code(), "5000");
        processAll();
        assertThat(f.payments.status(subscription.id(), f.actor(2)).status()).isEqualTo("RECEIVED");
    }

    @Test void tpBankQrPreservesExactAccountAmountAndFullPaymentCode() {
        f.jdbc.update("UPDATE payment_channels SET bank_bin='970423' WHERE provider='SEPAY'");
        var subscription = f.payments.openAttempt(202L, f.actor(2));
        assertThat(subscription.qrUrl()).contains("970423-000123456-compact2.png", "amount=5000", "addInfo=" + subscription.code());
        assertThat(subscription.code()).matches("CR[A-F0-9]{30}");
    }

    @Test void sameAccountNumberAtAnotherBankCannotCreateAnInbox() throws Exception {
        var rental = f.payments.openAttempt(201L, f.actor(4));
        byte[] body = new String(SePayInboxTest.body(91001, rental.code(), "600000"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("MBBank", "TPBank").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON)
                .headers(f.signed(body)).content(body)).andExpect(status().isBadRequest());
        assertThat(f.count("webhook_inbox")).isZero();
        assertThat(f.payments.status(rental.id(), f.actor(4)).status()).isEqualTo("ACTIVE");
    }

    static void concurrentReceive(CountDownLatch start, long channel, String code, String amount) {
        try {
            start.await();
            byte[] body = SePayInboxTest.body(91001, code, amount);
            f.app.getBean(SePayWebhookService.class).receive(channel, f.signed(body), body);
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    static void receive(long channel, long transaction, String code, String amount) throws Exception {
        byte[] body = SePayInboxTest.body(transaction, code, amount);
        f.mvc.perform(post("/webhooks/sepay/" + channel).contentType(MediaType.APPLICATION_JSON)
                .headers(f.signed(body)).content(body)).andExpect(status().isOk());
    }

    static void processAll() {
        for (Long id : f.jdbc.queryForList("SELECT id FROM webhook_inbox ORDER BY id", Long.class)) {
            f.app.getBean(ReceiptProcessor.class).process(id);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SharedWebhook {
        @Bean @Primary SePayWebhookVerifier sharedVerifier(ProviderPaymentProperties properties, Clock clock) {
            var channels = new HashMap<>(properties.sepay());
            channels.put("admin-fixture", new ProviderPaymentProperties.SePayChannel(true, PaymentFlowFixture.SECRET));
            return new SePayWebhookVerifier(new ProviderPaymentProperties(channels, properties.payos()), clock);
        }
    }
}
