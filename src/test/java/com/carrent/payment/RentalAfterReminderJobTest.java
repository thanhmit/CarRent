package com.carrent.payment;

import com.carrent.booking.BookingRepository;
import com.carrent.booking.BookingService;
import com.carrent.booking.RentalDeadlineJob;
import com.carrent.concurrency.CarCommandExecutor;
import com.carrent.concurrency.CarQueueProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;

class RentalAfterReminderJobTest {
    @Test void reminderWorkerExistsButAutomaticSchedulingIsDisabledByDefault() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            assertThat(f.app.containsBean("rentalDeadlineJob")).isTrue();
            assertThat(f.app.containsBean("rentalDeadlineTick")).isFalse();
        }
    }

    @Test void rejectedExecutorAdmissionLeavesDueWorkForALaterScan() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            var reject = new AtomicBoolean(true);
            Executor executor = task -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (reject.get()) throw new RejectedExecutionException("Fixture queue full");
                task.run();
            };
            var job = job(f, f.app.getBean(CarCommandExecutor.class), executor);
            job.scan();
            assertUnsent(f, 1L);
            reject.set(false);
            job.scan(); // End of keyset rotation.
            job.scan();
            assertSentOnce(f, 1L);
            job.scan(); job.scan();
            assertSentOnce(f, 1L);
        }
    }

    @Test void rejectedCarQueueDoesNotMarkWorkCompleteAndANewWorkerCanRetry() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            var stopped = new CarCommandExecutor(new CarQueueProperties());
            stopped.close();
            job(f, stopped, Runnable::run).scan();
            assertUnsent(f, 1L);
            job(f, f.app.getBean(CarCommandExecutor.class), Runnable::run).scan();
            assertSentOnce(f, 1L);
        }
    }

    @Test void boundedKeysetRotationLetsLaterDueRowsPassAnEntireFailingBatch() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            for (int i = 0; i < 51; i++) {
                long id = 10000 + i;
                f.jdbc.update("""
                        INSERT INTO bookings(id,customer_id,car_id,start_date,end_date,price_per_day_snapshot,total_amount,
                        pickup_address,status,workflow_version,inspection_required,vehicle_received_at,contract_confirmed_at,
                        contract_confirmed_by) SELECT ?,customer_id,car_id,start_date,end_date,price_per_day_snapshot,total_amount,
                        pickup_address,status,workflow_version,inspection_required,vehicle_received_at,contract_confirmed_at,
                        contract_confirmed_by FROM bookings WHERE id=1
                        """, id);
                f.jdbc.update("""
                        INSERT INTO booking_inspections(booking_id,phase,author_id,status,submitted_at)
                        SELECT ?,'AFTER',author_id,status,submitted_at FROM booking_inspections WHERE booking_id=1
                        """, id);
            }
            f.jdbc.update("UPDATE bookings SET after_reminder_sent_at=? WHERE id=1",
                    LocalDateTime.ofInstant(f.clock.instant(), ZoneOffset.UTC));
            f.jdbc.execute("CREATE TRIGGER fixture_reminder_failure BEFORE INSERT ON notifications FOR EACH ROW BEGIN "
                    + "IF NEW.type='INSPECTION_AFTER_RETURN_OVERDUE' AND NEW.related_entity_id BETWEEN 10000 AND 10049 "
                    + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Fixture reminder failure'; END IF; END");
            var job = job(f, f.app.getBean(CarCommandExecutor.class), Runnable::run);
            job.scan();
            assertUnsent(f, 10050L);
            job.scan();
            assertSentOnce(f, 10050L);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE id BETWEEN 10000 AND 10049 "
                    + "AND after_reminder_sent_at IS NOT NULL", Long.class)).isZero();
        }
    }

    @Test void enabledSchedulerRecoversPersistedDueWorkAfterRestartWithoutHttpOrManualScan() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            assertUnsent(f, 1L);
            System.setProperty("carrent.rental.deadlines.enabled", "true");
            try { f.restart(false); }
            finally { System.clearProperty("carrent.rental.deadlines.enabled"); }
            assertThat(f.app.containsBean("rentalDeadlineTick")).isTrue();
            eventually(Duration.ofSeconds(45), () -> assertSentOnce(f, 1L));
            var marker = f.jdbc.queryForObject("SELECT after_reminder_sent_at FROM bookings WHERE id=1", Timestamp.class);
            f.restart(false);
            f.app.getBean(RentalDeadlineJob.class).scan();
            assertSentOnce(f, 1L);
            assertThat(f.jdbc.queryForObject("SELECT after_reminder_sent_at FROM bookings WHERE id=1", Timestamp.class))
                    .isEqualTo(marker);
        }
    }

    private static RentalDeadlineJob job(PaymentFlowFixture f, CarCommandExecutor commands, Executor executor) {
        return new RentalDeadlineJob(f.app.getBean(BookingRepository.class), f.app.getBean(BookingService.class),
                commands, f.clock, executor, f.app.getBean(com.carrent.booking.BalanceDeadlineService.class));
    }

    private static void ready(PaymentFlowFixture f) {
        f.reset();
        f.jdbc.update("DELETE FROM notifications");
        var received = LocalDateTime.ofInstant(PaymentFlowFixture.NOW.minusSeconds(1), ZoneOffset.UTC);
        f.jdbc.update("UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1,status='CONFIRMED',"
                + "vehicle_received_at=?,contract_confirmed_at=?,contract_confirmed_by=2,vehicle_returned_at=NULL,"
                + "after_reminder_sent_at=NULL WHERE id=1", received, received);
        f.jdbc.update("INSERT INTO booking_inspections(booking_id,phase,author_id,status,submitted_at) "
                + "VALUES(1,'AFTER',4,'SUBMITTED',?)", LocalDateTime.ofInstant(PaymentFlowFixture.NOW, ZoneOffset.UTC));
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));
        assertThat(f.app.getBean(BookingRepository.class).findAfterReturnReminderCandidates(0,
                LocalDateTime.ofInstant(PaymentFlowFixture.NOW, ZoneOffset.UTC), 50))
                .as("Persisted UTC report must be discoverable at the one-hour deadline").hasSize(1);
    }

    private static void assertUnsent(PaymentFlowFixture f, long id) {
        assertThat(f.jdbc.queryForObject("SELECT after_reminder_sent_at FROM bookings WHERE id=?", Timestamp.class, id)).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='INSPECTION_AFTER_RETURN_OVERDUE' "
                + "AND related_entity_id=?", Long.class, id)).isZero();
    }

    private static void assertSentOnce(PaymentFlowFixture f, long id) {
        assertThat(f.jdbc.queryForObject("SELECT after_reminder_sent_at FROM bookings WHERE id=?", Timestamp.class, id)).isNotNull();
        assertThat(f.jdbc.queryForList("SELECT user_id FROM notifications WHERE type='INSPECTION_AFTER_RETURN_OVERDUE' "
                + "AND related_entity_id=? ORDER BY user_id", Long.class, id)).containsExactly(1L, 2L);
    }

    private static void eventually(Duration timeout, Runnable assertion) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (true) {
            try { assertion.run(); return; }
            catch (AssertionError ex) { if (System.nanoTime() >= end) throw ex; Thread.sleep(50); }
        }
    }
}

