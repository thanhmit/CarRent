package com.carrent.payment;

import com.carrent.payment.provider.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentFoundationTest {
    static final DurableProvider provider = new DurableProvider();

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void signedWebhookThroughScheduledRefundSurvivesLostPayoutResponseAndRestart(boolean subscription) throws Exception {
        provider.reset();
        try (var f = new PaymentFlowFixture(true, true, true, Configuration.class)) {
            f.reset();
            if (subscription) f.jdbc.update("UPDATE payment_obligations SET business_key='SUBSCRIPTION:1',purpose='SUBSCRIPTION',subject_type='SUBSCRIPTION_ORDER',payer_user_id=2,collector_user_id=1");
            long payer = subscription ? 2 : 4, collector = subscription ? 1 : 2, channel = subscription ? 102 : 101;
            f.app.getBean(PaymentFlowFixture.FixtureState.class).refundUnallocated = true;
            var intent = f.payments.openAttempt(201L, f.actor(payer));
            send(f, channel, 22001, intent.code(), subscription);
            await(() -> assertThat(f.payments.status(intent.id(), f.actor(payer)).status()).isEqualTo("RECEIVED"));
            try (var threads = Executors.newFixedThreadPool(2)) {
                var a = threads.submit(() -> { send(f, channel, 22002, intent.code(), subscription); return null; });
                var b = threads.submit(() -> { send(f, channel, 22002, intent.code(), subscription); return null; });
                a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
            }
            await(() -> assertThat(f.count("refunds")).isEqualTo(1));
            long refund = f.jdbc.queryForObject("SELECT id FROM refunds", Long.class);
            assertThat(f.count("webhook_inbox")).isEqualTo(2);
            assertThat(f.count("payment_receipts")).isEqualTo(2);
            assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201", Long.class)).isEqualTo(600000);
            assertThat(f.payments.status(intent.id(), f.actor(payer)).status()).isEqualTo("REFUND_PENDING");
            f.mvc.perform(post("/payment/refunds/" + refund + "/recipient").with(user(f.actor(payer))).with(csrf())
                    .param("bankBin", "970422").param("accountNumber", "000123456")
                    .param("confirmedAccountName", "NGUYEN TEST").param("confirmed", "true"))
                    .andExpect(status().is3xxRedirection());
            await(() -> {
                assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payout_attempts WHERE status='UNKNOWN'", Long.class)).isEqualTo(1);
                assertThat(provider.creates.get()).isEqualTo(1);
                assertThat(f.jdbc.queryForObject("SELECT lease_owner FROM refunds", String.class)).isNull();
            });
            assertThat(provider.creates.get()).isEqualTo(1);
            assertThat(provider.channel.get()).isEqualTo(subscription ? 104L : 103L);
            var due = f.jdbc.queryForObject("SELECT due_at FROM refunds", java.sql.Timestamp.class);
            f.clock.at.set(f.clock.instant().plusSeconds(3601));
            f.restart(true);
            await(() -> assertThat(f.app.getBean(RefundRecipientService.class).view(refund, f.actor(payer)).status()).isEqualTo(RefundStatus.SUCCEEDED));
            assertThat(provider.creates.get()).isEqualTo(1);
            assertThat(provider.queries.get()).isGreaterThanOrEqualTo(1);
            assertThat(f.count("payout_attempts")).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT due_at FROM refunds", java.sql.Timestamp.class)).isEqualTo(due);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_SUCCEEDED'", Long.class)).isEqualTo(1);
            for (long actor : new long[]{payer, collector}) {
                String json = f.mvc.perform(get("/payment/refunds/" + refund + "/status").with(user(f.actor(actor))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(json).contains("\"status\":\"SUCCEEDED\"", "\"overdue\":false").doesNotContain("000123456", "bankBin", "credential");
            }
            assertThat(f.mvc.perform(get("/payment/finances?view=paid").with(user(f.actor(payer))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .contains("Lần thanh toán gần nhất: Đã hoàn tiền");
        }
    }

    @Test void domainFailureRollsBackReceiptAndRefundTogetherAndRetryCreatesOnlyOneObligation() throws Exception {
        try (var f = new PaymentFlowFixture()) {
            f.reset();
            var intent = f.payments.openAttempt(201L, f.actor(4));
            f.payments.cancelAttempt(intent.id(), f.actor(4));
            var state = f.app.getBean(PaymentFlowFixture.FixtureState.class);
            state.refundUnallocated = true; state.failApply = true;
            long inbox = f.receive(23001, intent.code(), "600000");
            var processor = f.app.getBean(ReceiptProcessor.class);
            processor.process(inbox);
            assertThat(f.count("payment_receipts")).isZero();
            assertThat(f.count("refunds")).isZero();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_RECIPIENT'", Long.class)).isZero();
            state.failApply = false; f.clock.at.set(f.clock.instant().plusSeconds(301));
            processor.process(inbox); processor.process(inbox);
            assertThat(f.count("payment_receipts")).isEqualTo(1);
            assertThat(f.count("refunds")).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds", Long.class)).isEqualTo(600000);
        }
    }

    static void send(PaymentFlowFixture f, long channel, long transaction, String code, boolean subscription) throws Exception {
        byte[] body = SePayInboxTest.body(transaction, code, "600000");
        if (subscription) body = new String(body, StandardCharsets.UTF_8).replace("000123456", "000999999").getBytes(StandardCharsets.UTF_8);
        f.mvc.perform(post("/webhooks/sepay/" + channel).contentType("application/json").content(body)
                .headers(f.signed(body, subscription ? "another-fixture-secret" : PaymentFlowFixture.SECRET)))
                .andExpect(status().isOk());
    }

    static void await(Runnable assertion) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
        while (true) {
            try { assertion.run(); return; }
            catch (AssertionError ex) { if (System.nanoTime() >= end) throw ex; Thread.sleep(50); }
        }
    }

    static class DurableProvider implements PayOsPayoutClient {
        final AtomicInteger creates = new AtomicInteger(), queries = new AtomicInteger();
        final AtomicReference<Long> channel = new AtomicReference<>();
        final AtomicReference<PayoutCommand> accepted = new AtomicReference<>();
        void reset() { creates.set(0); queries.set(0); channel.set(null); accepted.set(null); }
        public PayoutResult create(Long source, PayoutCommand c) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            creates.incrementAndGet(); channel.set(source); accepted.set(c);
            throw new IllegalStateException("Fixture accepted transfer; response lost");
        }
        public PayoutResult get(Long source, String id) { return findByReference(source, accepted.get().referenceId()); }
        public PayoutResult findByReference(Long source, String reference) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(source).isEqualTo(channel.get());
            var c = accepted.get(); assertThat(reference).isEqualTo(c.referenceId()); queries.incrementAndGet();
            return new PayoutResult("foundation-" + c.referenceId(), reference, TransferOutcome.SUCCEEDED, c.bankBin(), c.accountNumber(), c.amount());
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration { @Bean @Primary PayOsPayoutClient foundationProvider() { return provider; } }
}
