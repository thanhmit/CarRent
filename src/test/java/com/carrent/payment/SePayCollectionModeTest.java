package com.carrent.payment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static com.carrent.payment.PaymentMaintenanceJobTest.eventually;

class SePayCollectionModeTest {
    static PaymentFlowFixture f;
    static PaymentMaintenancePayoutTest.FakePayouts provider;

    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.payment.payout.enabled", "false");
        try { f = new PaymentFlowFixture(true, true, PaymentMaintenancePayoutTest.Configuration.class); }
        finally { System.clearProperty("carrent.payment.payout.enabled"); }
        provider = f.app.getBean(PaymentMaintenancePayoutTest.FakePayouts.class);
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); provider.creates.set(0); provider.queries.set(0); }

    @Test void collectionWorkerProcessesIncomingMoneyButNeverDispatchesOrQueriesPayouts() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.receive(92001, intent.code(), "600000");
        var job = f.app.getBean(PaymentMaintenanceJob.class);
        job.scan();
        eventually(() -> assertThat(f.payments.status(intent.id(), f.actor(4)).status()).isEqualTo("RECEIVED"));
        drain();
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        long refund = f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt,
                receipt, 540000, RefundReason.CUSTOMER_CANCEL);
        var form = new RefundRecipientForm();
        form.setBankBin("970422"); form.setAccountNumber("000123456");
        f.app.getBean(RefundRecipientService.class).submit(refund, form, f.actor(4));
        job.scan(); drain();
        assertThat(provider.creates.get()).isZero();
        assertThat(f.count("payout_attempts")).isZero();

        var transactions = f.app.getBean(PayoutTransactions.class);
        var claim = transactions.prepareDispatch(refund);
        assertThat(transactions.beginSend(claim)).isTrue();
        f.clock.at.set(f.clock.instant().plusSeconds(31));
        job.scan(); drain();
        assertThat(provider.queries.get()).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts", String.class)).isEqualTo("UNKNOWN");
    }

    static void drain() throws Exception {
        for (String name : new String[]{"paymentDomainExecutor", "paymentPayoutExecutor"}) {
            var executor = f.app.getBean(name, ThreadPoolTaskExecutor.class);
            eventually(() -> {
                assertThat(executor.getActiveCount()).isZero();
                assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            });
        }
    }
}
