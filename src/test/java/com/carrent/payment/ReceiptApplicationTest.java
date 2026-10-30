package com.carrent.payment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ReceiptApplicationTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); }

    @Test void receivedMoneyIsAppliedExactlyOnceAndPollingReadsCommittedResult() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        long inboxId = f.receive(9001, view.code(), "600000");
        var processor = f.app.getBean(ReceiptProcessor.class);
        processor.process(inboxId);
        processor.process(inboxId);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201", Long.class))
                .isEqualTo(600000L);
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts", Long.class)).isEqualTo(600000L);
        assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox", String.class)).isEqualTo("PROCESSED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='PAYMENT_FIXTURE'", Long.class))
                .isEqualTo(1);
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("RECEIVED");
    }

    @ParameterizedTest @ValueSource(strings = {"500000", "600001"})
    void mismatchedAdvanceRemainsReviewWithoutAllocationOrDomainBenefit(String amount) throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, view.code(), amount));
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201", Long.class)).isZero();
        assertThat(f.count("refunds")).isZero();
        assertThat(notifications()).isZero();
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test void sameAmountAndCodeWithDifferentBankTransactionsRequiresRefundOfSecondReceipt() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        var processor = f.app.getBean(ReceiptProcessor.class);
        processor.process(f.receive(9001, view.code(), "600000"));
        processor.process(f.receive(9002, view.code(), "600000"));
        assertThat(f.count("payment_receipts")).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts", Long.class)).isEqualTo(600000L);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts WHERE provider_transaction_id='9002'", String.class))
                .isEqualTo("REFUND_REQUIRED");
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("REFUND_PENDING");
        assertThat(f.count("refunds")).isZero(); // P4.7/Phase 6 own the actual refund obligation.
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "expiry", "bank-late"})
    void cancelledOrExpiredAttemptCannotBeResurrectedByIncomingMoney(String action) throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        long inbox = f.receive(9001, view.code(), "600000");
        if (action.equals("cancel")) f.payments.cancelAttempt(view.id(), f.actor(4));
        else {
            f.clock.at.set(view.expiresAt());
            if (action.equals("expiry")) f.app.getBean(ReceiptProcessor.class).expire(view.id());
            else {
                f.jdbc.update("DELETE FROM webhook_inbox");
                byte[] late = new String(SePayInboxTest.body(9001, view.code(), "600000"), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("10:00:00", "10:15:00").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                f.app.getBean(SePayWebhookService.class).receive(101L, f.signed(late), late);
                inbox = f.jdbc.queryForObject("SELECT id FROM webhook_inbox", Long.class);
            }
        }
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REFUND_REQUIRED");
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("REFUND_PENDING");
    }

    @Test void bankCreditedBeforeDeadlineCanApplyWhenExpiryHasNotCommitted() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        f.clock.at.set(view.expiresAt().plusSeconds(5));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, view.code(), "600000"));
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("RECEIVED");
        assertThat(f.jdbc.queryForObject("SELECT credited_at FROM payment_receipts", java.sql.Timestamp.class)
                .toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-10-30T03:00:00"));
    }

    @Test void bankTimestampWithSecondPrecisionCanMatchIntentCreatedWithinSameSecond() throws Exception {
        f.clock.at.set(PaymentFlowFixture.NOW.plusNanos(123456000));
        var view = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, view.code(), "600000"));
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("RECEIVED");
    }

    @Test void unmatchedMoneyIsRetainedForReviewWithExactLargeAmount() throws Exception {
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, "UNKNOWNCODE", "9007199254740993"));
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM payment_receipts", Long.class)).isEqualTo(9007199254740993L);
        assertThat(f.jdbc.queryForObject("SELECT intent_id FROM payment_receipts", Long.class)).isNull();
        assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox", String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(notifications()).isZero();
    }

    @Test void anotherCollectorsCodeCannotGrantPaymentBenefit() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        long inbox = f.receive(9001, view.code(), "600000");
        f.jdbc.update("UPDATE payment_channels SET user_id=3 WHERE id=101");
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts", Long.class)).isZero();
        assertThat(notifications()).isZero();
    }

    @Test void disabledPayerDoesNotDiscardAlreadyReceivedMoney() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=4");
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, view.code(), "600000"));
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("APPLIED");
    }

    @Test void notificationFailureRollsBackFinancialWritesAndDurableInboxCanRetry() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        long inbox = f.receive(9001, view.code(), "600000");
        f.app.getBean(PaymentFlowFixture.FixtureState.class).failApply = true;
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.count("payment_receipts")).isZero();
        assertThat(notifications()).isZero();
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox", String.class)).isEqualTo("RECEIVED");
        assertThat(f.jdbc.queryForObject("SELECT last_error FROM webhook_inbox", String.class)).isEqualTo("APPLY_FAILED");
        f.app.getBean(PaymentFlowFixture.FixtureState.class).failApply = false;
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(30));
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("RECEIVED");
    }

    @Test void expiredClaimIsRecoveredAndStaleWorkerCannotApplyOrReleaseNewLease() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        long inbox = f.receive(9001, view.code(), "600000");
        var work = f.app.getBean(InboxWorkService.class);
        var first = work.claim(inbox);
        assertThat(work.claim(inbox)).isNull();
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(60));
        var second = work.claim(inbox);
        assertThat(second.leaseOwner()).isNotEqualTo(first.leaseOwner());
        f.app.getBean(ReceiptApplicationService.class).apply(inbox, first.leaseOwner());
        work.retry(first, "APPLY_FAILED");
        assertThat(f.count("payment_receipts")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT lease_owner FROM webhook_inbox", String.class)).isEqualTo(second.leaseOwner());
        f.app.getBean(ReceiptApplicationService.class).apply(inbox, second.leaseOwner());
        assertThat(f.count("payment_receipts")).isEqualTo(1);
    }

    @Test void twoWorkersCannotApplySameInboxTwice() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        long inbox = f.receive(9001, view.code(), "600000");
        var start = new CountDownLatch(1);
        Runnable process = () -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            f.app.getBean(ReceiptProcessor.class).process(inbox);
        };
        var a = CompletableFuture.runAsync(process);
        var b = CompletableFuture.runAsync(process);
        start.countDown(); a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(notifications()).isEqualTo(1);
    }

    @Test void balanceAllocatesOnlyRemainingDebtAndLeavesExcessForRefundHandler() throws Exception {
        f.jdbc.update("UPDATE payment_obligations SET business_key='BOOKING:1:BALANCE',purpose='BALANCE',amount_due=1000000,amount_settled=400000 WHERE id=201");
        var view = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, view.code(), "650000"));
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201", Long.class))
                .isEqualTo(1000000L);
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts", Long.class)).isEqualTo(600000L);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REFUND_REQUIRED");
        assertThat(notifications()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"expire", "cancel"})
    void twoDatabaseConnectionsSerializeApplyAgainstClosingAttempt(String action) throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        long inbox = f.receive(9001, view.code(), "600000");
        var claim = f.app.getBean(InboxWorkService.class).claim(inbox);
        if (action.equals("expire")) f.clock.at.set(view.expiresAt());
        // A fresh lease is required at the later test clock; the old one has safely expired.
        if (action.equals("expire")) claim = f.app.getBean(InboxWorkService.class).claim(inbox);
        String token = claim.leaseOwner();
        var route = f.app.getBean(PaymentRoutes.class).intent(view.id());
        var actor = f.actor(4);
        var start = new CountDownLatch(1);
        Runnable apply = () -> {
            await(start);
            f.app.getBean(ReceiptApplicationService.class).apply(inbox, token);
        };
        Runnable close = () -> {
            await(start);
            if (action.equals("expire")) f.app.getBean(ReceiptApplicationService.class).expire(view.id());
            else f.app.getBean(PaymentIntentTransactions.class).cancel(route, view.id(), actor);
        };
        var a = CompletableFuture.runAsync(apply);
        var b = CompletableFuture.runAsync(close);
        start.countDown(); a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        String state = f.jdbc.queryForObject("SELECT status FROM payment_intents", String.class);
        long settled = f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201", Long.class);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        if (state.equals("RECEIVED")) assertThat(settled).isEqualTo(600000L);
        else {
            assertThat(state).isIn("CANCELLED", "EXPIRED");
            assertThat(settled).isZero();
            assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REFUND_REQUIRED");
        }
    }

    @Test void distinctBankTransactionsRacingAllocateOnlyOneAdvance() throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        long first = f.receive(9001, view.code(), "600000");
        long second = f.receive(9002, view.code(), "600000");
        var work = f.app.getBean(InboxWorkService.class);
        var aClaim = work.claim(first);
        var bClaim = work.claim(second);
        var start = new CountDownLatch(1);
        var a = CompletableFuture.runAsync(() -> {
            await(start); f.app.getBean(ReceiptApplicationService.class).apply(first, aClaim.leaseOwner());
        });
        var b = CompletableFuture.runAsync(() -> {
            await(start); f.app.getBean(ReceiptApplicationService.class).apply(second, bClaim.leaseOwner());
        });
        start.countDown(); a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        assertThat(f.count("payment_receipts")).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts", Long.class)).isEqualTo(600000L);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipts WHERE status='REFUND_REQUIRED'", Long.class))
                .isEqualTo(1);
    }

    @Test void subscriptionMismatchDoesNotGrantEntitlement() throws Exception {
        f.jdbc.update("""
                UPDATE payment_obligations SET business_key='SUBSCRIPTION:1',purpose='SUBSCRIPTION',
                subject_type='SUBSCRIPTION_ORDER',payer_user_id=2,collector_user_id=1,amount_due=5000 WHERE id=201
                """);
        var view = f.payments.openAttempt(201L, f.actor(2));
        byte[] body = new String(SePayInboxTest.body(9001, view.code(), "4000"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("000123456", "000999999").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Only the transport secret changes between fixture channels; payload/source remain distinct.
        var headers = sign(body, "another-fixture-secret");
        f.app.getBean(SePayWebhookService.class).receive(102L, headers, body);
        f.app.getBean(ReceiptProcessor.class).process(f.jdbc.queryForObject("SELECT id FROM webhook_inbox", Long.class));
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(notifications()).isZero();
    }

    @Test void adminSubscriptionReceiptUsesAdminChannelAndOwnerPayer() throws Exception {
        f.jdbc.update("""
                UPDATE payment_obligations SET business_key='SUBSCRIPTION:1',purpose='SUBSCRIPTION',
                subject_type='SUBSCRIPTION_ORDER',payer_user_id=2,collector_user_id=1,amount_due=5000 WHERE id=201
                """);
        var view = f.payments.openAttempt(201L, f.actor(2));
        assertThat(view.qrUrl()).contains("970422-000999999", "amount=5000");
        byte[] body = new String(SePayInboxTest.body(9001, view.code(), "5000"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("000123456", "000999999").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L, sign(body, "another-fixture-secret"), body);
        f.app.getBean(ReceiptProcessor.class).process(f.jdbc.queryForObject("SELECT id FROM webhook_inbox", Long.class));
        assertThat(f.payments.status(view.id(), f.actor(2)).status()).isEqualTo("RECEIVED");
        assertThat(f.jdbc.queryForObject("SELECT channel_id FROM payment_receipts", Long.class)).isEqualTo(102L);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201", Long.class))
                .isEqualTo(5000L);
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
    }

    private org.springframework.http.HttpHeaders sign(byte[] body, String secret) throws Exception {
        String timestamp = Long.toString(f.clock.instant().getEpochSecond());
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-SePay-Timestamp", timestamp);
        headers.set("X-SePay-Signature", "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body)));
        return headers;
    }

    private long notifications() {
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='PAYMENT_FIXTURE'", Long.class);
    }
}
