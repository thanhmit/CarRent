package com.carrent.payment;

import com.carrent.booking.RentalDeadlineJob;
import com.carrent.booking.BookingRepository;
import com.carrent.booking.BookingService;
import com.carrent.concurrency.CarCommandExecutor;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RentalBalanceDeadlineTest {
    @Test void dueBalanceNotifiesCustomerOwnerAndAdminOnceAfterRestartWithoutChangingDebt() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            var job = job(f);
            job.scan();
            assertUnsent(f);
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));
            job.scan(); job.scan();
            assertSent(f);
            var marker = f.jdbc.queryForObject("SELECT balance_overdue_notified_at FROM bookings WHERE id=1", Timestamp.class);
            f.restart(false);
            job(f).scan();
            assertSent(f);
            assertThat(f.jdbc.queryForObject("SELECT balance_overdue_notified_at FROM bookings WHERE id=1", Timestamp.class)).isEqualTo(marker);
            assertThat(f.jdbc.queryForMap("SELECT status,amount_due,amount_settled,due_at FROM payment_obligations WHERE id=202"))
                    .containsEntry("status", "OPEN").containsEntry("amount_due", 1400000L).containsEntry("amount_settled", 0L)
                    .containsEntry("due_at", LocalDateTime.ofInstant(PaymentFlowFixture.NOW.plusSeconds(3600), ZoneOffset.UTC));
            assertThat(f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1", String.class)).isEqualTo("CONFIRMED");
            assertThat(f.count("refunds")).isZero();
            assertThat(f.jdbc.queryForObject("SELECT status FROM users WHERE id=4", String.class)).isEqualTo("ACTIVE");
        }
    }

    @Test void settledLegacyUnreturnedAndInvalidObligationsNeverNotify() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            for (String mutation : new String[]{
                    "UPDATE payment_obligations SET status='SETTLED',amount_settled=amount_due WHERE id=202",
                    "UPDATE bookings SET workflow_version='LEGACY' WHERE id=1",
                    "UPDATE bookings SET vehicle_returned_at=NULL WHERE id=1",
                    "UPDATE payment_obligations SET collector_user_id=1 WHERE id=202"}) {
                ready(f);
                f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3601));
                f.jdbc.update(mutation);
                job(f).scan();
                assertUnsent(f);
            }
        }
    }

    @Test void notificationFailureRollsBackMarkerAndAllRecipientsThenRetrySucceeds() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));
            f.jdbc.execute("CREATE TRIGGER fixture_balance_notice_failure BEFORE INSERT ON notifications FOR EACH ROW BEGIN "
                    + "IF NEW.type='BOOKING_BALANCE_OVERDUE' AND NEW.user_id=4 THEN SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT='Fixture balance reminder failure'; END IF; END");
            assertThatThrownBy(() -> f.app.getBean(com.carrent.booking.BalanceDeadlineService.class).remind(1L))
                    .isInstanceOf(RuntimeException.class);
            assertUnsent(f);
            f.jdbc.execute("DROP TRIGGER fixture_balance_notice_failure");
            f.app.getBean(com.carrent.booking.BalanceDeadlineService.class).remind(1L);
            assertSent(f);
        }
    }

    @Test void currentReadSeesSettledBalanceAfterAnOlderRepeatableReadSnapshot() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));
            f.tx.executeWithoutResult(tx -> {
                f.em.find(com.carrent.booking.Booking.class, 1L);
                f.em.find(PaymentObligation.class, 202L);
                CompletableFuture.runAsync(() -> f.jdbc.update("UPDATE payment_obligations SET status='SETTLED',amount_settled=amount_due WHERE id=202")).join();
                f.app.getBean(com.carrent.booking.BalanceDeadlineService.class).remind(1L);
            });
            assertUnsent(f);
        }
    }

    @Test void competingReminderTransactionsPersistOnlyOneNoticePerRecipient() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));
            var service = f.app.getBean(com.carrent.booking.BalanceDeadlineService.class);
            var first = CompletableFuture.runAsync(() -> service.remind(1L));
            var second = CompletableFuture.runAsync(() -> service.remind(1L));
            CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
            assertSent(f);
        }
    }

    @Test void rejectedExecutorDoesNotLosePersistedBalanceReminder() throws Exception {
        try (var f = new PaymentFlowFixture(false, false)) {
            ready(f);
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));
            var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.Executor executor = task -> {
                if (reject.get()) throw new java.util.concurrent.RejectedExecutionException("Fixture full");
                task.run();
            };
            var job = new RentalDeadlineJob(f.app.getBean(BookingRepository.class), f.app.getBean(BookingService.class),
                    f.app.getBean(CarCommandExecutor.class), f.clock, executor,
                    f.app.getBean(com.carrent.booking.BalanceDeadlineService.class));
            job.scan();
            assertUnsent(f);
            reject.set(false);
            job.scan(); job.scan();
            assertSent(f);
        }
    }

    private static RentalDeadlineJob job(PaymentFlowFixture f) {
        return new RentalDeadlineJob(f.app.getBean(BookingRepository.class), f.app.getBean(BookingService.class),
                f.app.getBean(CarCommandExecutor.class), f.clock, Runnable::run,
                f.app.getBean(com.carrent.booking.BalanceDeadlineService.class));
    }

    private static void ready(PaymentFlowFixture f) {
        f.reset();
        f.jdbc.update("DELETE FROM notifications");
        var at = LocalDateTime.ofInstant(PaymentFlowFixture.NOW, ZoneOffset.UTC);
        var due = LocalDateTime.ofInstant(PaymentFlowFixture.NOW.plusSeconds(3600), ZoneOffset.UTC);
        f.jdbc.update("UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1,status='CONFIRMED',"
                + "vehicle_received_at=?,vehicle_returned_at=?,contract_confirmed_at=?,contract_confirmed_by=2,balance_amount=1400000,balance_overdue_notified_at=NULL WHERE id=1", at, at, at);
        f.jdbc.update("INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,collector_user_id,"
                + "amount_due,amount_settled,status,due_at,created_at,updated_at) VALUES(202,'BOOKING:1:BALANCE','BALANCE','BOOKING',1,4,2,1400000,0,'OPEN',?,?,?)", due, at, at);
    }

    private static void assertUnsent(PaymentFlowFixture f) {
        assertThat(f.jdbc.queryForObject("SELECT balance_overdue_notified_at FROM bookings WHERE id=1", Timestamp.class)).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_BALANCE_OVERDUE'", Long.class)).isZero();
    }

    private static void assertSent(PaymentFlowFixture f) {
        assertThat(f.jdbc.queryForObject("SELECT balance_overdue_notified_at FROM bookings WHERE id=1", Timestamp.class)).isNotNull();
        assertThat(f.jdbc.queryForList("SELECT user_id FROM notifications WHERE type='BOOKING_BALANCE_OVERDUE' AND related_entity_id=1 ORDER BY user_id", Long.class))
                .containsExactly(1L, 2L, 4L);
    }
}
