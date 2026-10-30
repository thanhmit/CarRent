package com.carrent.payment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.carrent.common.exception.BusinessException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundServiceTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); }

    @Test void cancellationCreatesOneObligationAndNotifiesItsRecipientOnce() throws Exception {
        long receipt = paidReceipt();
        var refunds = f.app.getBean(RefundService.class);
        String key = "CUSTOMER_CANCEL:1:" + receipt;
        long id = refunds.request(key, receipt, 540000, RefundReason.CUSTOMER_CANCEL);
        assertThat(refunds.request(key, receipt, 540000, RefundReason.CUSTOMER_CANCEL)).isEqualTo(id);
        assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT payout_channel_id FROM refunds", Long.class)).isEqualTo(103);
        assertThat(f.jdbc.queryForObject("SELECT recipient_user_id FROM refunds", Long.class)).isEqualTo(4);
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("WAITING_RECIPIENT");
        assertThat(f.jdbc.queryForObject("SELECT ready_at FROM refunds", java.sql.Timestamp.class)).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_RECIPIENT' AND user_id=4", Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM payment_receipts WHERE id=?", Long.class, receipt)).isEqualTo(600000);
        assertThat(f.count("payout_attempts")).isZero();
    }

    @ParameterizedTest @ValueSource(longs={0,-1,600001,Long.MAX_VALUE})
    void invalidOrExcessiveAmountsDoNotCreateRefunds(long amount) throws Exception {
        long receipt = paidReceipt();
        assertThatThrownBy(() -> f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt,
                receipt, amount, RefundReason.CUSTOMER_CANCEL)).isInstanceOf(BusinessException.class);
        assertThat(f.count("refunds")).isZero();
    }

    @Test void businessKeyAndRepeatedEventMustMatchTheReceiptReasonAndAmount() throws Exception {
        long receipt = paidReceipt();
        var service = f.app.getBean(RefundService.class);
        assertThatThrownBy(() -> service.request("CUSTOMER_CANCEL:99:" + receipt, receipt, 540000,
                RefundReason.CUSTOMER_CANCEL)).isInstanceOf(BusinessException.class);
        service.request("CUSTOMER_CANCEL:1:" + receipt, receipt, 540000, RefundReason.CUSTOMER_CANCEL);
        assertThatThrownBy(() -> service.request("CUSTOMER_CANCEL:1:" + receipt, receipt, 500000,
                RefundReason.CUSTOMER_CANCEL)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.request("OWNER_CANCEL:1:" + receipt, receipt, 60001,
                RefundReason.OWNER_CANCEL)).isInstanceOf(BusinessException.class);
        assertThat(f.jdbc.queryForObject("SELECT SUM(amount) FROM refunds", Long.class)).isEqualTo(540000);
    }

    @Test void balanceExcessIsLimitedToUnallocatedMoneyAndOriginalReceiptIsKept() throws Exception {
        f.jdbc.update("UPDATE payment_obligations SET purpose='BALANCE',business_key='BOOKING:1:BALANCE' WHERE id=201");
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001,intent.code(),"650000"));
        long receipt=f.jdbc.queryForObject("SELECT id FROM payment_receipts",Long.class);
        var service=f.app.getBean(RefundService.class);
        assertThatThrownBy(() -> service.request("EXCESS:"+receipt,receipt,50001,RefundReason.EXCESS_BALANCE))
                .isInstanceOf(BusinessException.class);
        service.request("EXCESS:"+receipt,receipt,50000,RefundReason.EXCESS_BALANCE);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM payment_receipts",Long.class)).isEqualTo(650000);
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts",Long.class)).isEqualTo(600000);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(50000);
    }

    @Test void duplicateTransferHasItsOwnRefundAndCannotSpendTheAppliedReceipt() throws Exception {
        long first=paidReceipt();
        String code=f.jdbc.queryForObject("SELECT code FROM payment_intents",String.class);
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9002,code,"600000"));
        long second=f.jdbc.queryForObject("SELECT id FROM payment_receipts WHERE provider_transaction_id='9002'",Long.class);
        var service=f.app.getBean(RefundService.class);
        assertThatThrownBy(() -> service.request("DUPLICATE:"+first,first,600000,RefundReason.DUPLICATE_PAYMENT))
                .isInstanceOf(BusinessException.class);
        service.request("DUPLICATE:"+second,second,600000,RefundReason.DUPLICATE_PAYMENT);
        assertThat(f.jdbc.queryForObject("SELECT receipt_id FROM refunds",Long.class)).isEqualTo(second);
    }

    @Test void unknownOrMismatchedReceiptCannotBeAutomaticallyRefunded() throws Exception {
        var intent=f.payments.openAttempt(201L,f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001,intent.code(),"500000"));
        long receipt=f.jdbc.queryForObject("SELECT id FROM payment_receipts",Long.class);
        assertThatThrownBy(() -> f.app.getBean(RefundService.class).request("DUPLICATE:"+receipt,receipt,500000,
                RefundReason.DUPLICATE_PAYMENT)).isInstanceOf(BusinessException.class);
        assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts",String.class)).isEqualTo("REVIEW_REQUIRED");
    }

    @Test void missingOwnersPayoutChannelDoesNotFallBackToAdmin() throws Exception {
        long receipt=paidReceipt();
        f.jdbc.update("DELETE FROM payment_channels WHERE id=103");
        assertThatThrownBy(() -> f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:"+receipt,receipt,540000,
                RefundReason.CUSTOMER_CANCEL)).isInstanceOf(BusinessException.class);
        assertThat(f.count("refunds")).isZero();
    }

    @Test void concurrentRepeatedEventsCreateOneRefundAndOneNotification() throws Exception {
        long receipt=paidReceipt();
        var start=new CountDownLatch(1);
        var service=f.app.getBean(RefundService.class);
        var a=CompletableFuture.supplyAsync(() -> { await(start); return service.request("CUSTOMER_CANCEL:1:"+receipt,receipt,540000,RefundReason.CUSTOMER_CANCEL); });
        var b=CompletableFuture.supplyAsync(() -> { await(start); return service.request("CUSTOMER_CANCEL:1:"+receipt,receipt,540000,RefundReason.CUSTOMER_CANCEL); });
        start.countDown();
        assertThat(a.get(30,TimeUnit.SECONDS)).isEqualTo(b.get(30,TimeUnit.SECONDS));
        assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_RECIPIENT'",Long.class)).isEqualTo(1);
    }

    @Test void currentRefundBudgetIsReadEvenInsideAnOlderRepeatableReadSnapshot() throws Exception {
        long receipt=paidReceipt();
        var service=f.app.getBean(RefundService.class);
        f.tx.executeWithoutResult(status -> {
            f.em.find(PaymentReceipt.class,receipt);
            assertThat(f.app.getBean(RefundRepository.class).findByReceiptId(receipt)).isEmpty();
            try {
                CompletableFuture.runAsync(() -> service.request("CUSTOMER_CANCEL:1:"+receipt,receipt,300000,
                        RefundReason.CUSTOMER_CANCEL)).get(30,TimeUnit.SECONDS);
            } catch(Exception ex) { throw new RuntimeException(ex); }
            assertThatThrownBy(() -> service.request("OWNER_CANCEL:1:"+receipt,receipt,400000,RefundReason.OWNER_CANCEL))
                    .isInstanceOf(BusinessException.class);
            status.setRollbackOnly();
        });
        assertThat(f.jdbc.queryForObject("SELECT SUM(amount) FROM refunds",Long.class)).isEqualTo(300000);
    }

    @Test void notificationFailureRollsBackRefundAndRetryCanCreateItOnce() throws Exception {
        long receipt=paidReceipt();
        f.db.execute("CREATE TRIGGER reject_refund_notification BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='REFUND_WAITING_RECIPIENT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Fixture notification failure'; END IF; END");
        try {
            assertThatThrownBy(() -> f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:"+receipt,receipt,540000,
                    RefundReason.CUSTOMER_CANCEL)).isInstanceOf(RuntimeException.class);
            assertThat(f.count("refunds")).isZero();
        } finally { f.db.execute("DROP TRIGGER reject_refund_notification"); }
        f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:"+receipt,receipt,540000,RefundReason.CUSTOMER_CANCEL);
        assertThat(f.count("refunds")).isEqualTo(1);
    }

    static void await(CountDownLatch latch) {
        try { if(!latch.await(30,TimeUnit.SECONDS)) throw new AssertionError("Barrier timeout"); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
    }

    static long paidReceipt() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, intent.code(), "600000"));
        return f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
    }
}
