package com.carrent.payment;

import com.carrent.booking.BookingService;
import com.carrent.booking.RentalPaymentHandler;
import com.carrent.inspection.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

class RentalPaidCancellationTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root", root.toString());
        System.setProperty("carrent.rental.advance.enabled", "true");
        try { f = new PaymentFlowFixture(false, true, PayoutWorkerTest.Configuration.class); }
        finally { System.clearProperty("carrent.inspection.storage-root"); System.clearProperty("carrent.rental.advance.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        for (var table : List.of("inspection_events", "inspection_images", "booking_inspections", "notifications")) f.jdbc.update("DELETE FROM " + table);
        f.reset(); f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE users SET role=CASE id WHEN 2 THEN 'OWNER' ELSE 'CUSTOMER' END WHERE id IN (2,4)");
        f.jdbc.update("DELETE FROM bookings WHERE customer_note='P66 race B'");
        f.jdbc.update("UPDATE bookings SET status='PENDING',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at=NULL,before_accepted_at=NULL,hold_expires_at=NULL,advance_amount=NULL,balance_amount=NULL,advance_paid_at=NULL,cancelled_at=NULL,cancellation_fee=NULL,cancellation_reason=NULL,vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL,start_date='2030-12-01',end_date='2030-12-02',price_per_day_snapshot=2000001,total_amount=2000001 WHERE id=1");
    }
    BookingService bookings() { return f.app.getBean(BookingService.class); }
    ReceiptProcessor processor() { return f.app.getBean(ReceiptProcessor.class); }
    PaymentIntentView agree() throws Exception {
        bookings().confirmBooking(1L, f.actor(2));
        var inspections = f.app.getBean(InspectionService.class);
        var form = new InspectionForm(); form.setConditionNote("Xe sạch"); form.setVisibleDamage("Không phát hiện");
        form.setOdometerKm(12345); form.setEnergyPercent(80); form.setAccessoriesNote("Chìa khóa");
        inspections.saveDraft(1L, InspectionPhase.BEFORE, form, f.actor(2));
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        var photo = new MockMultipartFile("images", "a.png", "image/png", out.toByteArray());
        inspections.addImages(1L, InspectionPhase.BEFORE, List.of(photo, photo, photo, photo), f.actor(2));
        inspections.submitBefore(1L, f.actor(2));
        return bookings().acceptBefore(1L, f.actor(4));
    }
    String state(String table) { return f.jdbc.queryForObject("SELECT status FROM " + table + " ORDER BY id LIMIT 1", String.class); }
    long receive(long transaction, PaymentIntentView view, Instant creditedAt) throws Exception {
        String bankTime = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(creditedAt.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        byte[] body = new String(SePayInboxTest.body(transaction, view.code(), "600000"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("2026-10-30 10:00:00", bankTime).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(101L, f.signed(body), body);
        return f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?", Long.class, Long.toString(transaction));
    }


    @Test void paidCustomerCancellationCreatesPendingRefundWithoutRewritingReceivedMoney() throws Exception {
        var view=agree(); processor().process(f.receive(991001,view.code(),"600000"));
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3601));
        bookings().cancelBooking(1L,f.actor(4));
        assertThat(state("bookings")).isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT cancellation_fee FROM bookings WHERE id=1",Long.class)).isEqualTo(60000);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(540000);
        assertThat(state("refunds")).isEqualTo("WAITING_RECIPIENT");
        assertThat(state("payment_receipts")).isEqualTo("APPLIED");
        assertThat(state("payment_intents")).isEqualTo("RECEIVED");
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations",Long.class)).isEqualTo(600000);
    }

    void paid() throws Exception {
        var view=agree(); processor().process(f.receive(991001,view.code(),"600000"));
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"3599,2030-12-01,0", "3600,2026-10-29,0", "3601,2026-11-03,60000", "3601,2026-11-02,300000", "3601,2026-10-30,300000", "3601,2026-10-29,300000"})
    void customerPostComputesInclusiveHourAndCalendarDayPolicy(long seconds,String start,long fee) throws Exception {
        paid(); f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(seconds));
        var date=java.time.LocalDate.parse(start);
        f.jdbc.update("UPDATE bookings SET start_date=?,end_date=? WHERE id=1",date,date.plusDays(1));
        bookings().cancelBooking(1L,f.actor(4),"  Change schedule  ");
        assertThat(f.jdbc.queryForObject("SELECT cancellation_fee FROM bookings WHERE id=1",Long.class)).isEqualTo(fee);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(600000-fee);
        assertThat(f.jdbc.queryForObject("SELECT cancellation_reason FROM bookings WHERE id=1",String.class)).isEqualTo("Change schedule");
        assertThat(f.jdbc.queryForObject("SELECT owner_note FROM bookings WHERE id=1",String.class)).isNull();
    }
    @Test void ownerAlwaysRefundsFullyAndTheFirstSnapshotSurvivesEitherPartyRetry() throws Exception {
        paid(); f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(7200));
        bookings().cancelByOwner(1L,"Maintenance",f.actor(2));
        var snapshot=f.jdbc.queryForMap("SELECT cancelled_at,cancellation_fee,cancellation_reason FROM bookings WHERE id=1");
        long notices=f.count("notifications");
        f.clock.at.set(f.clock.instant().plusSeconds(86400));
        bookings().cancelBooking(1L,f.actor(4),"Another reason");
        bookings().cancelByOwner(1L,"Retry",f.actor(2));
        assertThat(f.jdbc.queryForMap("SELECT cancelled_at,cancellation_fee,cancellation_reason FROM bookings WHERE id=1")).isEqualTo(snapshot);
        assertThat(f.count("notifications")).isEqualTo(notices); assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(600000);
        assertThat(f.jdbc.queryForObject("SELECT reason FROM refunds",String.class)).isEqualTo("OWNER_CANCEL");
    }
    @Test void unpaidOwnerCancellationCreatesNoRefundUntilLateMoneyArrives() throws Exception {
        var view=agree(); bookings().cancelByOwner(1L,"Cannot deliver",f.actor(2));
        assertThat(f.jdbc.queryForObject("SELECT title FROM notifications WHERE type='BOOKING_CANCELLED' AND user_id=2",String.class))
                .isEqualTo("Chủ xe đã hủy đơn thuê");
        assertThat(f.count("refunds")).isZero();
        processor().process(f.receive(991001,view.code(),"600000"));
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(600000);
        assertThat(f.jdbc.queryForObject("SELECT reason FROM refunds",String.class)).isEqualTo("LATE_ADVANCE");
    }
    @ParameterizedTest @ValueSource(strings={"", "long", "channel", "notice", "refund"})
    void failedCancellationRollsBackEverySnapshotAndRefund(String fault) throws Exception {
        paid(); String reason=fault.equals("") ? " " : fault.equals("long") ? "x".repeat(501) : "Change schedule";
        if(fault.equals("channel")) f.jdbc.update("DELETE FROM payment_channels WHERE id=103");
        if(fault.equals("notice") || fault.equals("refund")) f.jdbc.execute("CREATE TRIGGER p611_failure BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='"+(fault.equals("notice")?"BOOKING_CANCELLED":"REFUND_WAITING_RECIPIENT")+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='P611 rollback'; END IF; END");
        long notices=f.count("notifications");
        try { assertThatThrownBy(()->bookings().cancelBooking(1L,f.actor(4),reason)).isInstanceOf(RuntimeException.class); }
        finally { if(fault.equals("notice")||fault.equals("refund")) f.jdbc.execute("DROP TRIGGER p611_failure"); }
        assertThat(state("bookings")).isEqualTo("CONFIRMED"); assertThat(f.count("refunds")).isZero();
        assertThat(f.count("notifications")).isEqualTo(notices);
        assertThat(f.jdbc.queryForObject("SELECT cancellation_reason FROM bookings WHERE id=1",String.class)).isNull();
        assertThat(f.jdbc.queryForObject("SELECT cancelled_at FROM bookings WHERE id=1",java.sql.Timestamp.class)).isNull();
        assertThat(f.jdbc.queryForObject("SELECT cancellation_fee FROM bookings WHERE id=1",Long.class)).isNull();
    }
    @Test void waitingFundsKeepsCancellationAndReceivedMoneyUntilProviderSuccess() throws Exception {
        paid(); bookings().cancelBooking(1L,f.actor(4),"Change schedule");
        long id=f.jdbc.queryForObject("SELECT id FROM refunds",Long.class);
        var form=new RefundRecipientForm(); form.setBankBin("970422"); form.setAccountNumber("000123456");
        f.app.getBean(RefundRecipientService.class).submit(id,form,f.actor(4));
        var provider=f.app.getBean(PayoutWorkerTest.FakePayouts.class); provider.reset();
        provider.onCreate=(channel,command)->new com.carrent.payment.provider.PayoutResult("p611-offline",command.referenceId(),com.carrent.payment.provider.TransferOutcome.NOT_SENT,command.bankBin(),command.accountNumber(),command.amount(),com.carrent.payment.provider.PayoutResult.NotSentReason.INSUFFICIENT_FUNDS);
        f.app.getBean(PayoutWorker.class).dispatch(id);
        assertThat(state("refunds")).isEqualTo("WAITING_FUNDS");assertThat(state("bookings")).isEqualTo("CANCELLED");
        assertThat(state("payment_receipts")).isEqualTo("APPLIED");assertThat(state("payment_obligations")).isEqualTo("SETTLED");
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations",Long.class)).isEqualTo(600000);
    }
    @Test void postedMoneyAndActorCannotOverrideTheQuoteRecomputedAtSubmission() throws Exception {
        paid(); var customer=f.actor(4);
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3599));
        long notices=f.count("notifications");
        assertThat(bookings().cancellationQuote(1L,customer).fee()).isZero();
        assertThat(f.count("refunds")).isZero(); assertThat(f.count("notifications")).isEqualTo(notices);
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3601));
        f.mvc.perform(post("/bookings/1/cancel").with(user(customer)).with(csrf())
                .param("reason","Change schedule").param("fee","0").param("refund","600000")
                .param("ownerCancels","true").param("cancelledAt","2020-01-01T00:00:00Z"))
                .andExpect(status().is3xxRedirection());
        assertThat(f.jdbc.queryForObject("SELECT cancellation_fee FROM bookings WHERE id=1",Long.class)).isEqualTo(60000);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(540000);
        assertThat(f.jdbc.queryForObject("SELECT reason FROM refunds",String.class)).isEqualTo("CUSTOMER_CANCEL");
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void neitherPartyCanCancelPhysicalCustodyOrReturnDebt(boolean returned) throws Exception {
        paid();
        f.jdbc.update("UPDATE bookings SET vehicle_received_at='2026-10-30 03:00:00',contract_confirmed_at='2026-10-30 03:00:00',contract_confirmed_by=2 WHERE id=1");
        if(returned) f.jdbc.update("UPDATE bookings SET vehicle_returned_at='2026-10-30 03:01:00' WHERE id=1");
        assertThatThrownBy(()->bookings().cancelBooking(1L,f.actor(4),"Customer")).isInstanceOf(com.carrent.common.exception.BusinessException.class);
        assertThatThrownBy(()->bookings().cancelByOwner(1L,"Owner",f.actor(2))).isInstanceOf(com.carrent.common.exception.BusinessException.class);
        assertThat(bookings().cancellationQuote(1L,f.actor(4))).isNull();
        assertThat(f.count("refunds")).isZero();assertThat(state("bookings")).isEqualTo("CONFIRMED");
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentCustomerOwnerCancellationKeepsFirstWinner(boolean ownerFirst) throws Exception {
        paid(); f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3601));
        Runnable customer=()->bookings().cancelBooking(1L,f.actor(4),"Customer");
        Runnable owner=()->bookings().cancelByOwner(1L,"Owner",f.actor(2));
        race(ownerFirst?owner:customer,ownerFirst?customer:owner);
        assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT cancellation_reason FROM bookings WHERE id=1",String.class)).isEqualTo(ownerFirst?"Owner":"Customer");
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(ownerFirst?600000:540000);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void handoverAndCancellationSerializeAgainstStaleSnapshots(boolean handoverFirst) throws Exception {
        paid();f.jdbc.update("UPDATE bookings SET start_date='2026-10-30',end_date='2026-10-31' WHERE id=1");
        var customer=f.actor(4);var owner=f.actor(2);
        Runnable cancel=()->bookings().cancelBooking(1L,customer,"Customer");
        Runnable handover=()->bookings().confirmHandover(1L,true,owner);
        race(handoverFirst?handover:cancel,()->{try { (handoverFirst?cancel:handover).run();throw new AssertionError("loser must fail"); }catch(com.carrent.common.exception.BusinessException expected){}},true);
        assertThat(state("bookings")).isEqualTo(handoverFirst?"CONFIRMED":"CANCELLED");
        assertThat(f.count("refunds")).isEqualTo(handoverFirst?0:1);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"2,status", "4,status", "2,role", "4,role"})
    void actorChangedWhileWaitingForAggregateCannotCancel(long user,String change) throws Exception {
        paid();var actor=f.actor(user);
        race(()->f.jdbc.update("UPDATE users SET "+(change.equals("status")?"status='INACTIVE'":"role='ADMIN'")+" WHERE id=?",user),()->{
            assertThatThrownBy(()->{if(user==2)bookings().cancelByOwner(1L,"Owner",actor);else bookings().cancelBooking(1L,actor,"Customer");})
                .isInstanceOf(com.carrent.common.exception.ForbiddenException.class);
        },true);
        assertThat(state("bookings")).isEqualTo("CONFIRMED"); assertThat(f.count("refunds")).isZero();
    }
    void race(Runnable first,Runnable second) throws Exception {race(first,second,false);}
    void race(Runnable first,Runnable second,boolean rollback) throws Exception {
        var held=new java.util.concurrent.CountDownLatch(1);var snapshot=new java.util.concurrent.CountDownLatch(1);
        var connections=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->f.tx.executeWithoutResult(t->{connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));f.app.getBean(RentalPaymentHandler.class).lockSubject(1L);held.countDown();await(snapshot);first.run();}));
            var b=pool.submit(()->{await(held);try{f.tx.executeWithoutResult(t->{
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));f.em.find(com.carrent.booking.Booking.class,1L);
                f.em.find(com.carrent.user.User.class,2L);f.em.find(com.carrent.user.User.class,4L);
                snapshot.countDown();second.run();
            });}catch(org.springframework.transaction.UnexpectedRollbackException expected){if(!rollback)throw expected;}});
            try{a.get(30,java.util.concurrent.TimeUnit.SECONDS);b.get(30,java.util.concurrent.TimeUnit.SECONDS);}finally{held.countDown();snapshot.countDown();}
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates();
    }
    static void await(java.util.concurrent.CountDownLatch latch) {
        try{if(!latch.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Race timeout");}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}
    }
}
