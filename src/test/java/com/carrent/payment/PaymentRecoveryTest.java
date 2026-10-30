package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.concurrency.CarCommandExecutor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRecoveryTest {
    @Test void missingHandlerKeepsDurableWorkAndRestartCanRecoverItAfterRegistration() throws Exception {
        try (var f = new PaymentFlowFixture(false)) {
            f.reset();
            assertThatThrownBy(() -> f.payments.openAttempt(201L, f.actor(4))).isInstanceOf(BusinessException.class);
            f.jdbc.update("""
                    INSERT INTO payment_intents(id,obligation_id,channel_id,code,amount,status,created_at,expires_at)
                    VALUES(301,201,101,'CRPENDINGDOMAIN',600000,'ACTIVE',?,?)
                    """, java.time.LocalDateTime.ofInstant(PaymentFlowFixture.NOW, java.time.ZoneOffset.UTC),
                    java.time.LocalDateTime.ofInstant(PaymentFlowFixture.NOW.plusSeconds(900), java.time.ZoneOffset.UTC));
            assertThat(f.payments.status(301L, f.actor(4)).qrUrl()).isNull();
            long inbox = f.receive(9001, "CRPENDINGDOMAIN", "600000");
            f.app.getBean(ReceiptProcessor.class).process(inbox);
            assertThat(f.count("payment_receipts")).isZero();
            assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox", String.class)).isEqualTo("RECEIVED");
            assertThat(f.jdbc.queryForObject("SELECT last_error FROM webhook_inbox", String.class)).isEqualTo("DOMAIN_UNAVAILABLE");
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(30));
            f.restart(true);
            f.app.getBean(ReceiptProcessor.class).process(inbox);
            assertThat(f.payments.status(301L, f.actor(4)).status()).isEqualTo("RECEIVED");
            assertThat(f.count("payment_receipts")).isEqualTo(1);
        }
    }

    @Test void crashAfterCommittedClaimAndQueueRejectionBothKeepWorkRecoverable() throws Exception {
        try (var f = new PaymentFlowFixture()) {
            f.reset();
            var view = f.payments.openAttempt(201L, f.actor(4));
            long inbox = f.receive(9001, view.code(), "600000");
            f.restart(true); // Inbox commit survives losing all process memory before any worker claim.
            var work = f.app.getBean(InboxWorkService.class);
            var abandoned = work.claim(inbox);
            f.restart(true);
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(60));
            work = f.app.getBean(InboxWorkService.class);
            f.app.getBean(CarCommandExecutor.class).close();
            f.app.getBean(ReceiptProcessor.class).process(inbox);
            assertThat(f.count("payment_receipts")).isZero();
            assertThat(f.jdbc.queryForObject("SELECT last_error FROM webhook_inbox", String.class)).isEqualTo("QUEUE_UNAVAILABLE");
            f.app.getBean(ReceiptApplicationService.class).apply(inbox, abandoned.leaseOwner());
            assertThat(f.count("payment_receipts")).isZero();
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(90));
            f.restart(true);
            f.app.getBean(ReceiptProcessor.class).process(inbox);
            assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("RECEIVED");
        }
    }
}
