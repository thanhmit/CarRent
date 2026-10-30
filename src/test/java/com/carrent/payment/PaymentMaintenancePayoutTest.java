package com.carrent.payment;

import com.carrent.payment.provider.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static com.carrent.payment.PaymentMaintenanceJobTest.eventually;

class PaymentMaintenancePayoutTest {
    static PaymentFlowFixture f;
    static FakePayouts provider;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(true, true, Configuration.class); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.reset(); provider = f.app.getBean(FakePayouts.class);
        provider.creates.set(0); provider.queries.set(0); provider.command.set(null); provider.unknown = false; provider.gate = null;
    }
    @AfterEach void drain() throws Exception {
        for (String name : new String[]{"paymentDomainExecutor", "paymentPayoutExecutor"}) {
            var executor = f.app.getBean(name, ThreadPoolTaskExecutor.class);
            eventually(() -> {
                assertThat(executor.getActiveCount()).isZero();
                assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            });
        }
    }

    @Test void unknownAfterRestartIsQueriedWithoutAnotherCreateAndCanSucceedAfterDeadline() throws Exception {
        long id = readyRefund();
        provider.unknown = true;
        f.app.getBean(PaymentMaintenanceJob.class).scan();
        eventually(() -> assertThat(provider.command.get()).isNotNull());
        eventually(() -> assertThat(f.jdbc.queryForObject("SELECT lease_owner FROM refunds", String.class)).isNull());
        PayoutCommand command = provider.command.get();
        f.clock.at.set(f.clock.instant().plusSeconds(3601));
        f.restart(true);
        provider = f.app.getBean(FakePayouts.class);
        provider.command.set(command);
        f.app.getBean(PaymentMaintenanceJob.class).scan();
        eventually(() -> assertThat(f.app.getBean(RefundRecipientService.class).view(id, f.actor(4)).status()).isEqualTo(RefundStatus.SUCCEEDED));
        assertThat(provider.creates.get()).isZero();
        assertThat(provider.queries.get()).isEqualTo(1);
        assertThat(f.count("payout_attempts")).isEqualTo(1);
        assertThat(f.app.getBean(RefundRecipientService.class).view(id, f.actor(4)).overdue()).isFalse();
    }

    @Test void slowProviderDoesNotBlockExpiryAndConcurrentScansDoNotDuplicateTransfer() throws Exception {
        long id = readyRefund();
        var gate = new CountDownLatch(1);
        provider.gate = gate;
        try {
            f.app.getBean(PaymentMaintenanceJob.class).scan();
            eventually(() -> assertThat(provider.creates.get()).isEqualTo(1));
            f.jdbc.update("UPDATE payment_obligations SET status='OPEN',amount_settled=0 WHERE id=201");
            var intent = f.payments.openAttempt(201L, f.actor(4));
            f.clock.at.set(intent.expiresAt().plusSeconds(1));
            var job = f.app.getBean(PaymentMaintenanceJob.class);
            for (int i = 0; i < 4; i++) job.scan();
            eventually(() -> assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=?", String.class, intent.id())).isEqualTo("EXPIRED"));
            assertThat(provider.creates.get()).isEqualTo(1);
        } finally { gate.countDown(); }
        eventually(() -> assertThat(f.app.getBean(RefundRecipientService.class).view(id, f.actor(4)).status()).isEqualTo(RefundStatus.SUCCEEDED));
    }

    @Test void fullExecutorRetainsDurableWorkUntilCapacityReturns() throws Exception {
        long id = readyRefund();
        var executor = f.app.getBean("paymentPayoutExecutor", ThreadPoolTaskExecutor.class);
        var gate = new CountDownLatch(1);
        try {
            while (true) {
                try { executor.execute(() -> await(gate)); }
                catch (RejectedExecutionException ex) { break; }
            }
            f.app.getBean(PaymentMaintenanceJob.class).scan();
            assertThat(f.app.getBean(RefundRecipientService.class).view(id, f.actor(4)).status()).isEqualTo(RefundStatus.READY);
            assertThat(provider.creates.get()).isZero();
        } finally { gate.countDown(); }
        drain();
        for (int i = 0; i < 3; i++) f.app.getBean(PaymentMaintenanceJob.class).scan();
        eventually(() -> assertThat(f.app.getBean(RefundRecipientService.class).view(id, f.actor(4)).status()).isEqualTo(RefundStatus.SUCCEEDED));
        assertThat(provider.creates.get()).isEqualTo(1);
    }

    static long readyRefund() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, intent.code(), "600000"));
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        long id = f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt, receipt, 540000, RefundReason.CUSTOMER_CANCEL);
        var form = new RefundRecipientForm(); form.setBankBin("970422"); form.setAccountNumber("000123456"); form.setConfirmedAccountName("NGUYEN TEST");
        f.app.getBean(RefundRecipientService.class).submit(id, form, f.actor(4));
        return id;
    }
    static void await(CountDownLatch gate) {
        try { if (!gate.await(15, TimeUnit.SECONDS)) throw new AssertionError("Fixture timeout"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
    }
    static class FakePayouts implements PayOsPayoutClient {
        final AtomicInteger creates = new AtomicInteger(), queries = new AtomicInteger();
        final AtomicReference<PayoutCommand> command = new AtomicReference<>();
        volatile boolean unknown;
        volatile CountDownLatch gate;
        public PayoutResult create(Long channel, PayoutCommand value) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            creates.incrementAndGet(); command.set(value);
            if (gate != null) await(gate);
            if (unknown) throw new IllegalStateException("Private provider payload");
            return result(value);
        }
        public PayoutResult get(Long channel, String id) { queries.incrementAndGet(); return result(command.get()); }
        public PayoutResult findByReference(Long channel, String reference) { queries.incrementAndGet(); return result(command.get()); }
        static PayoutResult result(PayoutCommand c) {
            return new PayoutResult("maintenance-fixture", c.referenceId(), TransferOutcome.SUCCEEDED, c.bankBin(), c.accountNumber(), c.amount());
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration { @Bean @Primary FakePayouts payouts() { return new FakePayouts(); } }
}
