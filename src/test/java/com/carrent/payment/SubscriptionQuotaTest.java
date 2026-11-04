package com.carrent.payment;

import com.carrent.car.*;
import com.carrent.booking.*;
import com.carrent.common.exception.BusinessException;
import com.carrent.subscription.*;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class SubscriptionQuotaTest {
    static PaymentFlowFixture f;
    static CarService cars;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
        cars = f.app.getBean(CarService.class);
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.jdbc.update("DELETE FROM notifications");
        f.reset();
        f.jdbc.update("DELETE FROM car_images WHERE car_id>6");
        f.jdbc.update("DELETE FROM cars WHERE id>6");
        f.jdbc.update("UPDATE cars SET listing_status='INACTIVE',approval_status='APPROVED'");
        f.jdbc.update("UPDATE users SET role='OWNER' WHERE id=2");
        f.jdbc.update("UPDATE cars SET brand='Toyota' WHERE id=1");
    }
    static void grant(long owner) {
        var order = f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL, f.actor(owner));
        f.jdbc.update("INSERT INTO owner_subscriptions(owner_id,plan_code,quota,last_applied_order_id,valid_from,valid_until,created_at,updated_at) VALUES(?,'SMALL',4,?,?,DATE_ADD(?,INTERVAL 30 DAY),?,?)",
                owner, order.orderId(), java.time.LocalDateTime.ofInstant(f.clock.instant(), java.time.ZoneOffset.UTC), java.time.LocalDateTime.ofInstant(f.clock.instant(), java.time.ZoneOffset.UTC),
                java.time.LocalDateTime.ofInstant(f.clock.instant(), java.time.ZoneOffset.UTC), java.time.LocalDateTime.ofInstant(f.clock.instant(), java.time.ZoneOffset.UTC));
    }
    static CarForm form() {
        return f.tx.execute(s -> CarForm.from(f.em.find(Car.class, 1L)));
    }
    long cloneCar(String approval, String listing) {
        var form = form();
        var car = cars.createCar(form, List.of(), f.actor(2));
        f.jdbc.update("UPDATE cars SET approval_status=?,listing_status=? WHERE id=?", approval, listing, car.getId());
        return car.getId();
    }
    long reserved() {
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM cars WHERE owner_id=2 AND listing_status='ACTIVE' AND approval_status IN ('PENDING','APPROVED')", Long.class);
    }
    @Test void missingOrExpiredPlanBlocksAdmissionButAllowsEditingExistingCar() {
        var form = form();
        assertThatThrownBy(() -> cars.createCar(form, List.of(), f.actor(2))).isInstanceOf(BusinessException.class);
        grant(2);
        f.clock.at.set(f.clock.instant().plusSeconds(30L*86400));
        assertThatThrownBy(() -> cars.activateCar(1L, f.actor(2))).isInstanceOf(BusinessException.class);
        cars.updateCar(1L, form, f.actor(2));
        assertThat(reserved()).isZero();
    }
    @Test void pendingReservesImmediatelyApprovalAndEditDoNotDoubleCountAndHideRejectRelease() {
        grant(2);
        long pending = cloneCar("PENDING", "ACTIVE");
        cars.updateCar(pending, form(), f.actor(2));
        assertThat(reserved()).isEqualTo(1);
        cars.approveCar(pending, f.actor(1));
        assertThat(reserved()).isEqualTo(1);
        cars.deactivateCar(pending, f.actor(2));
        assertThat(reserved()).isZero();
        long rejected = cloneCar("PENDING", "ACTIVE");
        cars.rejectCar(rejected, "Cần sửa nội dung", f.actor(1));
        assertThat(reserved()).isZero();
        cars.activateCar(rejected, f.actor(2));
        assertThat(reserved()).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT approval_status FROM cars WHERE id=?", String.class, rejected)).isEqualTo("PENDING");
        cars.deactivateCar(rejected, f.actor(2));
        cars.approveCar(rejected, f.actor(1));
        assertThat(reserved()).isZero();
    }
    @Test void fullQuotaBlocksCreateAndOwnerAdminActivationButIdempotentActivationAndEditSucceed() {
        grant(2);
        for (int i=0;i<4;i++) cloneCar("PENDING", "ACTIVE");
        var form = form();
        assertThatThrownBy(() -> cars.createCar(form, List.of(), f.actor(2))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cars.activateCar(1L, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cars.activateCarForAdmin(1L, f.actor(1))).isInstanceOf(BusinessException.class);
        long id = f.jdbc.queryForObject("SELECT MIN(id) FROM cars WHERE owner_id=2 AND listing_status='ACTIVE'", Long.class);
        cars.approveCar(id, f.actor(1));
        cars.activateCar(id, f.actor(2));
        cars.updateCar(id, form, f.actor(2));
        assertThat(reserved()).isEqualTo(4);
    }
    @Test void pendingReactivationKeepsPendingAndRejectedInvalidContentCannotResubmit() {
        grant(2);
        f.jdbc.update("UPDATE cars SET approval_status='PENDING' WHERE id=1");
        cars.activateCar(1L, f.actor(2));
        assertThat(f.jdbc.queryForObject("SELECT approval_status FROM cars WHERE id=1", String.class)).isEqualTo("PENDING");
        f.jdbc.update("UPDATE cars SET approval_status='REJECTED',brand='' WHERE id=1");
        assertThatThrownBy(() -> cars.activateCar(1L, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(reserved()).isZero();
    }
    @Test void differentCarsRaceLastSlotUsingCurrentReadsEvenWithOldSnapshot() throws Exception {
        grant(2);
        for (int i=0;i<3;i++) cloneCar("APPROVED", "ACTIVE");
        long a=cloneInactive(), b=cloneInactive();
        var actor=f.actor(2);
        var firstLocked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var secondSnapshot=new CountDownLatch(1);
        var first=CompletableFuture.runAsync(() -> f.tx.executeWithoutResult(s -> {
            f.em.createNativeQuery("SELECT id FROM users WHERE id=2 FOR UPDATE").getSingleResult();
            firstLocked.countDown(); await(release);
            cars.activateCar(a, actor);
        }));
        await(firstLocked);
        var second=CompletableFuture.supplyAsync(() -> f.tx.execute(s -> {
            f.em.createNativeQuery("SELECT COUNT(*) FROM cars").getSingleResult();
            secondSnapshot.countDown();
            try { cars.activateCar(b, actor); return true; }
            catch (BusinessException expected) { s.setRollbackOnly(); return false; }
        }));
        await(secondSnapshot); release.countDown();
        first.get(15,TimeUnit.SECONDS);
        assertThat(second.get(15,TimeUnit.SECONDS)).isFalse();
        assertThat(reserved()).isEqualTo(4);
    }
    long cloneInactive() {
        // Build inactive candidates without consuming the already reserved final slot.
        long id=f.jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM cars",Long.class);
        f.jdbc.update("INSERT INTO cars(id,owner_id,category_id,brand,model,manufacture_year,seat_count,transmission,fuel_type,price_per_day,province,approval_status,listing_status) SELECT ?,owner_id,category_id,brand,model,manufacture_year,seat_count,transmission,fuel_type,price_per_day,province,'APPROVED','INACTIVE' FROM cars WHERE id=1",id);
        return id;
    }
    @Test void rollbackReleasesSlotAndDifferentOwnerDoesNotWaitOnOwnerMutex() throws Exception {
        grant(2); f.historicalSmallSubscription(3);
        var actor=f.actor(2);
        f.tx.executeWithoutResult(s -> { cars.activateCar(1L, actor); s.setRollbackOnly(); });
        assertThat(reserved()).isZero();
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var blocker=CompletableFuture.runAsync(() -> f.tx.executeWithoutResult(s -> {
            f.em.createNativeQuery("SELECT id FROM users WHERE id=2 FOR UPDATE").getSingleResult();
            locked.countDown(); await(release);
        }));
        await(locked);
        try {
            var other=f.actor(3); var form=form();
            CompletableFuture.runAsync(() -> cars.createCar(form,List.of(),other)).get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); blocker.get(15,TimeUnit.SECONDS); }
    }
    @Test void expiryBetweenGetAndPostRejectsNewBookingWithoutCancellingExistingRequests() {
        grant(2); cars.activateCar(1L,f.actor(2));
        cars.getPublicCar(1L,null);
        long before=f.count("bookings");
        f.clock.at.set(f.clock.instant().plusSeconds(30L*86400));
        var form=new BookingCreateForm(); form.setCarId(1L);
        form.setStartDate(LocalDate.now().plusDays(90)); form.setEndDate(LocalDate.now().plusDays(92));
        assertThatThrownBy(() -> f.app.getBean(BookingService.class).createBooking(form,f.actor(4))).isInstanceOf(BusinessException.class);
        assertThat(f.count("bookings")).isEqualTo(before);
        assertThat(f.jdbc.queryForObject("SELECT listing_status FROM cars WHERE id=1",String.class)).isEqualTo("ACTIVE");
    }
    @Test void existingRentalWaitsForOwnerBeforeTakingCarSoQuotaAndNotificationCannotDeadlock() throws Exception {
        grant(2); cars.activateCar(1L,f.actor(2));
        var customer=f.actor(4);
        var connection=new java.util.concurrent.atomic.AtomicLong();
        var started=new CountDownLatch(1);
        var pending=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
        try {
            f.tx.executeWithoutResult(s -> {
                f.app.getBean(SubscriptionPolicy.class).lockOwner(2L);
                pending.set(CompletableFuture.runAsync(() -> f.tx.executeWithoutResult(t -> {
                    connection.set(((Number)f.em.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                    started.countDown();
                    f.app.getBean(BookingService.class).cancelBooking(1L,customer,"Change schedule");
                })));
                await(started);
                org.awaitility.Awaitility.await().atMost(8,TimeUnit.SECONDS).until(() ->
                        f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=? AND trx_state='LOCK WAIT'",Long.class,connection.get())==1);
                // A rental waiting at Owner must leave Car available to this quota transaction.
                f.em.createNativeQuery("SELECT id FROM cars WHERE id=1 FOR UPDATE NOWAIT").getSingleResult();
                cars.createCar(form(),List.of(),f.actor(2));
            });
        } finally { if(pending.get()!=null) pending.get().get(15,TimeUnit.SECONDS); }
        assertThat(f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class)).isEqualTo("CANCELLED");
        assertThat(reserved()).isEqualTo(2);
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
}
