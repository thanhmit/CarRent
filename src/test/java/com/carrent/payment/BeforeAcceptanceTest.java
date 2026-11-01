package com.carrent.payment;

import com.carrent.booking.BookingService;
import com.carrent.inspection.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import com.carrent.common.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BeforeAcceptanceTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root",root.toString());
        System.setProperty("carrent.rental.advance.enabled","true");
        try { f = new PaymentFlowFixture(false,false); }
        finally { System.clearProperty("carrent.inspection.storage-root"); System.clearProperty("carrent.rental.advance.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if(f!=null) f.close(); }
    @BeforeEach void reset() {
        for(var table:List.of("inspection_events","inspection_images","booking_inspections","notifications")) f.jdbc.update("DELETE FROM "+table);
        f.reset(); f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE bookings SET status='PENDING',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at=NULL,before_accepted_at=NULL,hold_expires_at=NULL,advance_amount=NULL,balance_amount=NULL,advance_paid_at=NULL,cancelled_at=NULL,cancellation_fee=NULL,vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL,start_date='2030-12-01',end_date='2030-12-02',price_per_day_snapshot=2000001,total_amount=2000001 WHERE id=1");
    }
    BookingService bookings() { return f.app.getBean(BookingService.class); }
    InspectionService inspections() { return f.app.getBean(InspectionService.class); }
    void ready() throws Exception {
        bookings().confirmBooking(1L,f.actor(2));
        var form=new InspectionForm(); form.setConditionNote("Xe sạch"); form.setVisibleDamage("Không phát hiện");
        form.setOdometerKm(12345); form.setEnergyPercent(80); form.setAccessoriesNote("Chìa khóa");
        inspections().saveDraft(1L,InspectionPhase.BEFORE,form,f.actor(2));
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",out);
        var photo=new MockMultipartFile("images","a.png","image/png",out.toByteArray());
        inspections().addImages(1L,InspectionPhase.BEFORE,List.of(photo,photo,photo,photo),f.actor(2));
        inspections().submitBefore(1L,f.actor(2));
    }
    @Test void agreementAndAdvanceCommitTogetherWithoutConfirmingPaymentOrHandover() throws Exception {
        ready();
        var payment=bookings().acceptBefore(1L,f.actor(4));
        assertThat(payment.status()).isEqualTo("ACTIVE");
        assertThat(payment.amount()).isEqualTo(600000);
        assertThat(payment.expiresAt()).isEqualTo(PaymentFlowFixture.NOW.plusSeconds(900));
        assertThat(payment.qrUrl()).contains("amount=600000","addInfo="+payment.code());
        var b=bookings().getBookingForView(1L,f.actor(4));
        assertThat(b.getBeforeAcceptedAt()).isEqualTo(PaymentFlowFixture.NOW);
        assertThat(b.getHoldExpiresAt()).isEqualTo(payment.expiresAt());
        assertThat(b.getAdvanceAmount()).isEqualTo(600000); assertThat(b.getBalanceAmount()).isEqualTo(1400001);
        assertThat(b.getStatus().name()).isEqualTo("PENDING");
        assertThat(b.getAdvancePaidAt()).isNull(); assertThat(b.getVehicleReceivedAt()).isNull();
        var report=inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4));
        assertThat(report.status()).isEqualTo(InspectionStatus.ACCEPTED);
        assertThat(report.events()).filteredOn(e->e.action().name().equals("BEFORE_ACCEPTED")).singleElement()
                .satisfies(e->{assertThat(e.actorId()).isEqualTo(4);assertThat(e.createdAt()).isEqualTo(PaymentFlowFixture.NOW);});
        assertThat(f.count("payment_obligations")).isEqualTo(1); assertThat(f.count("payment_intents")).isEqualTo(1);
    }
    @Test void cancelFromBookingEndsAdvanceAndReleasesCalendarAtomically() throws Exception {
        ready(); var payment=bookings().acceptBefore(1L,f.actor(4));
        bookings().cancelBooking(1L,f.actor(4));
        assertThat(f.payments.status(payment.id(),f.actor(4)).status()).isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_obligations",String.class)).isEqualTo("CANCELLED");
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getStatus().name()).isEqualTo("CANCELLED");
        var conflicts=f.tx.execute(s->f.app.getBean(com.carrent.booking.BookingRepository.class)
                .findConflictsForUpdate(1L,java.time.LocalDate.of(2030,12,1),java.time.LocalDate.of(2030,12,2)));
        assertThat(conflicts).noneMatch(b->b.getId().equals(1L));
    }
    @Test void customerCanAgreeFromPublishedEvidenceAndCheckoutShowsBothAmountsAndBookingCancellation() throws Exception {
        ready(); var customer=f.actor(4);
        f.mvc.perform(get("/bookings/1/inspections/BEFORE").with(user(customer)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/bookings/1/accept-before")));
        var result=f.mvc.perform(post("/bookings/1/accept-before").with(user(customer)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        String checkout=result.getResponse().getRedirectedUrl();
        assertThat(checkout).startsWith("/payment/intents/");
        f.mvc.perform(get(checkout).with(user(customer))).andExpect(status().isOk())
                .andExpect(content().string(containsString("600.000 VNĐ")))
                .andExpect(content().string(containsString("1.400.001 VNĐ")))
                .andExpect(content().string(containsString("Hủy đặt xe")))
                .andExpect(content().string(containsString("chưa xác nhận nhận xe")));
        f.mvc.perform(get("/bookings/1").with(user(customer))).andExpect(status().isOk())
                .andExpect(content().string(containsString(checkout)));
        f.mvc.perform(post(checkout+"/cancel").with(user(customer)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(checkout+"/result"));
        f.mvc.perform(get(checkout+"/result").with(user(customer))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã hủy đặt xe")));
    }
    @Test void ownerCannotRejectAnAcceptedReportAndLeaveAnActiveAdvanceOrphaned() throws Exception {
        ready(); var payment=bookings().acceptBefore(1L,f.actor(4));
        assertThatThrownBy(()->bookings().rejectBooking(1L,f.actor(2),"Không còn xe")).isInstanceOf(BusinessException.class);
        assertThat(f.payments.status(payment.id(),f.actor(4)).status()).isEqualTo("ACTIVE");
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getStatus().name()).isEqualTo("PENDING");
    }
    @Test void repeatedAgreementAndTwoTabsReuseOneIntentAndDeadline() throws Exception {
        ready(); var first=bookings().acceptBefore(1L,f.actor(4));
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(60));
        var repeat=bookings().acceptBefore(1L,f.actor(4));
        assertThat(repeat.id()).isEqualTo(first.id()); assertThat(repeat.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(f.count("payment_obligations")).isEqualTo(1); assertThat(f.count("payment_intents")).isEqualTo(1);
        assertThat(inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4)).events())
                .filteredOn(e->e.action().name().equals("BEFORE_ACCEPTED")).hasSize(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancelAttemptEndsBookingEvenAtOrAfterTheDeadline(boolean afterDeadline) throws Exception {
        ready(); var view=bookings().acceptBefore(1L,f.actor(4));
        if(afterDeadline) f.clock.at.set(view.expiresAt());
        f.payments.cancelAttempt(view.id(),f.actor(4));
        var cancelled=bookings().getBookingForView(1L,f.actor(4));
        assertThat(cancelled.getStatus().name()).isEqualTo("CANCELLED");
        assertThat(f.payments.status(view.id(),f.actor(4)).status()).isEqualTo("CANCELLED");
        var at=cancelled.getCancelledAt(); f.clock.at.set(f.clock.instant().plusSeconds(60));
        f.payments.cancelAttempt(view.id(),f.actor(4)); bookings().cancelBooking(1L,f.actor(4));
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getCancelledAt()).isEqualTo(at);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_CANCELLED'",Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","ACCEPTED","RESOLVED","MISSING","IMAGES","FIELDS","AUTHOR"})
    void invalidCurrentEvidenceRollsBackObligationIntentAndAgreement(String invalid) throws Exception {
        ready();
        switch(invalid) {
            case "DRAFT" -> f.jdbc.update("UPDATE booking_inspections SET status='DRAFT',submitted_at=NULL");
            case "MISSING" -> { f.jdbc.update("DELETE FROM inspection_events"); f.jdbc.update("DELETE FROM inspection_images"); f.jdbc.update("DELETE FROM booking_inspections"); }
            case "IMAGES" -> f.jdbc.update("DELETE FROM inspection_images ORDER BY id LIMIT 1");
            case "FIELDS" -> f.jdbc.update("UPDATE booking_inspections SET condition_note=' '");
            case "AUTHOR" -> f.jdbc.update("UPDATE booking_inspections SET author_id=3");
            default -> f.jdbc.update("UPDATE booking_inspections SET status=?",invalid);
        }
        assertThatThrownBy(()->bookings().acceptBefore(1L,f.actor(4))).isInstanceOf(BusinessException.class);
        assertUnagreed();
    }
    void assertUnagreed() {
        var b=bookings().getBookingForView(1L,f.actor(4));
        assertThat(b.getBeforeAcceptedAt()).isNull(); assertThat(b.getHoldExpiresAt()).isNull();
        assertThat(b.getAdvanceAmount()).isNull(); assertThat(b.getBalanceAmount()).isNull();
        assertThat(f.count("payment_obligations")).isZero(); assertThat(f.count("payment_intents")).isZero();
    }
    @Test void paymentChannelFailureDoesNotLeaveAcceptedReportOrPaymentObjects() throws Exception {
        ready(); f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=101");
        assertThatThrownBy(()->bookings().acceptBefore(1L,f.actor(4))).isInstanceOf(BusinessException.class);
        assertUnagreed(); assertThat(inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4)).status()).isEqualTo(InspectionStatus.SUBMITTED);
    }
    @Test void agreementAfterDisputePreservesOriginalEvidenceAndAllFeedbackAndClosesFurtherNotes() throws Exception {
        ready(); inspections().disputeBefore(1L,"Vết xước",f.actor(4));
        inspections().appendBeforeAddendum(1L,"Đã ghi nhận",f.actor(2));
        var before=inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4));
        bookings().acceptBefore(1L,f.actor(4));
        var after=inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4));
        assertThat(after.conditionNote()).isEqualTo(before.conditionNote()); assertThat(after.images()).isEqualTo(before.images());
        assertThat(after.events().subList(0,3)).isEqualTo(before.events());
        assertThat(after.canDispute()).isFalse(); assertThat(after.canAddendum()).isFalse();
        assertThatThrownBy(()->inspections().disputeBefore(1L,"Lặp",f.actor(4))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->inspections().appendBeforeAddendum(1L,"Lặp",f.actor(2))).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest @ValueSource(longs={1,2,3,5})
    void onlyTheBookingCustomerCanAgree(long other) throws Exception {
        ready(); assertThatThrownBy(()->bookings().acceptBefore(1L,f.actor(other))).isInstanceOf(RuntimeException.class);
        assertUnagreed();
    }
    @ParameterizedTest @ValueSource(strings={"BANNED","INACTIVE"})
    void staleLoginCannotAgreeWhenCustomerIsNoLongerActive(String status) throws Exception {
        ready(); var actor=f.actor(4); f.jdbc.update("UPDATE users SET status=? WHERE id=4",status);
        assertThatThrownBy(()->bookings().acceptBefore(1L,actor)).isInstanceOf(com.carrent.common.exception.ForbiddenException.class);
        assertThat(f.count("payment_intents")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"LEGACY","NO_INSPECTION","NO_OWNER","CANCELLED","REJECTED","EXPIRED","CONFIRMED","COMPLETED","HANDOVER"})
    void invalidBookingStateCannotStartAdvance(String state) throws Exception {
        ready();
        switch(state) {
            case "LEGACY" -> f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY' WHERE id=1");
            case "NO_INSPECTION" -> f.jdbc.update("UPDATE bookings SET inspection_required=0 WHERE id=1");
            case "NO_OWNER" -> f.jdbc.update("UPDATE bookings SET owner_accepted_at=NULL WHERE id=1");
            case "HANDOVER" -> f.jdbc.update("UPDATE bookings SET vehicle_received_at='2026-10-30 03:00:00',contract_confirmed_at='2026-10-30 03:00:00',contract_confirmed_by=2 WHERE id=1");
            default -> f.jdbc.update("UPDATE bookings SET status=? WHERE id=1",state);
        }
        assertThatThrownBy(()->bookings().acceptBefore(1L,f.actor(4))).isInstanceOf(BusinessException.class);
        assertThat(f.count("payment_obligations")).isZero(); assertThat(f.count("payment_intents")).isZero();
    }
    @Test void eventInsertFailureRollsBackAgreementAndAllFinancialObjects() throws Exception {
        ready();
        f.jdbc.execute("CREATE TRIGGER fail_before_accept BEFORE INSERT ON inspection_events FOR EACH ROW BEGIN IF NEW.action='BEFORE_ACCEPTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected accept audit failure'; END IF; END");
        try {
            assertThatThrownBy(()->bookings().acceptBefore(1L,f.actor(4))).isInstanceOf(RuntimeException.class);
            assertUnagreed(); assertThat(inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4)).status()).isEqualTo(InspectionStatus.SUBMITTED);
        } finally { f.jdbc.execute("DROP TRIGGER fail_before_accept"); }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void notificationFailureRollsBackBookingAndPaymentCancellation(boolean fromCheckout) throws Exception {
        ready(); var view=bookings().acceptBefore(1L,f.actor(4));
        f.jdbc.execute("CREATE TRIGGER fail_cancel_notify BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='BOOKING_CANCELLED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected cancellation notify failure'; END IF; END");
        try {
            assertThatThrownBy(()->{if(fromCheckout) f.payments.cancelAttempt(view.id(),f.actor(4)); else bookings().cancelBooking(1L,f.actor(4));}).isInstanceOf(RuntimeException.class);
            assertThat(bookings().getBookingForView(1L,f.actor(4)).getStatus().name()).isEqualTo("PENDING");
            assertThat(f.payments.status(view.id(),f.actor(4)).status()).isEqualTo("ACTIVE");
            assertThat(f.jdbc.queryForObject("SELECT status FROM payment_obligations",String.class)).isEqualTo("OPEN");
        } finally { f.jdbc.execute("DROP TRIGGER fail_cancel_notify"); }
    }
    @Test void twoDatabaseConnectionsAgreeOnceEvenWithPreviouslyLoadedBookingAndReport() throws Exception {
        ready(); var customer=f.actor(4); var barrier=new java.util.concurrent.CyclicBarrier(2);
        var connections=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        java.util.concurrent.Callable<Long> accept=()->f.tx.execute(s->{
            connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            f.em.find(com.carrent.booking.Booking.class,1L); inspections().getView(1L,InspectionPhase.BEFORE,customer);
            try{barrier.await(15,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}
            return bookings().acceptBefore(1L,customer).id();
        });
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(accept); var b=pool.submit(accept);
            assertThat(a.get(25,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(25,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates();
        assertThat(f.count("payment_obligations")).isEqualTo(1); assertThat(f.count("payment_intents")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='BEFORE_ACCEPTED'",Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancelVersusAgreementUsesCurrentStateInBothCommitOrders(boolean acceptFirst) throws Exception {
        ready(); var customer=f.actor(4);
        var held=new java.util.concurrent.CountDownLatch(1);var stale=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var connections=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->f.tx.executeWithoutResult(s->{
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                if(acceptFirst) bookings().acceptBefore(1L,customer); else bookings().cancelBooking(1L,customer);
                held.countDown(); await(release);
            }));
            try {
                assertThat(held.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var b=pool.submit(()->{
                    try { f.tx.executeWithoutResult(s->{
                        connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                        f.em.find(com.carrent.booking.Booking.class,1L); inspections().getView(1L,InspectionPhase.BEFORE,customer);
                        stale.countDown();
                        if(acceptFirst) bookings().cancelBooking(1L,customer); else bookings().acceptBefore(1L,customer);
                    });return true;}catch(BusinessException e){return false;}
                });
                assertThat(stale.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();release.countDown();
                a.get(25,java.util.concurrent.TimeUnit.SECONDS); assertThat(b.get(25,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(acceptFirst);
            } finally {release.countDown();}
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates();
        assertThat(bookings().getBookingForView(1L,customer).getStatus().name()).isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents WHERE status='ACTIVE'",Long.class)).isZero();
        assertThat(f.count("payment_intents")).isEqualTo(acceptFirst?1:0);
    }
    static void await(java.util.concurrent.CountDownLatch latch) {
        try{if(!latch.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Latch timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    @Test void agreedBeforeCanReceiveAdvanceThroughProductionHandlerWithoutHandover() throws Exception {
        ready();var view=bookings().acceptBefore(1L,f.actor(4));
        var inbox=f.receive(990001L,view.code(),"600000"); f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations",Long.class)).isEqualTo(600000L);
        assertThat(f.payments.status(view.id(),f.actor(4)).status()).isEqualTo("RECEIVED");
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getAdvancePaidAt()).isEqualTo(PaymentFlowFixture.NOW);
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getVehicleReceivedAt()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox WHERE id=?",String.class,inbox)).isEqualTo("PROCESSED");
    }
    @ParameterizedTest @ValueSource(strings={"payer_user_id","collector_user_id"})
    void corruptFinancialPartiesCannotCancelSomeoneElsesBooking(String party) throws Exception {
        ready();var view=bookings().acceptBefore(1L,f.actor(4));
        f.jdbc.update("UPDATE payment_obligations SET "+party+"=?",party.equals("payer_user_id")?5:3);
        var actor=f.actor(party.equals("payer_user_id")?5:4);
        assertThatThrownBy(()->f.payments.cancelAttempt(view.id(),actor)).isInstanceOf(BusinessException.class);
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getStatus().name()).isEqualTo("PENDING");
    }
    @Test void bookingCancellationRejectsAnObligationWithMismatchedParties() throws Exception {
        ready();bookings().acceptBefore(1L,f.actor(4));f.jdbc.update("UPDATE payment_obligations SET payer_user_id=5");
        assertThatThrownBy(()->bookings().cancelBooking(1L,f.actor(4))).isInstanceOf(BusinessException.class);
    }
    @Test void expiredAgreementDoesNotCreateAnotherIntentOrResetTheHold() throws Exception {
        ready();var first=bookings().acceptBefore(1L,f.actor(4));f.clock.at.set(first.expiresAt());
        assertThatThrownBy(()->bookings().acceptBefore(1L,f.actor(4))).isInstanceOf(BusinessException.class);
        assertThat(f.count("payment_intents")).isEqualTo(1);
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getHoldExpiresAt()).isEqualTo(first.expiresAt());
        f.mvc.perform(get("/payment/intents/"+first.id()).with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("id=\"payment-qr\""))))
                .andExpect(content().string(containsString("Hủy đặt xe")));
    }
}
