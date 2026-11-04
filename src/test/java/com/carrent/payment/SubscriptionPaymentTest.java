package com.carrent.payment;

import com.carrent.subscription.SubscriptionPlan;
import com.carrent.subscription.SubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SubscriptionPaymentTest {
    static PaymentFlowFixture f;
    static SubscriptionService service;

    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
        service = f.app.getBean(SubscriptionService.class);
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.jdbc.update("DELETE FROM notifications");
        f.reset();
        f.jdbc.update("UPDATE users SET role='OWNER' WHERE id=2");
    }

    @Test void checkoutUsesBackendSnapshotsAdminCollectorAndOneFifteenMinuteAttempt() {
        var order = service.createOrder(SubscriptionPlan.STANDARD, f.actor(2));
        var reopened = service.detail(order.orderId(), f.actor(2));
        var repeated = service.createOrder(SubscriptionPlan.STANDARD, f.actor(2));
        assertThat(reopened.intent().id()).isEqualTo(order.intent().id());
        assertThat(repeated.orderId()).isEqualTo(order.orderId());
        assertThat(order.planCode()).isEqualTo(SubscriptionPlan.STANDARD);
        assertThat(order.quotaSnapshot()).isEqualTo(8);
        assertThat(order.priceSnapshot()).isEqualTo(10000L);
        assertThat(order.durationDays()).isEqualTo(30);
        assertThat(order.intent().expiresAt()).isEqualTo(PaymentFlowFixture.NOW.plusSeconds(900));
        assertThat(order.intent().amount()).isEqualTo(10000);
        assertThat(f.count("subscription_orders")).isEqualTo(1);
        assertThat(f.jdbc.queryForMap("SELECT business_key,payer_user_id,collector_user_id,subject_type,subject_id,amount_due FROM payment_obligations WHERE purpose='SUBSCRIPTION'"))
                .containsEntry("business_key", "SUBSCRIPTION:" + order.orderId())
                .containsEntry("payer_user_id", 2L).containsEntry("collector_user_id", 1L)
                .containsEntry("subject_type", "SUBSCRIPTION_ORDER").containsEntry("subject_id", order.orderId())
                .containsEntry("amount_due", 10000L);
        assertThat(f.count("owner_subscriptions")).isZero();
    }

    @Test void firstReceiptGrantsThirtyDaysAndRenewalAddsThirtyDaysToExistingEnd() throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        pay(order.intent(), 5000, 81001, f.clock.instant());
        assertApplied(order.orderId(), PaymentFlowFixture.NOW.plusSeconds(30L * 86400));
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(86400));
        var renewal = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        pay(renewal.intent(), 5000, 81002, f.clock.instant());
        assertApplied(renewal.orderId(), PaymentFlowFixture.NOW.plusSeconds(60L * 86400));
        assertThat(f.count("owner_subscriptions")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM subscription_orders WHERE applied_at IS NOT NULL", Long.class)).isEqualTo(2);
    }

    @Test void providerReplayIsIgnoredButSecondRealTransferCreatesOneAdminRefund() throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        long inbox = pay(order.intent(), 5000, 82001, f.clock.instant());
        pay(order.intent(), 5000, 82001, f.clock.instant());
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.count("refunds")).isZero();
        pay(order.intent(), 5000, 82002, f.clock.instant());
        assertRefund("DUPLICATE_PAYMENT", 5000);
        assertApplied(order.orderId(), PaymentFlowFixture.NOW.plusSeconds(30L * 86400));
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts", Long.class)).isEqualTo(5000);
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "expire", "bank-at-expiry"})
    void cancelledOrLateMoneyCreatesRefundWithoutGrantingEntitlement(String action) throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        Instant credited = f.clock.instant();
        if (action.equals("cancel")) service.cancelOrder(order.orderId(), f.actor(2));
        else {
            f.clock.at.set(order.intent().expiresAt());
            credited = f.clock.instant();
            if (action.equals("expire")) f.app.getBean(ReceiptProcessor.class).expire(order.intent().id());
        }
        pay(order.intent(), 5000, 83001, credited);
        assertRefund(action.equals("cancel") ? "CANCELLED_SUBSCRIPTION" : "LATE_SUBSCRIPTION", 5000);
        assertThat(f.count("owner_subscriptions")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT applied_at FROM subscription_orders WHERE id=?", LocalDateTime.class, order.orderId())).isNull();
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts", Long.class)).isZero();
    }

    @Test void onTimeMoneyDeliveredAfterCommittedExpiryStillGrantsCompatiblePlan() throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        f.clock.at.set(order.intent().expiresAt().plusSeconds(5));
        f.app.getBean(ReceiptProcessor.class).expire(order.intent().id());
        pay(order.intent(), 5000, 84001, PaymentFlowFixture.NOW.plusSeconds(10));
        assertApplied(order.orderId(), f.clock.instant().plusSeconds(30L * 86400));
        assertThat(f.count("refunds")).isZero();
    }

    @Test void delayedOnTimeReceiptRefundsWhenAnotherPlanBecameActive() throws Exception {
        var oldOrder = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        f.clock.at.set(oldOrder.intent().expiresAt().plusSeconds(1));
        f.app.getBean(ReceiptProcessor.class).expire(oldOrder.intent().id());
        var current = service.createOrder(SubscriptionPlan.STANDARD, f.actor(2));
        pay(current.intent(), 10000, 85001, f.clock.instant());
        var entitlement = f.jdbc.queryForMap("SELECT * FROM owner_subscriptions");
        pay(oldOrder.intent(), 5000, 85002, PaymentFlowFixture.NOW.plusSeconds(10));
        assertRefund("INCOMPATIBLE_SUBSCRIPTION", 5000);
        assertThat(f.jdbc.queryForMap("SELECT * FROM owner_subscriptions")).isEqualTo(entitlement);
        assertThat(f.jdbc.queryForObject("SELECT applied_at FROM subscription_orders WHERE id=?", LocalDateTime.class, oldOrder.orderId())).isNull();
    }

    @Test void exactlyExpiredEntitlementAllowsDifferentPlanAndStartsFromActivationTime() throws Exception {
        var old = service.createOrder(SubscriptionPlan.STANDARD, f.actor(2));
        pay(old.intent(), 10000, 86001, f.clock.instant());
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(30L * 86400));
        var next = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        pay(next.intent(), 5000, 86002, f.clock.instant());
        assertApplied(next.orderId(), f.clock.instant().plusSeconds(30L * 86400));
        assertThat(f.jdbc.queryForObject("SELECT plan_code FROM owner_subscriptions", String.class)).isEqualTo("SMALL");
        assertThat(f.jdbc.queryForObject("SELECT valid_from FROM owner_subscriptions", LocalDateTime.class)).isEqualTo(LocalDateTime.ofInstant(f.clock.instant(), ZoneOffset.UTC));
    }

    @Test void downgradeWithExpiredPendingRenewalKeepsCarsLockedAndAppliesNewPlan() throws Exception {
        var first = service.createOrder(SubscriptionPlan.STANDARD, f.actor(2));
        pay(first.intent(), 10000, 95001, f.clock.instant());
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(29L * 86400));
        var stale = service.createOrder(SubscriptionPlan.STANDARD, f.actor(2));
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(30L * 86400));
        var next = f.tx.execute(status -> {
            var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
            var cars = f.em.createQuery("select c from Car c where c.owner.id=2 order by c.id", com.carrent.car.Car.class).getResultList();
            assertThat(cars).isNotEmpty();
            for (var car : cars) assertThat(f.em.getLockMode(car)).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            return order;
        });
        assertThat(f.jdbc.queryForObject("SELECT status FROM subscription_orders WHERE id=?", String.class, stale.orderId())).isEqualTo("EXPIRED");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=?", String.class, stale.intent().id())).isEqualTo("EXPIRED");
        pay(next.intent(), 5000, 95002, f.clock.instant());
        assertApplied(next.orderId(), f.clock.instant().plusSeconds(30L * 86400));
        assertThat(f.jdbc.queryForObject("SELECT plan_code FROM owner_subscriptions", String.class)).isEqualTo("SMALL");
    }

    @ParameterizedTest @ValueSource(longs = {4999, 5001})
    void mismatchedAmountRequiresReviewWithoutGrantOrRefund(long amount) throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        pay(order.intent(), amount, 87001, f.clock.instant());
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts", String.class)).isEqualTo("REVIEW_REQUIRED");
        assertThat(f.count("owner_subscriptions")).isZero();
        assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE purpose='SUBSCRIPTION'", Long.class)).isZero();
    }

    @Test void activeDifferentPlanAndNonOwnerOrDisabledActorCannotCreateOrder() throws Exception {
        assertThatThrownBy(() -> service.createOrder(SubscriptionPlan.SMALL, f.actor(4))).isInstanceOf(RuntimeException.class);
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=2");
        assertThatThrownBy(() -> service.createOrder(SubscriptionPlan.SMALL, f.actor(2))).isInstanceOf(RuntimeException.class);
        f.jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=2");
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        pay(order.intent(), 5000, 88001, f.clock.instant());
        assertThatThrownBy(() -> service.createOrder(SubscriptionPlan.STANDARD, f.actor(2))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.detail(order.orderId(), f.actor(4))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.cancelOrder(order.orderId(), f.actor(4))).isInstanceOf(RuntimeException.class);
        assertThat(f.count("subscription_orders")).isEqualTo(1);
    }

    @Test void simultaneousPostsReuseOneOrderAndOneIntent() throws Exception {
        var actor = f.actor(2);
        var start = new CountDownLatch(1);
        var a = CompletableFuture.supplyAsync(() -> { await(start); return service.createOrder(SubscriptionPlan.SMALL, actor); });
        var b = CompletableFuture.supplyAsync(() -> { await(start); return service.createOrder(SubscriptionPlan.SMALL, actor); });
        start.countDown();
        var first = a.get(30, TimeUnit.SECONDS);
        var second = b.get(30, TimeUnit.SECONDS);
        assertThat(first.orderId()).isEqualTo(second.orderId());
        assertThat(first.intent().id()).isEqualTo(second.intent().id());
        assertThat(f.count("subscription_orders")).isEqualTo(1);
        assertThat(f.count("payment_intents")).isEqualTo(1);
    }

    @Test void concurrentDistinctReceiptsGrantOnceAndRefundTheOtherTransfer() throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        long one = enqueue(order.intent(), 5000, 89001, f.clock.instant());
        long two = enqueue(order.intent(), 5000, 89002, f.clock.instant());
        var processor = f.app.getBean(ReceiptProcessor.class);
        var start = new CountDownLatch(1);
        var a = CompletableFuture.runAsync(() -> { await(start); processor.process(one); });
        var b = CompletableFuture.runAsync(() -> { await(start); processor.process(two); });
        start.countDown();
        CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);
        assertThat(f.count("payment_receipts")).isEqualTo(2);
        assertRefund("DUPLICATE_PAYMENT", 5000);
        assertApplied(order.orderId(), PaymentFlowFixture.NOW.plusSeconds(30L * 86400));
    }

    @Test void notificationFailureRollsBackEntitlementAppliedAtAndAllocationThenRetries() throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        long inbox = enqueue(order.intent(), 5000, 90001, f.clock.instant());
        f.jdbc.execute("CREATE TRIGGER subscription_fail_notice BEFORE INSERT ON notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='subscription notification failure'");
        try { f.app.getBean(ReceiptProcessor.class).process(inbox); }
        finally { f.jdbc.execute("DROP TRIGGER subscription_fail_notice"); }
        assertThat(f.count("owner_subscriptions")).isZero();
        assertThat(f.count("payment_receipts")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT applied_at FROM subscription_orders WHERE id=?", LocalDateTime.class, order.orderId())).isNull();
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE purpose='SUBSCRIPTION'", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT last_error FROM webhook_inbox", String.class)).isEqualTo("APPLY_FAILED");
        f.clock.at.set(f.clock.instant().plusSeconds(60));
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertApplied(order.orderId(), f.clock.instant().plusSeconds(30L * 86400));
    }

    @Test void concurrentReceiptsForDifferentOrdersSerializeOnTheSameOwner() throws Exception {
        var old = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        f.clock.at.set(old.intent().expiresAt().plusSeconds(1));
        f.app.getBean(ReceiptProcessor.class).expire(old.intent().id());
        var next = service.createOrder(SubscriptionPlan.STANDARD, f.actor(2));
        long one = enqueue(old.intent(), 5000, 94001, PaymentFlowFixture.NOW.plusSeconds(1));
        long two = enqueue(next.intent(), 10000, 94002, f.clock.instant());
        var processor = f.app.getBean(ReceiptProcessor.class);
        var start = new CountDownLatch(1);
        var a = CompletableFuture.runAsync(() -> { await(start); processor.process(one); });
        var b = CompletableFuture.runAsync(() -> { await(start); processor.process(two); });
        start.countDown();
        CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);
        assertThat(f.count("owner_subscriptions")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM subscription_orders WHERE applied_at IS NOT NULL", Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE reason='INCOMPATIBLE_SUBSCRIPTION'", Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM webhook_inbox WHERE status='PROCESSED'", Long.class)).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT valid_until FROM owner_subscriptions", LocalDateTime.class))
                .isEqualTo(LocalDateTime.ofInstant(f.clock.instant().plusSeconds(30L * 86400), ZoneOffset.UTC));
    }

    private void assertApplied(long orderId, Instant until) {
        assertThat(f.jdbc.queryForObject("SELECT status FROM subscription_orders WHERE id=?", String.class, orderId)).isEqualTo("APPLIED");
        assertThat(f.jdbc.queryForObject("SELECT applied_at FROM subscription_orders WHERE id=?", LocalDateTime.class, orderId)).isNotNull();
        assertThat(f.jdbc.queryForObject("SELECT last_applied_order_id FROM owner_subscriptions WHERE owner_id=2", Long.class)).isEqualTo(orderId);
        assertThat(f.jdbc.queryForObject("SELECT valid_until FROM owner_subscriptions WHERE owner_id=2", LocalDateTime.class)).isEqualTo(LocalDateTime.ofInstant(until, ZoneOffset.UTC));
    }

    @Test void everyRealTransferAfterCancellationRefundsWithoutLosingCancellationProvenance() throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        service.cancelOrder(order.orderId(), f.actor(2));
        pay(order.intent(), 5000, 91001, f.clock.instant());
        pay(order.intent(), 5000, 91002, f.clock.instant());
        assertThat(f.count("owner_subscriptions")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE reason='CANCELLED_SUBSCRIPTION' AND amount=5000", Long.class)).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM webhook_inbox WHERE status='PROCESSED'", Long.class)).isEqualTo(2);
    }

    @Test void timelyDelayedTransferCanApplyAfterAnotherLateTransferAlreadyRequestedRefund() throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        f.clock.at.set(order.intent().expiresAt().plusSeconds(10));
        pay(order.intent(), 5000, 92001, f.clock.instant());
        assertRefund("LATE_SUBSCRIPTION", 5000);
        pay(order.intent(), 5000, 92002, PaymentFlowFixture.NOW.plusSeconds(1));
        assertApplied(order.orderId(), f.clock.instant().plusSeconds(30L*86400));
        assertThat(f.count("refunds")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payer_user_id=3", "collector_user_id=3", "amount_due=6000"})
    void alteredObligationCannotGrantEntitlement(String change) throws Exception {
        var order = service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        f.jdbc.update("UPDATE payment_obligations SET " + change + " WHERE purpose='SUBSCRIPTION'");
        pay(order.intent(), 5000, 93001, f.clock.instant());
        assertThat(f.count("owner_subscriptions")).isZero();
        assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox", String.class)).isEqualTo("REVIEW_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"collector_user_id=2", "subject_type='BOOKING'", "business_key='SUBSCRIPTION:999999'"})
    void databaseRejectsMalformedSubscriptionObligation(String change) {
        service.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> f.jdbc.update("UPDATE payment_obligations SET " + change + " WHERE purpose='SUBSCRIPTION'"));
        assertThat(f.count("owner_subscriptions")).isZero();
    }
    private void assertRefund(String reason, long amount) {
        assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(f.jdbc.queryForMap("SELECT r.reason,r.amount,c.user_id AS collector_user_id,r.recipient_user_id,r.status FROM refunds r JOIN payment_channels c ON c.id=r.payout_channel_id"))
                .containsEntry("reason", reason).containsEntry("amount", amount)
                .containsEntry("collector_user_id", 1L).containsEntry("recipient_user_id", 2L)
                .containsEntry("status", "WAITING_RECIPIENT");
    }
    private long enqueue(PaymentIntentView intent, long amount, long transaction, Instant credited) throws Exception {
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(credited);
        byte[] body = new String(SePayInboxTest.body(transaction, intent.code(), Long.toString(amount)), StandardCharsets.UTF_8)
                .replace("000123456", "000999999").replace("2026-10-30 10:00:00", date).getBytes(StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L, f.signed(body, "another-fixture-secret"), body);
        return f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?", Long.class, Long.toString(transaction));
    }
    private long pay(PaymentIntentView intent, long amount, long transaction, Instant credited) throws Exception {
        long inbox = enqueue(intent, amount, transaction, credited);
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        return inbox;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race start timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
}
