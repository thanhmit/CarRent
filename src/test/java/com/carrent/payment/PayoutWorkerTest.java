package com.carrent.payment;

import com.carrent.payment.provider.PayOsPayoutClient;
import com.carrent.payment.provider.PayoutCommand;
import com.carrent.payment.provider.PayoutResult;
import com.carrent.payment.provider.TransferOutcome;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutWorkerTest {
    static PaymentFlowFixture f;
    static FakePayouts provider;
    @BeforeAll static void start() throws Exception {
        f=new PaymentFlowFixture(true,true,Configuration.class);
        provider=f.app.getBean(FakePayouts.class);
    }
    @AfterAll static void stop() throws Exception { if(f!=null) f.close(); }
    @BeforeEach void reset() { f.reset(); provider.reset(); }

    @Test void dispatchPersistsSnapshotBeforeNetworkAndCommitsSuccessExactlyOnce() throws Exception {
        long id=readyRefund();
        provider.onCreate=(channel,command) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(f.count("payout_attempts")).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("PROCESSING");
            assertThat(f.jdbc.queryForObject("SELECT idempotency_key FROM payout_attempts",String.class))
                    .isEqualTo(command.idempotencyKey());
            assertThat(channel).isEqualTo(103);
            assertThat(command.referenceId()).isEqualTo("refund:"+id+":attempt:1");
            assertThat(command.bankBin()).isEqualTo("970422");
            assertThat(command.accountNumber()).isEqualTo("000123456");
            assertThat(command.amount()).isEqualTo(540000);
            return result(command,TransferOutcome.SUCCEEDED);
        };
        var worker=f.app.getBean(PayoutWorker.class);
        worker.dispatch(id); worker.dispatch(id);
        assertThat(provider.creates.get()).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("SUCCEEDED");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts",String.class)).isEqualTo("SUCCEEDED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_SUCCEEDED' AND user_id=4",Long.class)).isEqualTo(1);
        assertThat(f.count("payout_attempts")).isEqualTo(1);
    }

    @Test void lostCreateResponseIsQueriedByReferenceThenProviderIdWithoutAnotherPost() throws Exception {
        long id=readyRefund();
        var accepted=new java.util.concurrent.atomic.AtomicReference<PayoutCommand>();
        provider.onCreate=(channel,command) -> { accepted.set(command); throw new IllegalStateException("Timeout after acceptance: private provider body"); };
        var worker=f.app.getBean(PayoutWorker.class);
        worker.dispatch(id);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts",String.class)).isEqualTo("UNKNOWN");
        long attempt=f.jdbc.queryForObject("SELECT id FROM payout_attempts",Long.class);
        provider.onFind=(channel,reference) -> {
            assertThat(channel).isEqualTo(103);
            assertThat(reference).isEqualTo(accepted.get().referenceId());
            return result(accepted.get(),TransferOutcome.PROCESSING);
        };
        advance(31); worker.reconcile(attempt);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts",String.class)).isEqualTo("PROCESSING");
        provider.onGet=(channel,payout) -> {
            assertThat(channel).isEqualTo(103); assertThat(payout).isEqualTo("fixture-payout-1");
            return result(accepted.get(),TransferOutcome.SUCCEEDED);
        };
        advance(31); worker.reconcile(attempt); worker.reconcile(attempt); worker.dispatch(id);
        assertThat(provider.creates.get()).isEqualTo(1);
        assertThat(provider.finds.get()).isEqualTo(1);
        assertThat(provider.gets.get()).isEqualTo(1);
        assertThat(f.app.getBean(RefundRecipientService.class).view(id,f.actor(4)).status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(f.jdbc.queryForObject("SELECT last_error FROM payout_attempts",String.class)).isNull();
    }

    static void advance(long seconds) { f.clock.at.set(f.clock.instant().plusSeconds(seconds)); }

    @Test void confirmedInsufficientFundsNotifiesTheOwnerOnceAndRetriesWithoutChangingTheDeadline() throws Exception {
        long id=readyRefund();
        var deadline=f.jdbc.queryForMap("SELECT ready_at,due_at FROM refunds");
        provider.onCreate=(channel,command) -> new PayoutResult("fixture-payout-"+provider.creates.get(),command.referenceId(),
                TransferOutcome.NOT_SENT,command.bankBin(),command.accountNumber(),command.amount(),PayoutResult.NotSentReason.INSUFFICIENT_FUNDS);
        var worker=f.app.getBean(PayoutWorker.class);
        worker.dispatch(id); worker.dispatch(id);
        assertThat(provider.creates.get()).isEqualTo(1);
        assertThat(f.app.getBean(RefundRecipientService.class).view(id,f.actor(4)).status()).isEqualTo(RefundStatus.WAITING_FUNDS);
        advance(31); worker.dispatch(id);
        assertThat(provider.creates.get()).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_FUNDS' AND user_id=2",Long.class)).isEqualTo(1);
        provider.onCreate=(channel,command) -> new PayoutResult("fixture-payout-3",command.referenceId(),TransferOutcome.SUCCEEDED,
                command.bankBin(),command.accountNumber(),command.amount());
        advance(31); worker.dispatch(id);
        assertThat(f.app.getBean(RefundRecipientService.class).view(id,f.actor(4)).status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(f.count("payout_attempts")).isEqualTo(3);
        assertThat(f.jdbc.queryForMap("SELECT ready_at,due_at FROM refunds")).isEqualTo(deadline);
        assertThat(f.jdbc.queryForList("SELECT reference_id FROM payout_attempts ORDER BY attempt_no",String.class))
                .containsExactly("refund:"+id+":attempt:1","refund:"+id+":attempt:2","refund:"+id+":attempt:3");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(DISTINCT idempotency_key) FROM payout_attempts",Long.class)).isEqualTo(3);
    }

    @Test void emptyLookupNeverProvesNoTransferAndUnknownBackoffIsBounded() throws Exception {
        long id=readyRefund();
        provider.onCreate=(channel,command) -> { throw new IllegalStateException("Secret provider body must not be stored"); };
        var worker=f.app.getBean(PayoutWorker.class); worker.dispatch(id);
        provider.onFind=(channel,reference) -> null;
        for(long expected : new long[]{60,120,240,300,300}) {
            advance(601); worker.reconcile(attemptId()); worker.dispatch(id);
            var dates=f.jdbc.queryForMap("SELECT updated_at,next_reconcile_at FROM payout_attempts");
            assertThat(java.time.Duration.between((java.time.LocalDateTime)dates.get("updated_at"),
                    (java.time.LocalDateTime)dates.get("next_reconcile_at")).getSeconds()).isEqualTo(expected);
        }
        assertThat(provider.creates.get()).isEqualTo(1);
        assertThat(f.count("payout_attempts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT last_error FROM payout_attempts",String.class)).isEqualTo("PROVIDER_UNCONFIRMED");
        assertThat(f.app.getBean(RefundRecipientService.class).view(id,f.actor(4)).editable()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"reference","amount","bank","account","missing","outcome"})
    void mismatchedOrIncompleteSuccessNeverCompletesOrAllowsAnotherPost(String mismatch) throws Exception {
        long id=readyRefund();
        provider.onCreate=(channel,c) -> mismatch.equals("missing") ? null : new PayoutResult("fixture-payout-1",
                mismatch.equals("reference") ? "other-reference" : c.referenceId(),
                mismatch.equals("outcome") ? null : TransferOutcome.SUCCEEDED,
                mismatch.equals("bank") ? "970436" : c.bankBin(),mismatch.equals("account") ? "999" : c.accountNumber(),
                mismatch.equals("amount") ? 1 : c.amount());
        var worker=f.app.getBean(PayoutWorker.class); worker.dispatch(id); advance(601); worker.dispatch(id);
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("PROCESSING");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts",String.class)).isEqualTo("UNKNOWN");
        assertThat(provider.creates.get()).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_SUCCEEDED'",Long.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"payout_channel_id=104","amount=1","bank_bin='970436'","account_number='999'","account_name='OTHER'"})
    void changedAttemptSnapshotIsBlockedBeforeAnyProviderQuery(String change) throws Exception {
        long id=readyRefund();
        var accepted=new java.util.concurrent.atomic.AtomicReference<PayoutCommand>();
        provider.onCreate=(channel,c) -> { accepted.set(c); return result(c,TransferOutcome.PROCESSING); };
        var worker=f.app.getBean(PayoutWorker.class); worker.dispatch(id);
        f.jdbc.update("UPDATE payout_attempts SET "+change);
        provider.onGet=(channel,value) -> result(accepted.get(),TransferOutcome.SUCCEEDED);
        advance(31); worker.reconcile(attemptId());
        assertThat(provider.gets.get()).isZero();
        assertThat(provider.finds.get()).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("REVIEW_REQUIRED");
    }

    @ParameterizedTest @ValueSource(strings={"disabled","unconfigured","admin-source"})
    void unavailableOrWrongOwnersSourceNeverDispatches(String source) throws Exception {
        long id=readyRefund();
        switch(source) {
            case "disabled" -> f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=103");
            case "unconfigured" -> f.jdbc.update("UPDATE payment_channels SET credential_reference='not-configured' WHERE id=103");
            case "admin-source" -> f.jdbc.update("UPDATE refunds SET payout_channel_id=104 WHERE id=?",id);
        }
        f.app.getBean(PayoutWorker.class).dispatch(id);
        assertThat(provider.creates.get()).isZero();
        assertThat(f.count("payout_attempts")).isZero();
    }

    @Test void disabledSourceDuringResponseIsNotAcceptedAndCanBeReconciledAfterReenable() throws Exception {
        long id=readyRefund();
        var accepted=new java.util.concurrent.atomic.AtomicReference<PayoutCommand>();
        provider.onCreate=(channel,c) -> {
            accepted.set(c); f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=103");
            return result(c,TransferOutcome.SUCCEEDED);
        };
        var worker=f.app.getBean(PayoutWorker.class); worker.dispatch(id);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts",String.class)).isEqualTo("UNKNOWN");
        advance(31); worker.reconcile(attemptId()); assertThat(provider.finds.get()).isZero();
        f.jdbc.update("UPDATE payment_channels SET enabled=1 WHERE id=103");
        provider.onFind=(channel,reference) -> result(accepted.get(),TransferOutcome.SUCCEEDED);
        worker.reconcile(attemptId());
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("SUCCEEDED");
        assertThat(provider.creates.get()).isEqualTo(1);
    }

    @Test void workerRejectsAmbientTransactionsBeforeClaimingOrCallingTheProvider() throws Exception {
        long id=readyRefund();
        f.tx.executeWithoutResult(s -> {
            assertThatThrownBy(() -> f.app.getBean(PayoutWorker.class).dispatch(id))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThatThrownBy(() -> f.app.getBean(PayoutWorker.class).reconcile(1L))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        });
        assertThat(provider.creates.get()).isZero(); assertThat(f.count("payout_attempts")).isZero();
    }

    static long attemptId() { return f.jdbc.queryForObject("SELECT id FROM payout_attempts ORDER BY attempt_no DESC LIMIT 1",Long.class); }

    @ParameterizedTest @ValueSource(strings={"before-send","after-acceptance"})
    void crashesAndContextRestartRecoverTheOriginalAttemptWithoutAnotherPost(String point) throws Exception {
        long id=readyRefund();
        PayoutCommand accepted;
        if(point.equals("before-send")) {
            var claim=f.app.getBean(PayoutTransactions.class).prepareDispatch(id);
            accepted=claim.command();
        } else {
            var captured=new java.util.concurrent.atomic.AtomicReference<PayoutCommand>();
            provider.onCreate=(channel,c) -> { captured.set(c); throw new AssertionError("Simulated process crash after provider acceptance"); };
            assertThatThrownBy(() -> f.app.getBean(PayoutWorker.class).dispatch(id)).isInstanceOf(AssertionError.class);
            accepted=captured.get();
        }
        var original=f.jdbc.queryForMap("SELECT id,reference_id,idempotency_key,payout_channel_id,amount,bank_bin,account_number FROM payout_attempts");
        long attempt=attemptId();
        f.restart(true); provider=f.app.getBean(FakePayouts.class); provider.reset();
        var worker=f.app.getBean(PayoutWorker.class);
        advance(61); worker.dispatch(id);
        provider.onFind=(channel,reference) -> {
            assertThat(channel).isEqualTo(103); assertThat(reference).isEqualTo(accepted.referenceId());
            return point.equals("before-send") ? null : result(accepted,TransferOutcome.SUCCEEDED);
        };
        worker.reconcile(attempt);
        assertThat(provider.creates.get()).isZero();
        assertThat(f.jdbc.queryForMap("SELECT id,reference_id,idempotency_key,payout_channel_id,amount,bank_bin,account_number FROM payout_attempts"))
                .isEqualTo(original);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts",String.class))
                .isEqualTo(point.equals("before-send") ? "UNKNOWN" : "SUCCEEDED");
    }

    @Test void crashAfterProviderSuccessBeforeLocalCommitReconcilesWithoutSendingAgain() throws Exception {
        long id=readyRefund();
        var accepted=new java.util.concurrent.atomic.AtomicReference<PayoutCommand>();
        provider.onCreate=(channel,c) -> { accepted.set(c); return result(c,TransferOutcome.SUCCEEDED); };
        f.db.execute("CREATE TRIGGER reject_refund_success BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='REFUND_SUCCEEDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Fixture commit failure'; END IF; END");
        try {
            assertThatThrownBy(() -> f.app.getBean(PayoutWorker.class).dispatch(id)).isInstanceOf(RuntimeException.class);
            assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts",String.class)).isEqualTo("UNKNOWN");
            assertThat(f.jdbc.queryForObject("SELECT provider_payout_id FROM payout_attempts",String.class)).isNull();
        } finally { f.db.execute("DROP TRIGGER reject_refund_success"); }
        provider.onFind=(channel,reference) -> result(accepted.get(),TransferOutcome.SUCCEEDED);
        advance(61); f.app.getBean(PayoutWorker.class).reconcile(attemptId());
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("SUCCEEDED");
        assertThat(provider.creates.get()).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_SUCCEEDED'",Long.class)).isEqualTo(1);
    }

    @Test void twoWorkersCannotDispatchTogetherAndNetworkHoldsNeitherRowLocksNorCarQueue() throws Exception {
        long id=readyRefund();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        provider.onCreate=(channel,c) -> { entered.countDown(); await(release); return result(c,TransferOutcome.SUCCEEDED); };
        var worker=f.app.getBean(PayoutWorker.class);
        var first=java.util.concurrent.CompletableFuture.runAsync(() -> worker.dispatch(id));
        try {
            assertThat(entered.await(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.CompletableFuture.runAsync(() -> worker.dispatch(id)).get(10,java.util.concurrent.TimeUnit.SECONDS);
            java.util.concurrent.CompletableFuture.runAsync(() -> f.tx.executeWithoutResult(s ->
                    assertThat(f.app.getBean(RefundRepository.class).findByIdForUpdate(id)).isPresent()))
                    .get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(f.app.getBean(com.carrent.concurrency.CarCommandExecutor.class).execute(1L,() -> "queue free")).isEqualTo("queue free");
            assertThat(provider.creates.get()).isEqualTo(1);
        } finally { release.countDown(); }
        first.get(30,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(f.count("payout_attempts")).isEqualTo(1);
    }

    @Test void expiredLeaseAllowsReconciliationButTheLateOriginalResultCannotOverwriteIt() throws Exception {
        long id=readyRefund();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var accepted=new java.util.concurrent.atomic.AtomicReference<PayoutCommand>();
        provider.onCreate=(channel,c) -> {
            accepted.set(c); entered.countDown(); await(release); return result(c,TransferOutcome.UNKNOWN);
        };
        var worker=f.app.getBean(PayoutWorker.class);
        var first=java.util.concurrent.CompletableFuture.runAsync(() -> worker.dispatch(id));
        try {
            assertThat(entered.await(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            advance(61); worker.dispatch(id);
            provider.onFind=(channel,reference) -> result(accepted.get(),TransferOutcome.SUCCEEDED);
            worker.reconcile(attemptId());
            assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("SUCCEEDED");
        } finally { release.countDown(); }
        first.get(30,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payout_attempts",String.class)).isEqualTo("SUCCEEDED");
        assertThat(provider.creates.get()).isEqualTo(1);
    }

    @Test void twoReconcilersUseOneLiveLease() throws Exception {
        long id=readyRefund();
        var accepted=new java.util.concurrent.atomic.AtomicReference<PayoutCommand>();
        provider.onCreate=(channel,c) -> { accepted.set(c); throw new IllegalStateException("Fixture lost response"); };
        var worker=f.app.getBean(PayoutWorker.class); worker.dispatch(id); advance(31);
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        provider.onFind=(channel,reference) -> { entered.countDown(); await(release); return result(accepted.get(),TransferOutcome.SUCCEEDED); };
        long attempt=attemptId();
        var first=java.util.concurrent.CompletableFuture.runAsync(() -> worker.reconcile(attempt));
        try {
            assertThat(entered.await(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.CompletableFuture.runAsync(() -> worker.reconcile(attempt)).get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(provider.finds.get()).isEqualTo(1);
        } finally { release.countDown(); }
        first.get(30,java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test void anExpiredPreparedClaimCannotStartSending() throws Exception {
        long id=readyRefund();
        var transactions=f.app.getBean(PayoutTransactions.class);
        var old=transactions.prepareDispatch(id); advance(60);
        assertThat(transactions.beginSend(old)).isFalse();
        f.app.getBean(PayoutWorker.class).dispatch(id);
        assertThat(provider.creates.get()).isZero();
        assertThat(f.count("payout_attempts")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"RETRYABLE","PERMANENT","UNCLASSIFIED"})
    void onlyAClassifiedRetryableNotSentResultCanOpenTheNextAttempt(String reason) throws Exception {
        long id=readyRefund();
        provider.onCreate=(channel,c) -> new PayoutResult("fixture-payout-1",c.referenceId(),TransferOutcome.NOT_SENT,
                c.bankBin(),c.accountNumber(),c.amount(),reason.equals("UNCLASSIFIED") ? null : PayoutResult.NotSentReason.valueOf(reason));
        var worker=f.app.getBean(PayoutWorker.class); worker.dispatch(id);
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class))
                .isEqualTo(reason.equals("RETRYABLE") ? "READY" : "REVIEW_REQUIRED");
        provider.onCreate=(channel,c) -> new PayoutResult("fixture-payout-2",c.referenceId(),TransferOutcome.SUCCEEDED,c.bankBin(),c.accountNumber(),c.amount());
        advance(601); worker.dispatch(id);
        assertThat(provider.creates.get()).isEqualTo(reason.equals("RETRYABLE") ? 2 : 1);
    }

    @Test void knownProviderIdCannotBeReplacedByADifferentSignedPayout() throws Exception {
        long id=readyRefund();
        var accepted=new java.util.concurrent.atomic.AtomicReference<PayoutCommand>();
        provider.onCreate=(channel,c) -> { accepted.set(c); return result(c,TransferOutcome.PROCESSING); };
        var worker=f.app.getBean(PayoutWorker.class); worker.dispatch(id);
        provider.onGet=(channel,value) -> new PayoutResult("other-provider-id",accepted.get().referenceId(),TransferOutcome.SUCCEEDED,
                accepted.get().bankBin(),accepted.get().accountNumber(),accepted.get().amount());
        advance(31); worker.reconcile(attemptId());
        assertThat(f.jdbc.queryForObject("SELECT provider_payout_id FROM payout_attempts",String.class)).isEqualTo("fixture-payout-1");
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("PROCESSING");
        provider.onGet=(channel,value) -> result(accepted.get(),TransferOutcome.SUCCEEDED);
        advance(601); worker.reconcile(attemptId());
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("SUCCEEDED");
        assertThat(provider.creates.get()).isEqualTo(1);
    }

    static void await(java.util.concurrent.CountDownLatch latch) {
        try { if(!latch.await(30,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Fixture barrier timeout"); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
    }

    @Test void adminSubscriptionRefundUsesItsOwnChannelAndNotifiesAdminForFunding() throws Exception {
        f.jdbc.update("""
                UPDATE payment_obligations SET business_key='SUBSCRIPTION:1',purpose='SUBSCRIPTION',
                subject_type='SUBSCRIPTION_ORDER',payer_user_id=2,collector_user_id=1,amount_due=5000 WHERE id=201
                """);
        var intent=f.payments.openAttempt(201L,f.actor(2)); f.payments.cancelAttempt(intent.id(),f.actor(2));
        byte[] body=new String(SePayInboxTest.body(9001,intent.code(),"5000"),java.nio.charset.StandardCharsets.UTF_8)
                .replace("000123456","000999999").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L,f.signed(body,"another-fixture-secret"),body);
        f.app.getBean(ReceiptProcessor.class).process(f.jdbc.queryForObject("SELECT id FROM webhook_inbox",Long.class));
        long receipt=f.jdbc.queryForObject("SELECT id FROM payment_receipts",Long.class);
        long id=f.app.getBean(RefundService.class).request("CANCELLED_SUBSCRIPTION:"+receipt,receipt,5000,RefundReason.CANCELLED_SUBSCRIPTION);
        var form=new RefundRecipientForm(); form.setBankBin("970422"); form.setAccountNumber("000123456"); form.setConfirmedAccountName("NGUYEN TEST");
        f.app.getBean(RefundRecipientService.class).submit(id,form,f.actor(2));
        provider.onCreate=(channel,c) -> {
            assertThat(channel).isEqualTo(104);
            return new PayoutResult("admin-payout-1",c.referenceId(),TransferOutcome.NOT_SENT,c.bankBin(),c.accountNumber(),c.amount(),
                    PayoutResult.NotSentReason.INSUFFICIENT_FUNDS);
        };
        var worker=f.app.getBean(PayoutWorker.class); worker.dispatch(id);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_FUNDS' AND user_id=1",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_FUNDS' AND user_id=2",Long.class)).isZero();
        provider.onCreate=(channel,c) -> {
            assertThat(channel).isEqualTo(104);
            return new PayoutResult("admin-payout-2",c.referenceId(),TransferOutcome.SUCCEEDED,c.bankBin(),c.accountNumber(),c.amount());
        };
        advance(31); worker.dispatch(id);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_SUCCEEDED' AND user_id=2",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(5000);
    }

    @Test void credentialResolverSeparatesOwnerAndAdminAndRejectsCollectOrDisabledChannels() throws Exception {
        var resolver=f.app.getBean(PayoutChannelResolver.class);
        assertThat(resolver.resolve(103L).clientId()).isEqualTo("fixture-owner");
        assertThat(resolver.resolve(104L).clientId()).isEqualTo("fixture-admin");
        assertThatThrownBy(() -> resolver.resolve(101L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver.resolve(999L)).isInstanceOf(IllegalStateException.class);
        f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=103");
        assertThatThrownBy(() -> resolver.resolve(103L)).isInstanceOf(IllegalStateException.class);
    }

    static long readyRefund() throws Exception {
        var intent=f.payments.openAttempt(201L,f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001,intent.code(),"600000"));
        long receipt=f.jdbc.queryForObject("SELECT id FROM payment_receipts",Long.class);
        long id=f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:"+receipt,receipt,540000,RefundReason.CUSTOMER_CANCEL);
        var form=new RefundRecipientForm(); form.setBankBin("970422"); form.setAccountNumber("000123456");
        form.setConfirmedAccountName("NGUYEN TEST");
        f.app.getBean(RefundRecipientService.class).submit(id,form,f.actor(4));
        return id;
    }

    static PayoutResult result(PayoutCommand command,TransferOutcome outcome) {
        return new PayoutResult("fixture-payout-1",command.referenceId(),outcome,
                command.bankBin(),command.accountNumber(),command.amount());
    }

    @FunctionalInterface interface Create { PayoutResult run(Long channel,PayoutCommand command); }
    @FunctionalInterface interface Query { PayoutResult run(Long channel,String value); }
    static class FakePayouts implements PayOsPayoutClient {
        final AtomicInteger creates=new AtomicInteger();
        final AtomicInteger gets=new AtomicInteger();
        final AtomicInteger finds=new AtomicInteger();
        volatile Create onCreate;
        volatile Query onGet;
        volatile Query onFind;
        void reset() {
            creates.set(0); gets.set(0); finds.set(0);
            onCreate=(channel,command) -> result(command,TransferOutcome.SUCCEEDED);
            onGet=(channel,value) -> { throw new AssertionError("Unexpected get"); };
            onFind=(channel,value) -> { throw new AssertionError("Unexpected lookup"); };
        }
        private void outsideTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
        public PayoutResult create(Long channel,PayoutCommand command) { outsideTransaction(); creates.incrementAndGet(); return onCreate.run(channel,command); }
        public PayoutResult get(Long channel,String id) { outsideTransaction(); gets.incrementAndGet(); return onGet.run(channel,id); }
        public PayoutResult findByReference(Long channel,String reference) { outsideTransaction(); finds.incrementAndGet(); return onFind.run(channel,reference); }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Configuration {
        @Bean @Primary FakePayouts fakePayouts() { return new FakePayouts(); }
    }
}
