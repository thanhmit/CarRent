package com.carrent.concurrency;

import com.carrent.booking.Booking;
import com.carrent.booking.BookingCreateForm;
import com.carrent.booking.BookingService;
import com.carrent.booking.BookingStatus;
import com.carrent.car.ApprovalStatus;
import com.carrent.car.Car;
import com.carrent.car.CarCategory;
import com.carrent.car.FuelType;
import com.carrent.car.ListingStatus;
import com.carrent.car.Transmission;
import com.carrent.common.exception.BusinessException;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real service proxies and MySQL connections; every committed fixture is deleted by exact ID. */
@SpringBootTest(properties = {"debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN",
        "logging.level.org.hibernate=WARN", "logging.level.org.hibernate.SQL=OFF", "spring.main.banner-mode=off"})
class CarCommandTransactionTest {
    @Autowired CarCommandExecutor queue;
    @Autowired BookingService bookings;
    @Autowired EntityManager em;
    @Autowired JpaTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    TransactionTemplate tx;
    Collection<TransactionExecutionListener> originalListeners;
    User owner;
    User customer;
    Car car;
    CarCategory category;
    final LocalDate start = LocalDate.now().plusDays(60);

    @BeforeEach
    void fixtures() {
        tx = new TransactionTemplate(manager);
        originalListeners = new ArrayList<>(manager.getTransactionExecutionListeners());
        tx.executeWithoutResult(status -> {
            owner = user(Role.OWNER);
            customer = user(Role.CUSTOMER);
            String name = "tx-" + UUID.randomUUID();
            category = CarCategory.builder().name(name).build();
            em.persist(category);
            car = Car.builder().owner(owner).category(category).brand(name).model("Transaction fixture")
                    .manufactureYear(2024).seatCount(5).transmission(Transmission.AUTOMATIC)
                    .fuelType(FuelType.GASOLINE).pricePerDay(500000L).province("Hà Nội")
                    .approvalStatus(ApprovalStatus.APPROVED).listingStatus(ListingStatus.ACTIVE).build();
            em.persist(car);
        });
    }

    User user(Role role) {
        var user = User.builder().email(UUID.randomUUID() + "@transaction.test").fullName("Transaction fixture")
                .passwordHash("unused-test-password-hash").role(role).build();
        em.persist(user);
        return user;
    }

    @AfterEach
    void cleanup() {
        manager.setTransactionExecutionListeners(originalListeners);
        tx.executeWithoutResult(status -> {
            if (car != null) {
                for (Long id : jdbc.queryForList("select id from bookings where car_id=?", Long.class, car.getId())) {
                    jdbc.update("delete from notifications where related_entity_type='BOOKING' and related_entity_id=?", id);
                    jdbc.update("delete from payments where booking_id=?", id);
                }
                jdbc.update("delete from bookings where car_id=?", car.getId());
                jdbc.update("delete from cars where id=?", car.getId());
            }
            for (User user : new User[]{owner, customer}) {
                if (user == null) continue;
                jdbc.update("delete from notifications where user_id=?", user.getId());
                jdbc.update("delete from users where id=?", user.getId());
            }
            if (category != null) jdbc.update("delete from car_categories where id=?", category.getId());
        });
    }

