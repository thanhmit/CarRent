package com.carrent.payment;

import com.carrent.booking.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RentalBalanceTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.rental.balance.enabled","true");
        try { f=new PaymentFlowFixture(false); }
        finally { System.clearProperty("carrent.rental.balance.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if(f!=null)f.close(); }
    @BeforeEach void ready() {
        f.jdbc.update("DELETE FROM notifications"); f.reset();
        f.jdbc.update("UPDATE users SET role='CUSTOMER' WHERE id=4");
        f.jdbc.update("UPDATE users SET role='OWNER' WHERE id=2");
        f.jdbc.update("UPDATE bookings SET status='CONFIRMED',workflow_version='VIETQR_V1',inspection_required=1,advance_amount=600000,balance_amount=1400000,owner_accepted_at='2026-10-28',before_accepted_at='2026-10-28',advance_paid_at='2026-10-28',vehicle_received_at='2026-10-29',contract_confirmed_at='2026-10-29',contract_confirmed_by=2,vehicle_returned_at='2026-10-30 03:00:00',balance_overdue_notified_at=NULL WHERE id=1");
        f.jdbc.update("UPDATE payment_obligations SET business_key='BOOKING:1:BALANCE',purpose='BALANCE',amount_due=1400000,due_at='2026-10-30 04:00:00' WHERE id=201");
    }
    PaymentIntentView open() { return f.payments.openAttempt(201L,f.actor(4)); }
    String bookingStatus(){return f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class);}
    @Test void cancelledAttemptKeepsDebtAndOverdueDebtCanOpenFullFifteenMinuteQr() {
        var first=open(); f.payments.cancelAttempt(first.id(),f.actor(4));
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(4000));
        var next=open();
        assertThat(next.id()).isNotEqualTo(first.id());
        assertThat(next.expiresAt()).isEqualTo(f.clock.instant().plusSeconds(900));
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201",Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT due_at FROM payment_obligations WHERE id=201",LocalDateTime.class)).isEqualTo(LocalDateTime.parse("2026-10-30T04:00:00"));
    }
    long enqueue(PaymentIntentView intent,long amount,long tx) throws Exception {
        var date=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(f.clock.instant());
        byte[] body=new String(SePayInboxTest.body(tx,intent.code(),Long.toString(amount)),java.nio.charset.StandardCharsets.UTF_8).replace("2026-10-30 10:00:00",date).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(101L,f.signed(body),body);
        long id=f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?",Long.class,Long.toString(tx));
        return id;
    }
    long receive(PaymentIntentView intent,long amount,long tx) throws Exception {
        long id=enqueue(intent,amount,tx);f.app.getBean(ReceiptProcessor.class).process(id);return id;
    }
    @ParameterizedTest @ValueSource(strings={"active","expired","cancelled","after-due"})
    void fullReceiptCompletesEvenWithOldQr(String state) throws Exception {
        var intent=open();
        if(state.equals("cancelled")) f.payments.cancelAttempt(intent.id(),f.actor(4));
        if(state.equals("expired")){f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(901));f.app.getBean(ReceiptProcessor.class).expire(intent.id());}
        if(!state.equals("active")){f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(4000));open();}
        receive(intent,1400000,501);
        assertThat(bookingStatus()).isEqualTo("COMPLETED");
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201",Long.class)).isEqualTo(1400000);
        assertThat(f.count("refunds")).isZero();
    }
    @ParameterizedTest @ValueSource(longs={1399999,1400000,1500000})
    void shortFullAndExcessHaveDistinctOutcomes(long amount) throws Exception {
        receive(open(),amount,601);
        assertThat(bookingStatus()).isEqualTo(amount<1400000?"CONFIRMED":"COMPLETED");
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201",Long.class)).isEqualTo(amount<1400000?0:1400000);
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts",String.class)).isEqualTo(amount<1400000?"REVIEW_REQUIRED":amount==1400000?"APPLIED":"REFUND_REQUIRED");
        assertThat(f.count("refunds")).isEqualTo(amount>1400000?1:0);
        if(amount>1400000)assertThat(f.jdbc.queryForMap("SELECT amount,reason,payout_channel_id FROM refunds")).containsEntry("amount",100000L).containsEntry("reason","EXCESS_BALANCE").containsEntry("payout_channel_id",103L);
    }
    @Test void duplicateReceiptAfterCompletionRefundsOwnerAndProviderReplayIsIdempotent() throws Exception {
        var intent=open();receive(intent,1400000,701);receive(intent,1400000,702);receive(intent,1400000,702);
        assertThat(bookingStatus()).isEqualTo("COMPLETED");assertThat(f.count("payment_receipts")).isEqualTo(2);
        assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(f.jdbc.queryForMap("SELECT amount,reason,payout_channel_id FROM refunds")).containsEntry("amount",1400000L).containsEntry("reason","DUPLICATE_PAYMENT").containsEntry("payout_channel_id",103L);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_COMPLETED'",Long.class)).isEqualTo(2);
    }
    @Test void failureToNotifyRollsBackReceiptDebtAndBookingAndRetryWorks() throws Exception {
        var intent=open();
        f.jdbc.execute("CREATE TRIGGER fail_balance_notice BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='BOOKING_COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='offline failure'; END IF; END");
        long inbox;
        try { inbox=receive(intent,1400000,801); } finally { f.jdbc.execute("DROP TRIGGER fail_balance_notice"); }
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");assertThat(f.count("payment_receipts")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201",Long.class)).isZero();
        f.clock.at.set(f.clock.instant().plusSeconds(3600));f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(bookingStatus()).isEqualTo("COMPLETED");
    }
    @Test void expiredActiveAttemptCanBeReplacedByPostWithoutMaintenance() {
        var first=open();f.clock.at.set(f.clock.instant().plusSeconds(901));var next=open();
        assertThat(next.id()).isNotEqualTo(first.id());
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=?",String.class,first.id())).isEqualTo("EXPIRED");
    }
    @Test void completedManualAndLegacyAndUnreturnedCannotUseBalanceWorkflow() {
        var bookings=f.app.getBean(BookingService.class);
        assertThatThrownBy(()->bookings.completeBooking(1L,f.actor(2))).isInstanceOf(RuntimeException.class);
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY' WHERE id=1");assertThatThrownBy(this::open).isInstanceOf(RuntimeException.class);
        f.jdbc.update("UPDATE bookings SET workflow_version='VIETQR_V1',vehicle_returned_at=NULL WHERE id=1");assertThatThrownBy(this::open).isInstanceOf(RuntimeException.class);
    }
    @Test void competingOldAndNewQrReceiptsSettleOnceAndRefundTheOther() throws Exception {
        var a=open();f.payments.cancelAttempt(a.id(),f.actor(4));var b=open();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var x=pool.submit(()->{try{return receive(a,1400000,901);}catch(Exception e){throw new RuntimeException(e);}});
            var y=pool.submit(()->{try{return receive(b,1500000,902);}catch(Exception e){throw new RuntimeException(e);}});
            x.get(20,java.util.concurrent.TimeUnit.SECONDS);y.get(20,java.util.concurrent.TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(bookingStatus()).isEqualTo("COMPLETED");
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts",Long.class)).isEqualTo(1400000);
        assertThat(f.jdbc.queryForObject("SELECT SUM(amount) FROM refunds",Long.class)).isEqualTo(1500000);
    }
    @Test void staleSnapshotMustNotAllowBannedPayerToOpenQr() {
        var actor=f.actor(4);var route=f.app.getBean(PaymentRoutes.class).obligation(201L);
        assertThatThrownBy(()->f.tx.execute(s->{
            f.em.find(com.carrent.user.User.class,4L).getStatus();
            f.jdbc.queryForObject("SELECT COUNT(*) FROM bookings",Long.class);
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {pool.submit(()->f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=4")).get(10,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
            return f.app.getBean(PaymentIntentTransactions.class).open(route,actor);
        })).isInstanceOf(com.carrent.common.exception.ForbiddenException.class);
        assertThat(f.count("payment_intents")).isZero();
    }
    @Test void twoShortReceiptsAreReviewedWithoutAccumulatingAndRestartDoesNotLoseDebt() throws Exception {
        var intent=open();receive(intent,700000,1001);receive(intent,700000,1002);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201",Long.class)).isZero();
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
        System.setProperty("carrent.rental.balance.enabled","true");
        try {f.restart(false);}finally{System.clearProperty("carrent.rental.balance.enabled");}
        receive(intent,1400000,1003);assertThat(bookingStatus()).isEqualTo("COMPLETED");
    }
    @Test void disputedAfterRemainsImmutableWhenBalanceCompletes() throws Exception {
        f.jdbc.update("DELETE FROM inspection_events");f.jdbc.update("DELETE FROM inspection_images");f.jdbc.update("DELETE FROM booking_inspections");
        f.jdbc.update("INSERT INTO booking_inspections(booking_id,phase,status,author_id,condition_note,visible_damage,odometer_km,energy_percent,accessories_note,submitted_at,created_at,updated_at) VALUES(1,'AFTER','DISPUTED',4,'immutable condition','immutable scratch',12500,60,'keys','2026-10-30 02:59:00','2026-10-30','2026-10-30')");
        var before=f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE booking_id=1");
        receive(open(),1400000,1101);
        assertThat(bookingStatus()).isEqualTo("COMPLETED");assertThat(f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE booking_id=1")).isEqualTo(before);
    }
    @Test void refundFailureRollsBackCompletionAndAllocation() throws Exception {
        var intent=open();f.jdbc.update("DELETE FROM payment_channels WHERE id=103");
        receive(intent,1500000,1201);
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");assertThat(f.count("payment_receipts")).isZero();assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=201",Long.class)).isZero();
    }
    @Test void staleReceiptSnapshotSeesOtherCommittedSettlementAndRefundsWithoutDoubleAllocation() throws Exception {
        var intent=open();long inbox=enqueue(intent,1400000,1301);
        var claim=f.app.getBean(InboxWorkService.class).claim(inbox);
        f.tx.execute(s->{
            f.em.find(com.carrent.booking.Booking.class,1L).getStatus();f.em.find(PaymentObligation.class,201L).getStatus();
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {pool.submit(()->{try{receive(intent,1400000,1302);}catch(Exception e){throw new RuntimeException(e);}}).get(20,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
            f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());return null;
        });
        assertThat(bookingStatus()).isEqualTo("COMPLETED");
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts",Long.class)).isEqualTo(1400000);
        assertThat(f.jdbc.queryForObject("SELECT SUM(amount) FROM refunds",Long.class)).isEqualTo(1400000);
    }
    @Test void concurrentReminderAndPaymentHaveAtomicOutcomes() throws Exception {
        var intent=open();long inbox=enqueue(intent,1400000,1401);var claim=f.app.getBean(InboxWorkService.class).claim(inbox);
        f.clock.at.set(f.clock.instant().plusSeconds(3600));
        // Renew this independently committed claim after moving the fixture clock.
        f.jdbc.update("UPDATE webhook_inbox SET lease_until='2026-10-30 05:00:00' WHERE id=?",inbox);
        var gate=new java.util.concurrent.CyclicBarrier(2);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->f.tx.execute(s->{await(gate);f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());return null;}));
            var b=pool.submit(()->f.tx.execute(s->{await(gate);f.app.getBean(BalanceDeadlineService.class).remind(1L);return null;}));
            a.get(20,java.util.concurrent.TimeUnit.SECONDS);b.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertThat(bookingStatus()).isEqualTo("COMPLETED");
        long notices=f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_BALANCE_OVERDUE'",Long.class);
        assertThat(notices).isIn(0L,3L);
        f.app.getBean(BalanceDeadlineService.class).remind(1L);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_BALANCE_OVERDUE'",Long.class)).isEqualTo(notices);
    }
    static void await(java.util.concurrent.CyclicBarrier gate){try{gate.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}}
    @Test void staleSnapshotAfterCommittedCancellationCreatesNewActiveAttempt() {
        var first=open();var actor=f.actor(4);var route=f.app.getBean(PaymentRoutes.class).obligation(201L);
        var next=f.tx.execute(s->{
            f.em.find(PaymentIntent.class,first.id()).getStatus();
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {pool.submit(()->f.payments.cancelAttempt(first.id(),actor)).get(20,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
            return f.app.getBean(PaymentIntentTransactions.class).open(route,actor);
        });
        assertThat(next.id()).isNotEqualTo(first.id());assertThat(next.status()).isEqualTo("ACTIVE");
    }
}
