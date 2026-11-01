package com.carrent.payment;

import com.carrent.booking.BookingService;
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
import com.carrent.common.exception.BusinessException;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

class RentalHandoverTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
    static final Instant HANDOVER = Instant.parse("2030-12-01T03:00:00Z");
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root", root.toString());
        System.setProperty("carrent.rental.advance.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.inspection.storage-root"); System.clearProperty("carrent.rental.advance.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        for (var table : List.of("inspection_events", "inspection_images", "booking_inspections", "notifications")) f.jdbc.update("DELETE FROM " + table);
        f.reset(); f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE bookings SET vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL");
        f.jdbc.update("UPDATE bookings SET status='PENDING',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at=NULL,before_accepted_at=NULL,hold_expires_at=NULL,advance_amount=NULL,balance_amount=NULL,advance_paid_at=NULL,cancelled_at=NULL,cancellation_fee=NULL,start_date='2030-12-01',end_date='2030-12-03',price_per_day_snapshot=1000000,total_amount=2000000 WHERE id=1");
    }
    BookingService bookings() { return f.app.getBean(BookingService.class); }
    InspectionService inspections() { return f.app.getBean(InspectionService.class); }
    PaymentIntentView ready(boolean paid) throws Exception {
        return ready(1L, paid);
    }
    PaymentIntentView ready(long id, boolean paid) throws Exception {
        f.clock.at.set(PaymentFlowFixture.NOW);
        if(id!=1) f.jdbc.update("UPDATE bookings SET car_id=1,customer_id=4,status='PENDING',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at=NULL,before_accepted_at=NULL,hold_expires_at=NULL,advance_amount=NULL,balance_amount=NULL,advance_paid_at=NULL,cancelled_at=NULL,cancellation_fee=NULL,start_date='2030-12-01',end_date='2030-12-03',price_per_day_snapshot=1000000,total_amount=2000000 WHERE id=?",id);
        bookings().confirmBooking(id, f.actor(2));
        var form = new InspectionForm(); form.setConditionNote("Xe sạch"); form.setVisibleDamage("Không phát hiện");
        form.setOdometerKm(12345); form.setEnergyPercent(80); form.setAccessoriesNote("Chìa khóa");
        inspections().saveDraft(id, InspectionPhase.BEFORE, form, f.actor(2));
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        var photo = new MockMultipartFile("images", "a.png", "image/png", out.toByteArray());
        inspections().addImages(id, InspectionPhase.BEFORE, List.of(photo, photo, photo, photo), f.actor(2));
        inspections().submitBefore(id, f.actor(2));
        var view = bookings().acceptBefore(id, f.actor(4));
        if (paid) f.app.getBean(ReceiptProcessor.class).process(f.receive(991000+id, view.code(), "600000"));
        f.clock.at.set(HANDOVER);
        return view;
    }
    @Test void ownerHandoverRecordsContractCustodyAndEvidenceAtomically() throws Exception {
        ready(true);
        f.mvc.perform(post("/owner/bookings/1/handover").param("contractSigned", "true").with(user(f.actor(2))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/owner/bookings/1"))
                .andExpect(flash().attributeExists("successMessage"));
        var booking = bookings().getBookingForView(1L, f.actor(4));
        assertThat(booking.getVehicleReceivedAt()).isEqualTo(HANDOVER);
        assertThat(booking.getContractConfirmedAt()).isEqualTo(HANDOVER);
        assertThat(booking.getContractConfirmedBy().getId()).isEqualTo(2L);
        assertThat(booking.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(booking.getVehicleReturnedAt()).isNull();
        assertThat(inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4)).events())
                .filteredOn(e -> e.action() == InspectionEventAction.PICKUP_CONFIRMED).singleElement()
                .satisfies(e -> { assertThat(e.actorId()).isEqualTo(2L); assertThat(e.createdAt()).isEqualTo(HANDOVER); });
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='INSPECTION_PICKUP_CONFIRMED' AND user_id=4", Long.class)).isEqualTo(1);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.count("payment_obligations")).isEqualTo(1);
        assertThat(f.count("refunds")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unpaid", "pending", "cancelled", "expired", "completed", "owner-not-accepted", "before-not-agreed", "report-disputed", "report-wrong-author", "missing-paid-at", "short-payment", "open-obligation", "wrong-payer", "wrong-collector", "wrong-amount", "wrong-snapshot", "before-start", "at-end"})
    void invalidPrerequisiteCannotRecordAnyHandover(String invalid) throws Exception {
        ready(!invalid.equals("unpaid"));
        switch (invalid) {
            case "pending", "cancelled", "expired", "completed" -> f.jdbc.update("UPDATE bookings SET status=? WHERE id=1", invalid.toUpperCase());
            case "owner-not-accepted" -> f.jdbc.update("UPDATE bookings SET owner_accepted_at=NULL WHERE id=1");
            case "before-not-agreed" -> f.jdbc.update("UPDATE bookings SET before_accepted_at=NULL WHERE id=1");
            case "report-disputed" -> f.jdbc.update("UPDATE booking_inspections SET status='DISPUTED'");
            case "report-wrong-author" -> f.jdbc.update("UPDATE booking_inspections SET author_id=4");
            case "missing-paid-at" -> f.jdbc.update("UPDATE bookings SET advance_paid_at=NULL WHERE id=1");
            case "short-payment" -> f.jdbc.update("UPDATE payment_obligations SET amount_settled=599999");
            case "open-obligation" -> f.jdbc.update("UPDATE payment_obligations SET status='OPEN'");
            case "wrong-payer" -> f.jdbc.update("UPDATE payment_obligations SET payer_user_id=1");
            case "wrong-collector" -> f.jdbc.update("UPDATE payment_obligations SET collector_user_id=1");
            case "wrong-amount" -> f.jdbc.update("UPDATE payment_obligations SET amount_due=600001");
            case "wrong-snapshot" -> f.jdbc.update("UPDATE bookings SET advance_amount=600001 WHERE id=1");
            case "before-start" -> f.clock.at.set(Instant.parse("2030-11-30T16:59:59.999999Z"));
            case "at-end" -> f.clock.at.set(Instant.parse("2030-12-02T17:00:00Z"));
        }
        var events = inspections().getView(1L, InspectionPhase.BEFORE, f.actor(2)).events();
        long notifications = f.count("notifications");
        assertThatThrownBy(() -> bookings().confirmHandover(1L, true, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(bookings().getBookingForView(1L, f.actor(2)).getVehicleReceivedAt()).isNull();
        assertThat(inspections().getView(1L, InspectionPhase.BEFORE, f.actor(2)).events()).isEqualTo(events);
        assertThat(f.count("notifications")).isEqualTo(notifications);
    }

    @Test void oldUnreturnedBookingBlocksCustodyEvenWithExpiredDatesAndTerminalStatus() throws Exception {
        ready(true);
        f.jdbc.update("UPDATE bookings SET car_id=1,inspection_required=1,status='COMPLETED',vehicle_received_at='2026-01-01 00:00:00',contract_confirmed_at='2026-01-01 00:00:00',contract_confirmed_by=2 WHERE id=2");
        assertThatThrownBy(() -> bookings().confirmHandover(1L, true, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(bookings().getBookingForView(1L, f.actor(2)).getVehicleReceivedAt()).isNull();
        f.jdbc.update("UPDATE bookings SET vehicle_returned_at='2026-01-02 00:00:00' WHERE id=2");
        bookings().confirmHandover(1L, true, f.actor(2));
        assertThat(bookings().getBookingForView(1L, f.actor(2)).getVehicleReceivedAt()).isEqualTo(HANDOVER);
    }

    @Test void manualCompleteCannotBypassReturnAndBalanceForNewWorkflow() throws Exception {
        ready(true);
        f.jdbc.update("UPDATE bookings SET start_date='2020-01-01',end_date='2020-01-03' WHERE id=1");
        assertThatThrownBy(() -> bookings().completeBooking(1L, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(bookings().getBookingForView(1L, f.actor(2)).getStatus().name()).isEqualTo("CONFIRMED");
    }

    @Test void ownerFormAndBothRentalViewsReflectCustodyWithoutLegacyPaymentClaims() throws Exception {
        ready(true);
        f.mvc.perform(get("/owner/bookings/1").with(user(f.actor(2)))).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"contractSigned\"")))
                .andExpect(content().string(containsString("600.000 VNĐ")))
                .andExpect(content().string(containsString("1.400.000 VNĐ")))
                .andExpect(content().string(not(containsString("/owner/bookings/1/complete"))));
        bookings().confirmHandover(1L, true, f.actor(2));
        for (var path : List.of("/owner/bookings/1", "/bookings/1", "/owner/bookings", "/bookings")) {
            f.mvc.perform(get(path).with(user(f.actor(path.startsWith("/owner") ? 2 : 4)))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("Đang thuê")));
        }
        var ownerHtml=f.mvc.perform(get("/owner/bookings/1").with(user(f.actor(2)))).andReturn().getResponse().getContentAsString();
        assertThat(ownerHtml.substring(ownerHtml.indexOf("<main"),ownerHtml.indexOf("</main>")))
                .contains("Thời điểm giao xe:","01/12/2030 10:00");
        f.mvc.perform(get("/bookings/1").with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/bookings/1/cancel"))))
                .andExpect(content().string(containsString("01/12/2030 10:00")));
        f.mvc.perform(get("/bookings/1/inspections/BEFORE").with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Xác nhận hợp đồng và giao xe")));
    }

    @Test void repeatAfterDateWindowDoesNotChangeActorTimeOrDuplicateEvidence() throws Exception {
        ready(true); bookings().confirmHandover(1L, true, f.actor(2));
        long notifications=f.count("notifications");
        var events=inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4)).events();
        f.clock.at.set(Instant.parse("2031-01-01T00:00:00Z"));
        bookings().confirmHandover(1L,true,f.actor(2));
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getVehicleReceivedAt()).isEqualTo(HANDOVER);
        assertThat(f.count("notifications")).isEqualTo(notifications);
        assertThat(inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4)).events()).isEqualTo(events);
    }

    @ParameterizedTest @ValueSource(strings={"missing","false"})
    void checkboxMustBeTrueEvenForDirectPost(String value) throws Exception {
        ready(true);
        var request=post("/owner/bookings/1/handover").with(user(f.actor(2))).with(csrf());
        if(value.equals("false"))request.param("contractSigned","false");
        f.mvc.perform(request).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
        assertThat(bookings().getBookingForView(1L,f.actor(2)).getVehicleReceivedAt()).isNull();
    }

    @ParameterizedTest @ValueSource(longs={1,3,4,5})
    void nonOwnerCannotHandoverThroughServiceOrHttp(long actorId) throws Exception {
        ready(true); var actor=f.actor(actorId);
        assertThatThrownBy(()->bookings().confirmHandover(1L,true,actor)).isInstanceOfAny(
                com.carrent.common.exception.ForbiddenException.class, com.carrent.common.exception.NotFoundException.class);
        f.mvc.perform(post("/owner/bookings/1/handover").param("contractSigned","true").with(user(actor)).with(csrf()))
                .andExpect(actorId==3?status().isNotFound():status().isForbidden());
        assertThat(bookings().getBookingForView(1L,f.actor(2)).getVehicleReceivedAt()).isNull();
    }

    @Test void disabledOwnerAndStalePrincipalCannotHandover() throws Exception {
        ready(true); var actor=f.actor(2); f.jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=2");
        assertThatThrownBy(()->bookings().confirmHandover(1L,true,actor)).isInstanceOf(com.carrent.common.exception.ForbiddenException.class);
        f.mvc.perform(post("/owner/bookings/1/handover").param("contractSigned","true").with(user(actor)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test void csrfAndLegacyCannotBeBypassed() throws Exception {
        ready(true);
        f.mvc.perform(post("/owner/bookings/1/handover").param("contractSigned","true").with(user(f.actor(2))))
                .andExpect(status().isForbidden());
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY',inspection_required=0 WHERE id=1");
        assertThatThrownBy(()->bookings().confirmHandover(1L,true,f.actor(2))).isInstanceOf(BusinessException.class);
        f.mvc.perform(get("/owner/bookings/1").with(user(f.actor(2)))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"contractSigned\""))));
    }

    @ParameterizedTest @ValueSource(strings={"2030-11-30T17:00:00Z","2030-12-02T16:59:59.999999Z"})
    void vietnamDayBoundariesAreInclusiveStartExclusiveEnd(String at) throws Exception {
        ready(true); f.clock.at.set(Instant.parse(at));
        bookings().confirmHandover(1L,true,f.actor(2));
        assertThat(bookings().getBookingForView(1L,f.actor(2)).getVehicleReceivedAt()).isEqualTo(Instant.parse(at));
    }

    @ParameterizedTest @ValueSource(strings={"inspection_events","notifications"})
    void evidenceOrNoticeFailureRollsBackContractAndCustody(String table) throws Exception {
        ready(true); long eventCount=f.count("inspection_events"), notificationCount=f.count("notifications");
        f.jdbc.execute("CREATE TRIGGER p67_fail BEFORE INSERT ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='P67 injected rollback'");
        try { assertThatThrownBy(()->bookings().confirmHandover(1L,true,f.actor(2))).isInstanceOf(RuntimeException.class); }
        finally { f.jdbc.execute("DROP TRIGGER p67_fail"); }
        var b=bookings().getBookingForView(1L,f.actor(2));
        assertThat(b.getVehicleReceivedAt()).isNull(); assertThat(b.getContractConfirmedBy()).isNull(); assertThat(b.getContractConfirmedAt()).isNull();
        assertThat(f.count("inspection_events")).isEqualTo(eventCount);assertThat(f.count("notifications")).isEqualTo(notificationCount);
        bookings().confirmHandover(1L,true,f.actor(2));
        assertThat(bookings().getBookingForView(1L,f.actor(2)).getVehicleReceivedAt()).isEqualTo(HANDOVER);
    }

    @Test void simultaneousTabsCommitOneHandoverEventAndNotice() throws Exception {
        ready(true); var owner=f.actor(2);
        race(()->bookings().confirmHandover(1L,true,owner),false,()->bookings().confirmHandover(1L,true,owner),false);
        assertThat(inspections().getView(1L,InspectionPhase.BEFORE,f.actor(4)).events())
                .filteredOn(e->e.action()==InspectionEventAction.PICKUP_CONFIRMED).hasSize(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='INSPECTION_PICKUP_CONFIRMED'",Long.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationCannotErasePaidOrHandedOverBookingInEitherOrder(boolean cancelFirst) throws Exception {
        ready(true); var owner=f.actor(2); var customer=f.actor(4);
        Runnable handover=()->bookings().confirmHandover(1L,true,owner);
        Runnable cancel=()->assertThatThrownBy(()->bookings().cancelBooking(1L,customer)).isInstanceOf(BusinessException.class);
        race(cancelFirst?cancel:handover,cancelFirst,cancelFirst?handover:cancel,!cancelFirst);
        var b=bookings().getBookingForView(1L,customer);
        assertThat(b.getStatus().name()).isEqualTo("CONFIRMED");assertThat(b.getVehicleReceivedAt()).isEqualTo(HANDOVER);
        assertThat(f.count("refunds")).isZero();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void unpaidCancellationWinsAndHandoverIsRejectedInEitherOrder(boolean cancelFirst) throws Exception {
        ready(false); var owner=f.actor(2); var customer=f.actor(4);
        Runnable handover=()->assertThatThrownBy(()->bookings().confirmHandover(1L,true,owner)).isInstanceOf(BusinessException.class);
        Runnable cancel=()->bookings().cancelBooking(1L,customer);
        race(cancelFirst?cancel:handover,!cancelFirst,cancelFirst?handover:cancel,cancelFirst);
        var b=bookings().getBookingForView(1L,customer);
        assertThat(b.getStatus().name()).isEqualTo("CANCELLED");assertThat(b.getVehicleReceivedAt()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='PICKUP_CONFIRMED'",Long.class)).isZero();
    }

    @ParameterizedTest @ValueSource(longs={1,2})
    void concurrentCustodyWithStaleBookingsCanOnlyHandOverOne(long firstId) throws Exception {
        ready(true); ready(2L,true); var owner=f.actor(2);long secondId=firstId==1?2:1;
        race(()->bookings().confirmHandover(firstId,true,owner),false,
                ()->assertThatThrownBy(()->bookings().confirmHandover(secondId,true,owner)).isInstanceOf(BusinessException.class),true);
        assertThat(bookings().getBookingForView(firstId,owner).getVehicleReceivedAt()).isEqualTo(HANDOVER);
        assertThat(bookings().getBookingForView(secondId,owner).getVehicleReceivedAt()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE car_id=1 AND vehicle_received_at IS NOT NULL AND vehicle_returned_at IS NULL",Long.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"money","report","cancelled"})
    void handoverRechecksPrerequisitesAfterOtherConnectionCommits(String changed) throws Exception {
        ready(true);var owner=f.actor(2);
        Runnable change=()->{
            if(changed.equals("money"))f.jdbc.update("UPDATE payment_obligations SET amount_settled=599999");
            else if(changed.equals("report"))f.jdbc.update("UPDATE booking_inspections SET status='DISPUTED'");
            else f.jdbc.update("UPDATE bookings SET status='CANCELLED' WHERE id=1");
        };
        race(change,false,()->assertThatThrownBy(()->bookings().confirmHandover(1L,true,owner)).isInstanceOf(BusinessException.class),true);
        assertThat(bookings().getBookingForView(1L,owner).getVehicleReceivedAt()).isNull();
    }

    void race(Runnable first,boolean firstRollsBack,Runnable second,boolean secondRollsBack) throws Exception {
        var held=new java.util.concurrent.CountDownLatch(1);var snapshot=new java.util.concurrent.CountDownLatch(1);
        var connections=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->runTransaction(()->{
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                f.app.getBean(com.carrent.booking.RentalPaymentHandler.class).lockSubject(1L);
                held.countDown();await(snapshot);first.run();
            },firstRollsBack));
            var b=pool.submit(()->{await(held);runTransaction(()->{
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                f.em.find(com.carrent.booking.Booking.class,1L).getVehicleReceivedAt();
                f.em.find(com.carrent.booking.Booking.class,2L).getVehicleReceivedAt();
                f.em.createQuery("select o from PaymentObligation o",PaymentObligation.class).getResultList().forEach(o->o.getStatus());
                f.em.createQuery("select r from BookingInspection r",BookingInspection.class).getResultList().forEach(r->r.getStatus());
                snapshot.countDown();second.run();
            },secondRollsBack);});
            try {a.get(40,java.util.concurrent.TimeUnit.SECONDS);b.get(40,java.util.concurrent.TimeUnit.SECONDS);}
            finally {held.countDown();snapshot.countDown();}
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE status='CANCELLED' AND vehicle_received_at IS NOT NULL",Long.class)).isZero();
    }
    static void runTransaction(Runnable action,boolean rollback) {
        try {f.tx.executeWithoutResult(s->action.run());assertThat(rollback).isFalse();}
        catch(org.springframework.transaction.UnexpectedRollbackException ex) {if(!rollback)throw ex;}
    }
    static void await(java.util.concurrent.CountDownLatch latch) {
        try {if(!latch.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Race timed out");}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new AssertionError(ex);}
    }
}
