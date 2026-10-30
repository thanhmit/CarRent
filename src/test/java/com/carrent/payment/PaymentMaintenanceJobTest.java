package com.carrent.payment;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentMaintenanceJobTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); }
    @AfterEach void drain() throws Exception {
        var executor = f.app.getBean("paymentDomainExecutor", org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        eventually(() -> {
            assertThat(executor.getActiveCount()).isZero();
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        });
    }

    @Test void duplicateWebhookAndConcurrentJobExpiryNeverApplyTheSameMoneyTwice() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.clock.at.set(intent.expiresAt());
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var webhook = threads.submit(() -> {
                try { f.receive(9555, intent.code(), "600000"); f.receive(9555, intent.code(), "600000"); }
                catch (Exception ex) { throw new RuntimeException(ex); }
                f.app.getBean(PaymentMaintenanceJob.class).scan();
            });
            var job = threads.submit(() -> f.app.getBean(PaymentMaintenanceJob.class).scan());
            webhook.get(); job.get();
        }
        eventually(() -> {
            f.app.getBean(PaymentMaintenanceJob.class).scan();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM webhook_inbox WHERE status='PROCESSED'", Long.class)).isEqualTo(1);
        });
        drain();
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.count("webhook_inbox")).isEqualTo(1);
        long settled = f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations", Long.class);
        assertThat(settled).isIn(0L, 600000L);
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts", Long.class)).isEqualTo(settled);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents", String.class)).isEqualTo(settled == 0 ? "EXPIRED" : "RECEIVED");
    }

    @Test void notificationFailureRollsBackMarkerAndEveryRecipientThenRetryDeduplicatesAdminCollector() throws Exception {
        long id = readyRefund();
        f.jdbc.update("UPDATE payment_obligations SET business_key='SUBSCRIPTION:1',purpose='SUBSCRIPTION',subject_type='SUBSCRIPTION_ORDER',payer_user_id=2,collector_user_id=1");
        f.jdbc.update("UPDATE refunds SET recipient_user_id=2,payout_channel_id=104");
        f.clock.at.set(f.clock.instant().plusSeconds(3600));
        f.jdbc.execute("CREATE TRIGGER fixture_overdue_failure BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='REFUND_OVERDUE' AND NEW.user_id=1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Fixture notification failure'; END IF; END");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.app.getBean(PaymentMaintenanceWork.class).notifyOverdue(id)).isInstanceOf(RuntimeException.class);
            assertThat(overdueCount()).isZero();
            assertThat(f.jdbc.queryForObject("SELECT overdue_notified_at FROM refunds", java.sql.Timestamp.class)).isNull();
        } finally { f.jdbc.execute("DROP TRIGGER fixture_overdue_failure"); }
        f.app.getBean(PaymentMaintenanceWork.class).notifyOverdue(id);
        assertThat(f.jdbc.queryForList("SELECT user_id FROM notifications WHERE type='REFUND_OVERDUE' ORDER BY user_id", Long.class)).containsExactly(1L, 2L);
    }

    @Test void laterExpiryRowsProgressPastAnEntireBatchOfInvalidDomainRows() throws Exception {
        for (int i = 0; i < 50; i++) {
            long subject = 10000 + i, obligation = 10000 + i;
            f.jdbc.update("""
                    INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,collector_user_id,
                    amount_due,amount_settled,status,created_at,updated_at)
                    SELECT ?,?,'ADVANCE','BOOKING',?,4,2,600000,0,'OPEN',created_at,updated_at FROM payment_obligations WHERE id=201
                    """, obligation, "BOOKING:" + subject + ":ADVANCE", subject);
            f.jdbc.update("""
                    INSERT INTO payment_intents(obligation_id,channel_id,code,amount,status,created_at,expires_at)
                    SELECT ?,101,?,600000,'ACTIVE',created_at,created_at + INTERVAL 1 SECOND FROM payment_obligations WHERE id=201
                    """, obligation, "CRFIXTURE" + i);
        }
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.clock.at.set(intent.expiresAt());
        var job = f.app.getBean(PaymentMaintenanceJob.class);
        job.scan(); drain(); job.scan();
        eventually(() -> assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=?", String.class, intent.id())).isEqualTo("EXPIRED"));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents WHERE status='ACTIVE'", Long.class)).isEqualTo(50);
    }

    @Test void overdueIsAtomicAndDeduplicatedAcrossConcurrentCallsAndRestart() throws Exception {
        long id = readyRefund();
        f.clock.at.set(f.clock.instant().plusSeconds(3599));
        f.app.getBean(PaymentMaintenanceWork.class).notifyOverdue(id);
        assertThat(overdueCount()).isZero();
        f.clock.at.set(f.clock.instant().plusSeconds(1));
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> f.app.getBean(PaymentMaintenanceWork.class).notifyOverdue(id));
            var second = threads.submit(() -> f.app.getBean(PaymentMaintenanceWork.class).notifyOverdue(id));
            first.get(); second.get();
        }
        assertThat(f.jdbc.queryForList("SELECT user_id FROM notifications WHERE type='REFUND_OVERDUE' ORDER BY user_id", Long.class))
                .containsExactly(1L, 2L, 4L);
        f.restart(true);
        f.app.getBean(PaymentMaintenanceWork.class).notifyOverdue(id);
        assertThat(overdueCount()).isEqualTo(3);
        assertThat(f.app.getBean(RefundRecipientService.class).view(id, f.actor(4)).overdue()).isTrue();
    }

    @Test void missingHandlerRetainsInboxAndIntentForARecoveryScan() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        long inbox = f.receive(9123, intent.code(), "600000");
        f.clock.at.set(intent.expiresAt().plusSeconds(1));
        f.restart(false);
        f.app.getBean(PaymentMaintenanceJob.class).scan();
        eventually(() -> assertThat(f.jdbc.queryForObject("SELECT last_error FROM webhook_inbox WHERE id=?", String.class, inbox))
                .isEqualTo("DOMAIN_UNAVAILABLE"));
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents", String.class)).isEqualTo("ACTIVE");
        f.clock.at.set(f.clock.instant().plusSeconds(301));
        f.restart(true);
        f.app.getBean(PaymentMaintenanceJob.class).scan();
        eventually(() -> assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox", String.class)).isEqualTo("PROCESSED"));
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations", Long.class)).isZero();
    }

    static long overdueCount() { return f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_OVERDUE'", Long.class); }

    static long readyRefund() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, intent.code(), "600000"));
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        long id = f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt, receipt, 540000, RefundReason.CUSTOMER_CANCEL);
        var form = new RefundRecipientForm(); form.setBankBin("970422"); form.setAccountNumber("000123456");
        form.setConfirmedAccountName("NGUYEN TEST");
        f.app.getBean(RefundRecipientService.class).submit(id, form, f.actor(4));
        return id;
    }

    @Test void firstScanAfterRestartExpiresWorkWhoseDeadlinePassedWhileStopped() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.clock.at.set(intent.expiresAt().plusSeconds(1));
        f.restart(true);
        f.app.getBean(PaymentMaintenanceJob.class).scan();
        eventually(() -> assertThat(f.payments.status(intent.id(), f.actor(4)).status()).isEqualTo("EXPIRED"));
        eventually(() -> assertThat(f.jdbc.queryForObject("SELECT status FROM payment_obligations", String.class)).isEqualTo("EXPIRED"));
    }

    static void eventually(Runnable assertion) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (true) {
            try { assertion.run(); return; }
            catch (AssertionError ex) { if (System.nanoTime() >= end) throw ex; Thread.sleep(25); }
        }
    }
}
