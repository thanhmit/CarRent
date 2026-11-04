package com.carrent.payment;

import com.carrent.car.*;
import com.carrent.common.exception.BusinessException;
import com.carrent.subscription.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SubscriptionDowngradeTest {
    static PaymentFlowFixture f;
    static SubscriptionService subscriptions;
    static CarService cars;
    static long transaction = 986000;

    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
        subscriptions = f.app.getBean(SubscriptionService.class);
        cars = f.app.getBean(CarService.class);
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.jdbc.update("DELETE FROM notifications");
        f.reset();
        f.jdbc.update("DELETE FROM cars WHERE id>6");
        f.jdbc.update("UPDATE cars SET approval_status=CASE WHEN id=4 THEN 'REJECTED' WHEN id=3 THEN 'PENDING' ELSE 'APPROVED' END,listing_status=CASE WHEN id IN (4,5) THEN 'INACTIVE' ELSE 'ACTIVE' END");
    }

    @Test void expiredBusinessToSmallKeepsSixteenCarsAndBookingsAndLetsOwnerChooseFour() throws Exception {
        businessFleet();
        var bookings = f.jdbc.queryForList("SELECT * FROM bookings ORDER BY id");
        var images = f.jdbc.queryForList("SELECT * FROM car_images ORDER BY id");
        var before = f.jdbc.queryForList("SELECT id,approval_status,brand,model,description FROM cars ORDER BY id");
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        assertThat(publicCount()).isZero();
        var small = subscriptions.createOrder(SubscriptionPlan.SMALL, f.actor(2));
        assertThat(reserved()).isEqualTo(16); // Opening checkout never changes Owner intent.
        pay(small);
        assertThat(publicCount()).isZero();
        assertThat(reserved()).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM cars WHERE owner_id=2", Long.class)).isEqualTo(16);
        assertThat(f.jdbc.queryForList("SELECT id,approval_status,brand,model,description FROM cars ORDER BY id")).isEqualTo(before);
        assertThat(f.jdbc.queryForList("SELECT * FROM car_images ORDER BY id")).isEqualTo(images);
        assertThat(f.jdbc.queryForList("SELECT * FROM bookings ORDER BY id")).isEqualTo(bookings);
        for (long id : new long[]{1,2,5,101}) cars.activateCar(id, f.actor(2));
        assertThat(publicCount()).isEqualTo(4);
        assertThat(reserved()).isEqualTo(4);
        assertThatThrownBy(() -> cars.activateCar(102L, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(f.jdbc.queryForObject("SELECT approval_status FROM cars WHERE id=1", String.class)).isEqualTo("APPROVED");
    }

    @Test void samePlanRenewalRestoresOnlyEligibleIntendedListings() throws Exception {
        businessFleet();
        f.jdbc.update("UPDATE cars SET listing_status='INACTIVE' WHERE id=5");
        f.jdbc.update("UPDATE cars SET approval_status='REJECTED' WHERE id=101");
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        assertThat(publicCount()).isZero();
        pay(subscriptions.createOrder(SubscriptionPlan.BUSINESS, f.actor(2)));
        assertThat(publicCount()).isEqualTo(14);
        assertThat(f.jdbc.queryForObject("SELECT listing_status FROM cars WHERE id=5", String.class)).isEqualTo("INACTIVE");
        assertThat(f.jdbc.queryForObject("SELECT approval_status FROM cars WHERE id=101", String.class)).isEqualTo("REJECTED");
    }

    @Test void replayAndDuplicateTransferAfterOwnerChoiceDoNotHideCarsAgain() throws Exception {
        businessFleet();
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        var small=subscriptions.createOrder(SubscriptionPlan.SMALL,f.actor(2));
        long inbox=pay(small);
        cars.activateCar(1L,f.actor(2));
        var until=f.jdbc.queryForObject("SELECT valid_until FROM owner_subscriptions",LocalDateTime.class);
        var processor=f.app.getBean(ReceiptProcessor.class);
        processor.process(inbox);
        pay(small);
        assertThat(reserved()).isOne();
        assertThat(publicCount()).isOne();
        assertThat(f.jdbc.queryForObject("SELECT valid_until FROM owner_subscriptions",LocalDateTime.class)).isEqualTo(until);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE reason='DUPLICATE_PAYMENT' AND amount=5000",Long.class)).isOne();
    }

    @ParameterizedTest @ValueSource(strings={"cancel","late","wrong-amount","incompatible"})
    void unappliedDowngradeReceiptsNeverHideCars(String reason) throws Exception {
        businessFleet();
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        var small=subscriptions.createOrder(SubscriptionPlan.SMALL,f.actor(2));
        if(reason.equals("cancel")) subscriptions.cancelOrder(small.orderId(),f.actor(2));
        if(reason.equals("late")) f.clock.at.set(small.intent().expiresAt());
        if(reason.equals("incompatible")) {
            f.clock.at.set(small.intent().expiresAt().plusSeconds(1));
            pay(subscriptions.createOrder(SubscriptionPlan.BUSINESS,f.actor(2)));
        }
        var before=f.jdbc.queryForList("SELECT * FROM cars ORDER BY id");
        var entitlement=f.jdbc.queryForList("SELECT * FROM owner_subscriptions");
        long inbox=enqueue(small,reason.equals("wrong-amount")?4999:5000,
                reason.equals("incompatible")?small.intent().expiresAt().minusSeconds(899):f.clock.instant());
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.jdbc.queryForList("SELECT * FROM cars ORDER BY id")).isEqualTo(before);
        assertThat(f.jdbc.queryForList("SELECT * FROM owner_subscriptions")).isEqualTo(entitlement);
        assertThat(f.jdbc.queryForObject("SELECT applied_at FROM subscription_orders WHERE id=?",LocalDateTime.class,small.orderId())).isNull();
        assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox WHERE id=?",String.class,inbox))
                .isEqualTo(reason.equals("wrong-amount")?"REVIEW_REQUIRED":"PROCESSED");
    }

    @Test void notificationFailureRollsBackAllCarIntentsAndFinancialStateThenRetryHidesOnce() throws Exception {
        businessFleet();
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        var small=subscriptions.createOrder(SubscriptionPlan.SMALL,f.actor(2));
        long inbox=enqueue(small,5000,f.clock.instant());
        var entitlement=f.jdbc.queryForMap("SELECT * FROM owner_subscriptions");
        var before=f.jdbc.queryForList("SELECT * FROM cars ORDER BY id");
        f.jdbc.execute("CREATE TRIGGER downgrade_fail_notice BEFORE INSERT ON notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='downgrade notification failure'");
        try { f.app.getBean(ReceiptProcessor.class).process(inbox); }
        finally { f.jdbc.execute("DROP TRIGGER downgrade_fail_notice"); }
        assertThat(f.jdbc.queryForList("SELECT * FROM cars ORDER BY id")).isEqualTo(before);
        assertThat(f.jdbc.queryForMap("SELECT * FROM owner_subscriptions")).isEqualTo(entitlement);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=(SELECT obligation_id FROM subscription_orders WHERE id=?)",Long.class,small.orderId())).isZero();
        assertThat(f.jdbc.queryForObject("SELECT applied_at FROM subscription_orders WHERE id=?",LocalDateTime.class,small.orderId())).isNull();
        assertThat(f.jdbc.queryForObject("SELECT last_error FROM webhook_inbox WHERE id=?",String.class,inbox)).isEqualTo("APPLY_FAILED");
        f.clock.at.set(f.clock.instant().plusSeconds(60));
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(reserved()).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM subscription_orders WHERE id=?",String.class,small.orderId())).isEqualTo("APPLIED");
    }

    @Test void preloadedEntitlementCannotAllowMidtermPlanSwitchAfterAnotherTransactionRenews() throws Exception {
        businessFleet();
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        var actor=f.actor(2);
        f.tx.executeWithoutResult(s -> {
            f.em.find(OwnerSubscription.class,2L); // Managed expired entity and old RR snapshot.
            CompletableFuture.runAsync(() -> f.jdbc.update("UPDATE owner_subscriptions SET valid_until=DATE_ADD(valid_until,INTERVAL 30 DAY) WHERE owner_id=2")).join();
            assertThatThrownBy(() -> subscriptions.createOrder(SubscriptionPlan.SMALL,actor)).isInstanceOf(BusinessException.class);
            s.setRollbackOnly();
        });
        assertThat(reserved()).isEqualTo(16);
    }

    @Test void downgradeUsesCurrentFleetDespiteManagedCarsAndOldRepeatableReadSnapshot() throws Exception {
        businessFleet();
        f.jdbc.update("DELETE FROM cars WHERE id=113");
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        var small=subscriptions.createOrder(SubscriptionPlan.SMALL,f.actor(2));
        long inbox=enqueue(small,5000,f.clock.instant());
        var claim=f.app.getBean(InboxWorkService.class).claim(inbox);
        f.tx.executeWithoutResult(s -> {
            var preloaded=f.em.find(Car.class,1L);
            f.em.find(OwnerSubscription.class,2L);
            CompletableFuture.runAsync(() -> {
                f.jdbc.update("UPDATE cars SET description='Concurrent edit retained' WHERE id=1");
                cloneCar(113);
            }).join();
            f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());
            assertThat(preloaded.getListingStatus()).isEqualTo(ListingStatus.INACTIVE);
            assertThat(preloaded.getDescription()).isEqualTo("Concurrent edit retained");
            assertThat(f.em.find(Car.class,113L).getListingStatus()).isEqualTo(ListingStatus.INACTIVE);
        });
        assertThat(reserved()).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM cars WHERE owner_id=2",Long.class)).isEqualTo(16);
        assertThat(f.jdbc.queryForObject("SELECT description FROM cars WHERE id=1",String.class)).isEqualTo("Concurrent edit retained");
    }

    @Test void activationWaitingForDowngradeSeesNewEntitlementAndEmptyQuota() throws Exception {
        businessFleet();
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        var small=subscriptions.createOrder(SubscriptionPlan.SMALL,f.actor(2));
        long inbox=enqueue(small,5000,f.clock.instant());
        var claim=f.app.getBean(InboxWorkService.class).claim(inbox);
        var actor=f.actor(2);
        var snapshot=new CountDownLatch(1);
        var pending=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
        f.tx.executeWithoutResult(s -> {
            subscriptions.lockSubject(small.orderId());
            pending.set(CompletableFuture.runAsync(() -> f.tx.executeWithoutResult(t -> {
                f.em.find(OwnerSubscription.class,2L);
                f.em.createNativeQuery("SELECT COUNT(*) FROM cars").getSingleResult();
                snapshot.countDown();
                cars.activateCar(1L,actor);
            })));
            await(snapshot);
            f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());
        });
        pending.get().get(15,TimeUnit.SECONDS);
        assertThat(reserved()).isOne();
        assertThat(publicCount()).isOne();
        assertThat(f.jdbc.queryForObject("SELECT quota FROM owner_subscriptions",Integer.class)).isEqualTo(4);
    }

    @Test void delayedLowerPlanLocksCarsBeforeFinancialWhileHigherPlanIsStillActive() throws Exception {
        var old=subscriptions.createOrder(SubscriptionPlan.SMALL,f.actor(2));
        // A delayed lower-plan receipt may coexist with a newer, still-active Business entitlement.
        f.clock.at.set(old.intent().expiresAt().plusSeconds(1));
        f.app.getBean(ReceiptProcessor.class).expire(old.intent().id());
        businessFleet();
        f.tx.executeWithoutResult(s -> {
            subscriptions.lockSubject(old.orderId());
            var car=f.em.find(Car.class,1L);
            assertThat(f.em.getLockMode(car)).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        });
    }

    private void businessFleet() throws Exception {
        pay(subscriptions.createOrder(SubscriptionPlan.BUSINESS, f.actor(2)));
        f.jdbc.update("UPDATE cars SET listing_status='ACTIVE',approval_status='APPROVED' WHERE owner_id=2");
        for (long id=101; id<=113; id++) {
            cloneCar(id);
        }
        assertThat(reserved()).isEqualTo(16);
    }
    private long reserved() {
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM cars WHERE owner_id=2 AND listing_status='ACTIVE' AND approval_status IN ('PENDING','APPROVED')", Long.class);
    }
    private long publicCount() {
        return f.tx.execute(s -> f.app.getBean(CarRepository.class).count(f.app.getBean(SubscriptionPolicy.class).publicCars()));
    }
    private long pay(SubscriptionOrderView order) throws Exception {
        long inbox=enqueue(order,order.priceSnapshot(),f.clock.instant());
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        return inbox;
    }
    private long enqueue(SubscriptionOrderView order,long amount,Instant credited) throws Exception {
        long id=++transaction;
        String date=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(credited);
        byte[] body=new String(SePayInboxTest.body(id,order.intent().code(),Long.toString(amount)),StandardCharsets.UTF_8)
                .replace("000123456","000999999").replace("2026-10-30 10:00:00",date).getBytes(StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L,f.signed(body,"another-fixture-secret"),body);
        long inbox=f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?",Long.class,Long.toString(id));
        return inbox;
    }
    private void cloneCar(long id) {
        f.jdbc.update("INSERT INTO cars(id,owner_id,category_id,brand,model,manufacture_year,seat_count,transmission,fuel_type,price_per_day,province,approval_status,listing_status) SELECT ?,owner_id,category_id,brand,model,manufacture_year,seat_count,transmission,fuel_type,price_per_day,province,'APPROVED','ACTIVE' FROM cars WHERE id=1",id);
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue(); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
}
