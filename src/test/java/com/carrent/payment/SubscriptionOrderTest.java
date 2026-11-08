package com.carrent.payment;

import com.carrent.subscription.SubscriptionPlan;
import com.carrent.subscription.SubscriptionService;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class SubscriptionOrderTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions"); f.jdbc.update("DELETE FROM subscription_orders");
        f.jdbc.update("UPDATE users SET role='OWNER',status='ACTIVE' WHERE id=2");
        f.jdbc.update("UPDATE users SET role='ADMIN',status='ACTIVE' WHERE id=1");
        f.reset();
    }
    SubscriptionService service() { return f.app.getBean(SubscriptionService.class); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(SubscriptionPlan.class)
    void allPlansCreateAndCancelWithSubMicrosecondClock(SubscriptionPlan plan) {
        f.clock.at.set(PaymentFlowFixture.NOW.plusNanos(123456789));
        var order = service().createOrder(plan, f.actor(2));
        assertThat(order.intent().status()).isEqualTo("ACTIVE");
        assertThat(order.intent().amount()).isEqualTo(plan.getPrice());
        assertThat(f.count("owner_subscriptions")).isZero();
        assertThat(service().detail(order.orderId(), f.actor(2)).status()).isEqualTo("PENDING");
        service().cancelOrder(order.orderId(), f.actor(2));
        assertThat(service().detail(order.orderId(), f.actor(2)).status()).isEqualTo("CANCELLED");
    }
    @Test void createsServerSnapshotsAndOneAdminQrWithoutBooking() {
        var oldBookings = f.count("bookings");
        var a = service().createOrder(SubscriptionPlan.SMALL, f.actor(2));
        var b = service().createOrder(SubscriptionPlan.SMALL, f.actor(2));
        assertThat(b.orderId()).isEqualTo(a.orderId()); assertThat(b.intent().id()).isEqualTo(a.intent().id());
        assertThat(a.priceSnapshot()).isEqualTo(5000); assertThat(a.quotaSnapshot()).isEqualTo(4);
        assertThat(a.durationDays()).isEqualTo(30); assertThat(a.intent().expiresAt()).isEqualTo(PaymentFlowFixture.NOW.plusSeconds(900));
        assertThat(f.jdbc.queryForObject("SELECT channel_id FROM payment_intents WHERE id=?",Long.class,a.intent().id())).isEqualTo(102);
        assertThat(f.jdbc.queryForObject("SELECT business_key FROM payment_obligations WHERE subject_type='SUBSCRIPTION_ORDER'",String.class)).isEqualTo("SUBSCRIPTION:"+a.orderId());
        assertThat(f.count("bookings")).isEqualTo(oldBookings); assertThat(f.count("owner_subscriptions")).isZero();
        var obligationId = f.jdbc.queryForObject("SELECT obligation_id FROM subscription_orders WHERE id=?", Long.class, a.orderId());
        assertThat(f.app.getBean(PaymentRoutes.class).obligation(obligationId).carId()).isNull();
        assertThatThrownBy(() -> service().createOrder(SubscriptionPlan.STANDARD,f.actor(2))).isInstanceOf(BusinessException.class);
    }
    @Test void cancelAndExpiryEndAttemptWithoutGrantOrDeletingHistory() {
        var a=service().createOrder(SubscriptionPlan.SMALL,f.actor(2));
        service().cancelOrder(a.orderId(),f.actor(2));
        assertThat(service().detail(a.orderId(),f.actor(2)).status()).isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=?",String.class,a.intent().id())).isEqualTo("CANCELLED");
        var b=service().createOrder(SubscriptionPlan.SMALL,f.actor(2));
        f.clock.at.set(b.intent().expiresAt());
        assertThat(service().detail(b.orderId(),f.actor(2)).status()).isEqualTo("EXPIRED");
        var c=service().createOrder(SubscriptionPlan.BUSINESS,f.actor(2));
        assertThat(c.orderId()).isNotEqualTo(b.orderId());
        assertThat(f.jdbc.queryForObject("SELECT status FROM subscription_orders WHERE id=?",String.class,b.orderId())).isEqualTo("EXPIRED");
        assertThat(f.count("owner_subscriptions")).isZero(); assertThat(f.count("subscription_orders")).isEqualTo(3);
    }
    @Test void checksDatabaseActorAndOwnershipEvenWithStalePrincipal() {
        var owner=f.actor(2);var a=service().createOrder(SubscriptionPlan.SMALL,owner);
        for (long id : new long[]{1,3,4}) {
            assertThatThrownBy(() -> service().detail(a.orderId(),f.actor(id))).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service().cancelOrder(a.orderId(),f.actor(id))).isInstanceOf(ForbiddenException.class);
        }
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=2");
        assertThatThrownBy(() -> service().createOrder(SubscriptionPlan.SMALL,owner)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service().cancelOrder(a.orderId(),owner)).isInstanceOf(ForbiddenException.class);
        f.jdbc.update("UPDATE users SET status='ACTIVE',role='CUSTOMER' WHERE id=2");
        assertThatThrownBy(() -> service().createOrder(SubscriptionPlan.SMALL,owner)).isInstanceOf(ForbiddenException.class);
    }
    @Test void missingAdminChannelRollsBackOrderAndObligation() {
        f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=102");
        assertThatThrownBy(() -> service().createOrder(SubscriptionPlan.SMALL,f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(f.count("subscription_orders")).isZero(); assertThat(f.count("payment_intents")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE purpose='SUBSCRIPTION'",Long.class)).isZero();
    }
    @Test void doublePostOnTwoConnectionsReturnsOneOrderAndIntent() throws Exception {
        var actor=f.actor(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Long> work=()-> {start.await();return service().createOrder(SubscriptionPlan.SMALL,actor).orderId();};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
        }
        assertThat(f.count("subscription_orders")).isEqualTo(1);assertThat(f.count("payment_intents")).isEqualTo(1);
    }

    @Test void reuseWorksWhenSecondTransactionSnapshotPredatesFirstCommit() throws Exception {
        var actor = f.actor(2);
        var created = new CountDownLatch(1);
        var snapshot = new CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                f.app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tx.execute(status -> {
                var order = service().createOrder(SubscriptionPlan.SMALL, actor);
                created.countDown();
                await(snapshot);
                return order;
            }));
            var second = pool.submit(() -> {
                await(created);
                return tx.execute(status -> {
                    assertThat(f.count("subscription_orders")).isZero();
                    snapshot.countDown();
                    return service().createOrder(SubscriptionPlan.SMALL, actor);
                });
            });
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(b.orderId()).isEqualTo(a.orderId());
            assertThat(b.intent().id()).isEqualTo(a.intent().id());
            assertThat(b.intent().status()).isEqualTo("ACTIVE");
        }
        assertThat(f.count("subscription_orders")).isEqualTo(1);
        assertThat(f.count("payment_intents")).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
