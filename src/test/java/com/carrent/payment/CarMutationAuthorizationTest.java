package com.carrent.payment;

import com.carrent.car.Car;
import com.carrent.car.CarForm;
import com.carrent.car.CarService;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.user.User;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class CarMutationAuthorizationTest {
    static PaymentFlowFixture enabled;
    static PaymentFlowFixture disabled;

    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        try { enabled = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
        disabled = new PaymentFlowFixture(false, false);
    }

    @AfterAll static void stop() throws Exception {
        try { if (enabled != null) enabled.close(); }
        finally { if (disabled != null) disabled.close(); }
    }

    @BeforeEach void reset() {
        for (var f : List.of(enabled, disabled)) {
            f.jdbc.update("DELETE FROM owner_subscriptions");
            f.jdbc.update("DELETE FROM subscription_orders");
            f.jdbc.update("DELETE FROM notifications");
            f.reset();
            f.jdbc.update("DELETE FROM car_images WHERE car_id>6");
            f.jdbc.update("DELETE FROM cars WHERE id>6");
            f.jdbc.update("UPDATE users SET role=CASE id WHEN 1 THEN 'ADMIN' ELSE 'OWNER' END,status='ACTIVE' WHERE id IN (1,2)");
            f.jdbc.update("UPDATE cars SET listing_status='INACTIVE',approval_status='APPROVED'");
            f.historicalSmallSubscription(2);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "true,create,status", "true,create,role", "false,create,status", "false,create,role",
            "true,owner,status", "true,owner,role", "false,owner,status", "false,owner,role",
            "true,approve,status", "true,approve,role", "false,approve,status", "false,approve,role",
            "true,admin,status", "true,admin,role", "false,admin,status", "false,admin,role"
    })
    void mutationRejectsRevocationCommittedAfterItsRepeatableReadSnapshot(boolean subscriptions, String operation, String change) {
        var f = subscriptions ? enabled : disabled;
        long id = operation.equals("create") || operation.equals("owner") ? 2 : 1;
        var actor = f.actor(id);
        var form = f.tx.execute(s -> CarForm.from(f.em.find(Car.class, 1L)));
        if (operation.equals("approve")) f.jdbc.update("UPDATE cars SET approval_status='PENDING' WHERE id=1");
        var original = f.jdbc.queryForMap("SELECT listing_status,approval_status FROM cars WHERE id=1");
        long count = f.count("cars"), notifications = f.count("notifications");
        f.tx.executeWithoutResult(s -> {
            s.setRollbackOnly();
            f.em.find(User.class, id); // Establish the stale managed actor and RR snapshot.
            Long reader = f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            Long writer = CompletableFuture.supplyAsync(() -> f.tx.execute(other -> {
                f.jdbc.update("UPDATE users SET " + (change.equals("status") ? "status='BANNED'" : "role='CUSTOMER'") + " WHERE id=?", id);
                return f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            })).join();
            assertThat(writer).isNotEqualTo(reader);
            var cars = f.app.getBean(CarService.class);
            assertThatThrownBy(() -> {
                switch (operation) {
                    case "create" -> cars.createCar(form, List.of(), actor);
                    case "owner" -> cars.activateCar(1L, actor);
                    case "approve" -> cars.approveCar(1L, actor);
                    case "admin" -> cars.activateCarForAdmin(1L, actor);
                    default -> throw new AssertionError(operation);
                }
            }).isInstanceOf(ForbiddenException.class);
        });
        assertThat(f.jdbc.queryForMap("SELECT listing_status,approval_status FROM cars WHERE id=1")).isEqualTo(original);
        assertThat(f.count("cars")).isEqualTo(count);
        assertThat(f.count("notifications")).isEqualTo(notifications);
    }

    @ParameterizedTest
    @CsvSource({"create,status", "create,role", "activate,status", "activate,role"})
    void ownerMutationWaitingForOwnerMutexSeesCommittedRevocation(String operation, String change) throws Exception {
        var f = enabled;
        var actor = f.actor(2);
        var form = f.tx.execute(s -> CarForm.from(f.em.find(Car.class, 1L)));
        var workerConnection = new AtomicLong();
        var started = new CountDownLatch(1);
        var pending = new AtomicReference<CompletableFuture<Throwable>>();
        f.tx.executeWithoutResult(s -> {
            f.em.createNativeQuery("SELECT id FROM users WHERE id=2 FOR UPDATE").getSingleResult();
            pending.set(CompletableFuture.supplyAsync(() -> {
                try {
                    f.tx.executeWithoutResult(other -> {
                        workerConnection.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        started.countDown();
                        if (operation.equals("create")) f.app.getBean(CarService.class).createCar(form, List.of(), actor);
                        else f.app.getBean(CarService.class).activateCar(1L, actor);
                    });
                    return null;
                } catch (Throwable error) { return error; }
            }));
            await(started);
            org.awaitility.Awaitility.await().atMost(8, TimeUnit.SECONDS).until(() -> f.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=? AND trx_state='LOCK WAIT'",
                    Long.class, workerConnection.get()) == 1);
            f.jdbc.update("UPDATE users SET " + (change.equals("status") ? "status='BANNED'" : "role='CUSTOMER'") + " WHERE id=2");
        });
        assertThat(pending.get().get(10, TimeUnit.SECONDS)).isInstanceOf(ForbiddenException.class);
        assertThat(f.jdbc.queryForObject("SELECT listing_status FROM cars WHERE id=1", String.class)).isEqualTo("INACTIVE");
        assertThat(f.count("cars")).isEqualTo(6);
        assertThat(f.count("notifications")).isZero();
    }

    @Test void sharedAdminAuthorizationLockDoesNotBlockOwnerNotificationForeignKey() throws Exception {
        var f = enabled;
        var admin = f.actor(1);
        var owner = f.actor(2);
        var form = f.tx.execute(s -> CarForm.from(f.em.find(Car.class, 1L)));
        f.jdbc.update("UPDATE cars SET approval_status='PENDING' WHERE id=3");
        var adminLocked = new CountDownLatch(1);
        var releaseAdmin = new CountDownLatch(1);
        var adminWork = CompletableFuture.runAsync(() -> f.tx.executeWithoutResult(s -> {
            f.app.getBean(CarService.class).approveCar(3L, admin);
            adminLocked.countDown();
            await(releaseAdmin);
        }));
        await(adminLocked);
        try {
            // The Owner transaction inserts a notification referencing Admin while Admin is still locked.
            CompletableFuture.runAsync(() -> f.app.getBean(CarService.class).createCar(form, List.of(), owner))
                    .get(5, TimeUnit.SECONDS);
            assertThat(adminWork).isNotDone();
        } finally {
            releaseAdmin.countDown();
            adminWork.get(10, TimeUnit.SECONDS);
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=1 AND type='CAR_PENDING_APPROVAL'", Long.class)).isOne();
    }

    @Test void crossOwnerDenialsDoNotAcquireTheOtherOwnersMutex() throws Exception {
        var f = enabled;
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (long id : new long[]{2, 3}) {
                var actor = f.actor(id);
                attempts.add(pool.submit(() -> f.tx.executeWithoutResult(s -> {
                    s.setRollbackOnly();
                    f.em.createNativeQuery("SELECT id FROM users WHERE id=:id FOR UPDATE").setParameter("id", id).getSingleResult();
                    try { barrier.await(5, TimeUnit.SECONDS); }
                    catch (Exception ex) { throw new AssertionError(ex); }
                    assertThatThrownBy(() -> f.app.getBean(CarService.class).activateCar(id == 2 ? 3L : 1L, actor))
                            .isInstanceOf(ForbiddenException.class);
                })));
            }
            for (var attempt : attempts) attempt.get(8, TimeUnit.SECONDS);
        }
        assertThat(f.count("notifications")).isZero();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void ownerCreateAndConcurrentAdminBanCommitWithoutNotificationLockCycle(boolean subscriptions) throws Exception {
        var f = subscriptions ? enabled : disabled;
        var admin = f.actor(1);
        var owner = f.actor(2);
        var form = f.tx.execute(s -> CarForm.from(f.em.find(Car.class, 1L)));
        var adminConnection = new AtomicLong();
        var started = new CountDownLatch(1);
        var pending = new AtomicReference<CompletableFuture<Throwable>>();
        Throwable ownerFailure = catchThrowable(() -> f.tx.executeWithoutResult(s -> {
            f.em.createNativeQuery("SELECT id FROM users WHERE id=2 FOR UPDATE").getSingleResult();
            pending.set(CompletableFuture.supplyAsync(() -> {
                try {
                    f.tx.executeWithoutResult(other -> {
                        adminConnection.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        started.countDown();
                        f.app.getBean(com.carrent.admin.AdminUserService.class)
                                .changeStatus(2, com.carrent.user.UserStatus.BANNED, admin);
                    });
                    return null;
                } catch (Throwable failure) { return failure; }
            }));
            await(started);
            org.awaitility.Awaitility.await().atMost(8, TimeUnit.SECONDS).until(() -> f.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=? AND trx_state='LOCK WAIT'",
                    Long.class, adminConnection.get()) == 1);
            // Admin is waiting for Owner; the notification must still take a shared FK lock on Admin.
            f.app.getBean(CarService.class).createCar(form, List.of(), owner);
        }));
        Throwable adminFailure = pending.get().get(10, TimeUnit.SECONDS);
        assertThat(ownerFailure).as("Owner create must commit without deadlock/retry").isNull();
        assertThat(adminFailure).as("Admin ban must commit after Owner without deadlock/retry").isNull();
        assertThat(f.count("cars")).isEqualTo(7);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=1 AND type='CAR_PENDING_APPROVAL'", Long.class)).isOne();
        assertThat(f.jdbc.queryForObject("SELECT status FROM users WHERE id=2", String.class)).isEqualTo("BANNED");
    }

    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException ex) { throw new AssertionError(ex); }
    }
}