    @Test
    void waitingCommandsOpenNoTransactionUntilTheServiceRunsOnTheWorker() throws Exception {
        var transactionThreads = new CopyOnWriteArrayList<Thread>();
        manager.addListener(new TransactionExecutionListener() {
            @Override
            public void beforeBegin(TransactionExecution transaction) { transactionThreads.add(Thread.currentThread()); }
        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var actionRan = new AtomicBoolean();
        var blocker = call(() -> queue.execute(car.getId(), () -> { entered.countDown(); await(release); return null; }));
        Call<Booking> waiting = null;
        try {
            await(entered);
            waiting = call(() -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                Booking created = queue.execute(car.getId(), () -> {
                    actionRan.set(true);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return bookings.createBooking(form(), CustomUserPrincipal.from(customer));
                });
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return created;
            });
            waiting.awaitQueueWait();
            assertThat(actionRan).isFalse();
            assertThat(transactionThreads).isEmpty();
            release.countDown();
            blocker.get();
            assertThat(waiting.get().getId()).isNotNull();
            assertThat(transactionThreads).hasSize(1);
            assertThat(transactionThreads.getFirst().getName()).startsWith("car-command-");
            assertThat(transactionThreads.getFirst()).isNotSameAs(waiting.thread());
            assertThat(bookingCount()).isOne();
        } finally {
            release.countDown();
            blocker.get();
            if (waiting != null) waiting.get();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callersAndTheNextCommandWaitForCommitOrRollbackCompletion(boolean rollback) throws Exception {
        var before = new Gate();
        var after = new Gate();
        var failure = new BusinessException("Rollback fixture after real writes");
        var nextRan = new AtomicBoolean();
        manager.addListener(new TransactionExecutionListener() {
            @Override
            public void beforeCommit(TransactionExecution transaction) { if (!rollback) before.pause(); }
            @Override
            public void afterCommit(TransactionExecution transaction, Throwable error) { if (!rollback) after.pause(); }
            @Override
            public void beforeRollback(TransactionExecution transaction) { if (rollback) before.pause(); }
            @Override
            public void afterRollback(TransactionExecution transaction, Throwable error) { if (rollback) after.pause(); }
        });
        var running = call(() -> {
            try {
                queue.execute(car.getId(), () -> {
                    if (!rollback) return bookings.createBooking(form(), CustomUserPrincipal.from(customer));
                    return tx.execute(status -> {
                        bookings.createBooking(form(), CustomUserPrincipal.from(customer));
                        em.flush();
                        assertThat(bookingCount()).isOne();
                        assertThat(notificationCount()).isOne();
                        throw failure;
                    });
                });
                return null;
            } catch (RuntimeException ex) {
                return ex;
            }
        });
        Call<Integer> next = null;
        try {
            await(before.entered);
            assertThat(running.result()).isNotDone();
            assertThat(bookingCount()).isZero();
            assertThat(notificationCount()).isZero();
            next = call(() -> queue.execute(car.getId(), () -> { nextRan.set(true); return bookingCount(); }));
            next.awaitQueueWait();
            before.release.countDown();
            await(after.entered);
            assertThat(bookingCount()).isEqualTo(rollback ? 0 : 1);
            assertThat(notificationCount()).isEqualTo(rollback ? 0 : 1);
            assertThat(running.result()).isNotDone();
            assertThat(nextRan).isFalse();
            after.release.countDown();
            assertThat(running.get()).isSameAs(rollback ? failure : null);
            assertThat(next.get()).isEqualTo(rollback ? 0 : 1);
            assertThat(nextRan).isTrue();
        } finally {
            before.release.countDown();
            after.release.countDown();
            running.get();
            if (next != null) next.get();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseLocksPreventOverlappingBookingsEvenWhenBothCallersBypassTheQueue(boolean rollbackFirst) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        var firstConnection = new AtomicLong();
        var secondConnection = new AtomicLong();
        var rollbackFailure = new BusinessException("Rollback first booking fixture");
        var first = call(() -> repeatableRead().execute(status -> {
            firstConnection.set(connectionId());
            Booking created = bookings.createBooking(form(), CustomUserPrincipal.from(customer));
            em.flush();
            held.countDown();
            await(release);
            if (rollbackFirst) throw rollbackFailure;
            return created.getId();
        }));
        Call<Long> second = null;
        try {
            await(held);
            second = call(() -> repeatableRead().execute(status -> {
                secondConnection.set(connectionId());
                assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                assertThat(bookingCount()).as("old snapshot excludes the uncommitted first booking").isZero();
                em.find(Car.class, car.getId());
                attempting.countDown();
                return bookings.createBooking(form(), CustomUserPrincipal.from(customer)).getId();
            }));
            await(attempting);
            assertThat(secondConnection.get()).isNotEqualTo(firstConnection.get());
            awaitCarLockWait(secondConnection.get(), firstConnection.get());
            assertThat(second.result()).isNotDone();
            release.countDown();
            if (rollbackFirst) {
                assertThatThrownBy(first::get).isInstanceOf(ExecutionException.class).hasCause(rollbackFailure);
                assertThat(second.get()).isNotNull();
            } else {
                assertThat(first.get()).isNotNull();
                assertThatThrownBy(second::get).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(BusinessException.class).cause().hasMessageContaining("trùng thời gian");
            }
            assertThat(bookingCount()).isOne();
            assertThat(notificationCount()).isOne();
        } finally {
            release.countDown();
            first.finish();
            if (second != null) second.finish();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "hidden"})
    void bookingUsesTheCurrentCarDespiteAnOlderSnapshotAndManagedEntity(String change) throws Exception {
        repeatableRead().executeWithoutResult(status -> {
            status.setRollbackOnly();
            var stale = em.find(Car.class, car.getId());
            assertThat(stale.getPricePerDay()).isEqualTo(500000L);
            assertThat(stale.getListingStatus()).isEqualTo(ListingStatus.ACTIVE);
            assertThat(bookingCount()).isZero();
            long originalConnection = connectionId();
            var update = call(() -> tx.execute(other -> {
                assertThat(connectionId()).isNotEqualTo(originalConnection);
                return jdbc.update(change.equals("price") ? "update cars set price_per_day=700000 where id=?"
                        : "update cars set listing_status='INACTIVE' where id=?", car.getId());
            }));
            try { assertThat(update.get()).isOne(); }
            catch (Exception ex) { throw new AssertionError(ex); }
            assertThat(stale.getPricePerDay()).isEqualTo(500000L);
            assertThat(stale.getListingStatus()).isEqualTo(ListingStatus.ACTIVE);
            if (change.equals("hidden")) {
                assertThatThrownBy(() -> bookings.createBooking(form(), CustomUserPrincipal.from(customer)))
                        .isInstanceOf(BusinessException.class).hasMessageContaining("không nhận đơn");
            } else {
                var created = bookings.createBooking(form(), CustomUserPrincipal.from(customer));
                assertThat(created.getPricePerDaySnapshot()).isEqualTo(700000L);
                assertThat(created.getTotalAmount()).isEqualTo(1400000L);
            }
        });
        assertThat(bookingCount()).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void ownerCannotConfirmACancelledBookingFromAnOlderSnapshot() {
        Long id = bookings.createBooking(form(), CustomUserPrincipal.from(customer)).getId();
        repeatableRead().executeWithoutResult(status -> {
            status.setRollbackOnly();
            var stale = em.find(Booking.class, id);
            assertThat(stale.getStatus()).isEqualTo(BookingStatus.PENDING);
            em.find(Car.class, car.getId());
            long originalConnection = connectionId();
            var cancel = call(() -> tx.execute(other -> {
                assertThat(connectionId()).isNotEqualTo(originalConnection);
                bookings.cancelBooking(id, CustomUserPrincipal.from(customer));
                return null;
            }));
            try { cancel.get(); }
            catch (Exception ex) { throw new AssertionError(ex); }
            assertThat(stale.getStatus()).isEqualTo(BookingStatus.PENDING);
            assertThatThrownBy(() -> bookings.confirmBooking(id, CustomUserPrincipal.from(owner)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("đang chờ xác nhận");
        });
        assertThat(jdbc.queryForObject("select status from bookings where id=?", String.class, id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from notifications where related_entity_type='BOOKING' "
                + "and related_entity_id=? and type='BOOKING_CONFIRMED'", Integer.class, id)).isZero();
    }

    @Test
    void enqueueInsideARealTransactionHoldingTheCarLockIsRejectedBeforeAnyActionRuns() {
        var actionRan = new AtomicBoolean();
        tx.executeWithoutResult(status -> {
            em.find(Car.class, car.getId(), LockModeType.PESSIMISTIC_WRITE);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> queue.execute(car.getId(), () -> { actionRan.set(true); return null; }))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("before opening a transaction");
        });
        assertThat(actionRan).isFalse();
        assertThat(bookingCount()).isZero();
        assertThat(queue.execute(car.getId(), () -> bookings.createBooking(form(), CustomUserPrincipal.from(customer))).getId())
                .isNotNull();
        assertThat(bookingCount()).isOne();
    }

    TransactionTemplate repeatableRead() {
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    long connectionId() { return jdbc.queryForObject("select connection_id()", Long.class); }

    void awaitCarLockWait(long requesting, long blocking) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < until) {
            int waits = jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits w "
                    + "join performance_schema.threads r on r.thread_id=w.requesting_thread_id "
                    + "join performance_schema.threads b on b.thread_id=w.blocking_thread_id "
                    + "join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id and l.engine=w.engine "
                    + "where r.processlist_id=? and b.processlist_id=? and l.object_schema=database() and l.object_name='cars'",
                    Integer.class, requesting, blocking);
            if (waits > 0) return;
            Thread.yield();
        }
        throw new AssertionError("No actual Car row lock wait between the two MySQL connections");
    }

    int notificationCount() {
        return jdbc.queryForObject("select count(*) from notifications where user_id=? and type='BOOKING_CREATED'", Integer.class, owner.getId());
    }

    static class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        void pause() { entered.countDown(); await(release); }
    }

    BookingCreateForm form() {
        var form = new BookingCreateForm();
        form.setCarId(car.getId());
        form.setStartDate(start);
        form.setEndDate(start.plusDays(2));
        return form;
    }

    int bookingCount() {
        return jdbc.queryForObject("select count(*) from bookings where car_id=?", Integer.class, car.getId());
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("latch completed before deadline").isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    static <T> Call<T> call(Supplier<T> action) {
        var result = new CompletableFuture<T>();
        var thread = Thread.ofVirtual().start(() -> {
            try { result.complete(action.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return new Call<>(thread, result);
    }

    record Call<T>(Thread thread, CompletableFuture<T> result) {
        T get() throws Exception { return result.get(15, TimeUnit.SECONDS); }
        void finish() throws Exception { result.handle((value, failure) -> null).get(15, TimeUnit.SECONDS); }
        void awaitQueueWait() {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime() < until) {
                if (thread.getState() == Thread.State.TIMED_WAITING) {
                    for (var frame : thread.getStackTrace()) {
                        if (frame.getClassName().equals(CarCommandExecutor.class.getName())
                                && frame.getMethodName().equals("execute")) return;
                    }
                }
                Thread.yield();
            }
            throw new AssertionError("Caller did not reach the queue wait");
        }
    }
}
