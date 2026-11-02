package com.carrent.payment;

import com.carrent.inspection.*;
import com.carrent.booking.*;
import com.carrent.common.exception.BusinessException;
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

class RentalAfterReturnTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
    static final Instant HANDOVER = Instant.parse("2026-10-29T03:00:00Z");
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root", root.toString());
        System.setProperty("carrent.rental.advance.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.inspection.storage-root"); System.clearProperty("carrent.rental.advance.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void ready() throws Exception {
        for (var table : List.of("inspection_events", "inspection_images", "booking_inspections", "notifications")) f.jdbc.update("DELETE FROM " + table);
        f.reset(); f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE users SET role=CASE WHEN id=2 THEN 'OWNER' ELSE 'CUSTOMER' END WHERE id IN(2,4)");
        f.jdbc.update("UPDATE bookings SET status='CONFIRMED',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at='2026-10-28',before_accepted_at='2026-10-28',advance_amount=600000,balance_amount=1400000,advance_paid_at='2026-10-28',vehicle_received_at='2026-10-29 03:00:00',vehicle_returned_at=NULL,after_reminder_sent_at=NULL,contract_confirmed_at='2026-10-29 03:00:00',contract_confirmed_by=2,start_date='2026-10-29',end_date='2026-10-31',price_per_day_snapshot=1000000,total_amount=2000000 WHERE id=1");
        var service=inspections();
        var form=new InspectionForm(); form.setConditionNote("Xe sạch"); form.setVisibleDamage("Không phát hiện");
        form.setOdometerKm(12500); form.setEnergyPercent(70); form.setAccessoriesNote("Chìa khóa");
        service.saveDraft(1L, InspectionPhase.AFTER, form, f.actor(4));
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",out);
        var photo=new MockMultipartFile("images","a.png","image/png",out.toByteArray());
        service.addImages(1L,InspectionPhase.AFTER,List.of(photo,photo,photo,photo),f.actor(4));
    }
    InspectionService inspections() { return f.app.getBean(InspectionService.class); }
    BookingService bookings() { return f.app.getBean(BookingService.class); }
    ReturnReceiptForm receipt(ReturnReceiptForm.Decision decision, String note) {
        var form=new ReturnReceiptForm(); form.setDecision(decision); form.setNote(note); return form;
    }
    void submit() { inspections().submitAfter(1L,f.actor(4)); }
    void accepted() { bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED,null),f.actor(2)); }
    @Test void submissionFreezesEvidenceAndOnlyNotifiesOwner() throws Exception {
        submit(); submit();
        var view=inspections().getView(1L,InspectionPhase.AFTER,f.actor(4));
        assertThat(view.status()).isEqualTo(InspectionStatus.SUBMITTED);
        assertThat(view.submittedAt()).isEqualTo(PaymentFlowFixture.NOW);
        assertThat(view.editable()).isFalse();
        assertThat(f.jdbc.queryForObject("SELECT vehicle_returned_at FROM bookings WHERE id=1",java.sql.Timestamp.class)).isNull();
        assertThat(f.count("payment_obligations")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=2 AND related_entity_id=1",Long.class)).isEqualTo(1);
        var photo=view.images().getFirst();
        assertThatThrownBy(()->inspections().saveDraft(1L,InspectionPhase.AFTER,new InspectionForm(),f.actor(4))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->inspections().deleteDraftImage(1L,InspectionPhase.AFTER,photo.id(),f.actor(4))).isInstanceOf(BusinessException.class);
        assertThat(inspections().getView(1L,InspectionPhase.AFTER,f.actor(2)).images()).hasSize(4);
    }

    @ParameterizedTest @ValueSource(strings={"before-handover","returned","cancelled","pending","legacy","wrong-author","missing-note","missing-images"})
    void invalidSubmissionDoesNotPublishOrNotify(String invalid) {
        switch(invalid) {
            case "before-handover" -> f.jdbc.update("UPDATE bookings SET vehicle_received_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL WHERE id=1");
            case "returned" -> f.jdbc.update("UPDATE bookings SET vehicle_returned_at='2026-10-30' WHERE id=1");
            case "cancelled","pending" -> f.jdbc.update("UPDATE bookings SET status=? WHERE id=1",invalid.toUpperCase());
            case "legacy" -> f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY' WHERE id=1");
            case "wrong-author" -> f.jdbc.update("UPDATE booking_inspections SET author_id=2");
            case "missing-note" -> f.jdbc.update("UPDATE booking_inspections SET condition_note=NULL");
            case "missing-images" -> f.jdbc.update("DELETE FROM inspection_images");
        }
        assertThatThrownBy(this::submit).isInstanceOf(RuntimeException.class);
        assertThat(f.count("notifications")).isZero();assertThat(f.count("inspection_events")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM booking_inspections",String.class)).isEqualTo("DRAFT");
    }

    @ParameterizedTest @ValueSource(strings={"2026-10-29T04:00:00Z","2026-10-31T03:00:00Z","2026-11-03T03:00:00Z"})
    void earlyOnDateAndLateReturnKeepSnapshotAndStartDeadlineAtActualReceipt(String at) {
        f.clock.at.set(Instant.parse(at)); submit(); accepted();
        var b=bookings().getBookingForView(1L,f.actor(4));
        assertThat(b.getVehicleReturnedAt()).isEqualTo(Instant.parse(at));
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getStartDate().toString()).isEqualTo("2026-10-29");assertThat(b.getEndDate().toString()).isEqualTo("2026-10-31");
        assertThat(b.getTotalAmount()).isEqualTo(2000000L);assertThat(b.getPricePerDaySnapshot()).isEqualTo(1000000L);
        var balance=f.app.getBean(PaymentObligationRepository.class).findByBusinessKey("BOOKING:1:BALANCE").orElseThrow();
        assertThat(balance.getAmountDue()).isEqualTo(1400000L);assertThat(balance.getAmountSettled()).isZero();
        assertThat(balance.getDueAt()).isEqualTo(Instant.parse(at).plusSeconds(3600));
        assertThat(balance.getStatus()).isEqualTo(PaymentObligationStatus.OPEN);
        long notices=f.count("notifications"); f.clock.at.set(Instant.parse(at).plusSeconds(8000));accepted();
        assertThat(f.count("payment_obligations")).isEqualTo(1);assertThat(f.count("notifications")).isEqualTo(notices);
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getVehicleReturnedAt()).isEqualTo(Instant.parse(at));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='RETURN_CONFIRMED'",Long.class)).isEqualTo(1);
        var custody=f.tx.execute(s->f.app.getBean(BookingRepository.class).findOutstandingCustodyForUpdate(1L,2L));
        var overlaps=f.tx.execute(s->f.app.getBean(BookingRepository.class).findConflictsForUpdate(1L,b.getStartDate(),b.getEndDate()));
        assertThat(custody).isEmpty();assertThat(overlaps).isNotEmpty();
    }

    @Test void notReceivedIsDurableIdempotentAndCanThenReceiveWithDispute() {
        submit();var no=receipt(ReturnReceiptForm.Decision.NOT_RECEIVED,null);var owner=f.actor(2);
        bookings().confirmReturn(1L,no,owner);bookings().confirmReturn(1L,no,owner);
        assertThat(f.count("payment_obligations")).isZero();
        assertThat(bookings().getBookingForView(1L,owner).getVehicleReturnedAt()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='RETURN_NOT_RECEIVED'",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_DISPUTED,"  "),owner)).isInstanceOf(BusinessException.class);
        bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_DISPUTED,"Vết xước <script>1</script>"),owner);
        assertThat(inspections().getView(1L,InspectionPhase.AFTER,owner).status()).isEqualTo(InspectionStatus.DISPUTED);
        assertThat(f.count("payment_obligations")).isEqualTo(1);
        assertThatThrownBy(this::accepted).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest @ValueSource(strings={"draft","missing","wrong-author","missing-images","missing-note","before-handover","submitted-before-handover","future-submitted","bad-balance-snapshot"})
    void badAfterRollsBackBalanceCreation(String invalid) {
        if(!invalid.equals("draft"))submit();
        switch(invalid) {
            case "missing" -> {f.jdbc.update("DELETE FROM inspection_events");f.jdbc.update("DELETE FROM inspection_images");f.jdbc.update("DELETE FROM booking_inspections");}
            case "wrong-author" -> f.jdbc.update("UPDATE booking_inspections SET author_id=2");
            case "missing-images" -> f.jdbc.update("DELETE FROM inspection_images");
            case "missing-note" -> f.jdbc.update("UPDATE booking_inspections SET condition_note=NULL");
            case "before-handover" -> f.jdbc.update("UPDATE bookings SET vehicle_received_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL WHERE id=1");
            case "submitted-before-handover" -> f.jdbc.update("UPDATE booking_inspections SET submitted_at='2026-10-28'");
            case "future-submitted" -> f.jdbc.update("UPDATE booking_inspections SET submitted_at='2030-01-01'");
            case "bad-balance-snapshot" -> f.jdbc.update("UPDATE bookings SET balance_amount=1400001 WHERE id=1");
        }
        long notices=f.count("notifications");
        assertThatThrownBy(this::accepted).isInstanceOf(BusinessException.class);
        assertThat(f.count("payment_obligations")).isZero();assertThat(f.count("notifications")).isEqualTo(notices);
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getVehicleReturnedAt()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"submit","return","reminder"})
    void notificationFailureRollsBackWholeCommandAndRetryWorks(String command) {
        if(!command.equals("submit"))submit();
        if(command.equals("reminder"))f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));
        long notices=f.count("notifications"),events=f.count("inspection_events");
        String type=command.equals("submit")?"INSPECTION_AFTER_SUBMITTED":command.equals("return")?"INSPECTION_RETURN_CONFIRMED":"INSPECTION_AFTER_RETURN_OVERDUE";
        String recipient=command.equals("reminder")?" AND NEW.user_id=1":"";
        f.jdbc.execute("CREATE TRIGGER fail_after_notice BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='"+type+"'"+recipient+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='offline injected failure'; END IF; END");
        Runnable action=command.equals("submit")?this::submit:command.equals("return")?this::accepted:()->bookings().remindAfterReturn(1L);
        try {assertThatThrownBy(action::run).isInstanceOf(RuntimeException.class);}finally{f.jdbc.execute("DROP TRIGGER fail_after_notice");}
        assertThat(f.count("notifications")).isEqualTo(notices);assertThat(f.count("inspection_events")).isEqualTo(events);
        assertThat(f.count("payment_obligations")).isZero();
        var b=bookings().getBookingForView(1L,f.actor(4));assertThat(b.getVehicleReturnedAt()).isNull();assertThat(b.getAfterReminderSentAt()).isNull();
        action.run();
    }

    @ParameterizedTest @ValueSource(longs={1,3,5})
    void wrongActorCannotSubmitOrReceive(long actorId) {
        var actor=f.actor(actorId);
        assertThatThrownBy(()->inspections().submitAfter(1L,actor)).isInstanceOf(RuntimeException.class);
        submit();
        assertThatThrownBy(()->bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED,null),actor)).isInstanceOf(RuntimeException.class);
        assertThat(f.count("payment_obligations")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"submit","return"})
    void stalePrincipalMustStillBeActiveAndKeepCurrentRole(String command) {
        if(command.equals("return"))submit();
        var principal=f.actor(command.equals("return")?2:4);
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=?",principal.getId());
        assertThatThrownBy(()->{if(command.equals("return"))bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED,null),principal);else inspections().submitAfter(1L,principal);}).isInstanceOf(RuntimeException.class);
        assertThat(f.count("payment_obligations")).isZero();
    }

    @Test void reminderUsesSubmissionBoundaryOnceAndDoesNotResetOnReads() {
        submit();f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3599));bookings().remindAfterReturn(1L);
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getAfterReminderSentAt()).isNull();
        inspections().getView(1L,InspectionPhase.AFTER,f.actor(2));
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));bookings().remindAfterReturn(1L);bookings().remindAfterReturn(1L);
        assertThat(bookings().getBookingForView(1L,f.actor(4)).getAfterReminderSentAt()).isEqualTo(PaymentFlowFixture.NOW.plusSeconds(3600));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='INSPECTION_AFTER_RETURN_OVERDUE'",Long.class)).isEqualTo(2);
        assertThat(inspections().getView(1L,InspectionPhase.AFTER,f.actor(2)).submittedAt()).isEqualTo(PaymentFlowFixture.NOW);
    }

    @ParameterizedTest @ValueSource(strings={"submit","return","not-received","reminder-first","return-first","report-changed"})
    void twoMysqlConnectionsRecheckCurrentStateAndDeduplicateCommands(String mode) throws Exception {
        var customer=f.actor(4);var owner=f.actor(2);
        Runnable send=()->inspections().submitAfter(1L,customer);
        Runnable receive=()->bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED,null),owner);
        Runnable no=()->bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.NOT_RECEIVED,"Chưa nhận xe"),owner);
        Runnable remind=()->bookings().remindAfterReturn(1L);
        if(!mode.equals("submit")) {submit();f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(3600));}
        switch(mode) {
            case "submit" -> race(send,false,send,false);
            case "return" -> race(receive,false,receive,false);
            case "not-received" -> race(no,false,no,false);
            case "reminder-first" -> race(remind,false,receive,false);
            case "return-first" -> race(receive,false,remind,false);
            case "report-changed" -> race(()->f.jdbc.update("UPDATE booking_inspections SET author_id=2"),false,
                    ()->assertThatThrownBy(receive::run).isInstanceOf(BusinessException.class),true);
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='SUBMITTED'",Long.class)).isEqualTo(1);
        assertThat(f.count("payment_obligations")).isEqualTo(List.of("return","reminder-first","return-first").contains(mode)?1:0);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='RETURN_CONFIRMED'",Long.class))
                .isEqualTo(List.of("return","reminder-first","return-first").contains(mode)?1:0);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='INSPECTION_AFTER_RETURN_OVERDUE'",Long.class))
                .isEqualTo(mode.equals("reminder-first")?2:0);
        if(mode.equals("not-received"))assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='RETURN_NOT_RECEIVED'",Long.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void returnVersusDuplicateAdvanceReceiptKeepsBalanceAndRefundSeparate(boolean returnFirst) throws Exception {
        prepareRealAdvanceAndAfter();
        var owner=f.actor(2);
        var intent=f.app.getBean(PaymentIntentRepository.class).findFirstByObligationBusinessKeyOrderByCreatedAtDescIdDesc("BOOKING:1:ADVANCE").orElseThrow();
        long inbox=f.receive(887002,intent.getCode(),"600000");
        var claim=f.app.getBean(InboxWorkService.class).claim(inbox);
        assertThat(claim).isNotNull();
        Runnable receive=()->bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED,null),owner);
        Runnable process=()->f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());
        race(returnFirst?receive:process,false,returnFirst?process:receive,false);
        var balance=f.app.getBean(PaymentObligationRepository.class).findByBusinessKey("BOOKING:1:BALANCE").orElseThrow();
        assertThat(balance.getAmountSettled()).isZero();assertThat(balance.getAmountDue()).isEqualTo(1400000L);
        assertThat(f.count("payment_obligations")).isEqualTo(2);assertThat(f.count("payment_receipts")).isEqualTo(2);
        assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(bookings().getBookingForView(1L,owner).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @ParameterizedTest @ValueSource(strings={"submit","return","submit-role","return-role"})
    void actorBanCommittedAfterEarlierSnapshotMustBlockCommand(String command) throws Exception {
        boolean returning=command.startsWith("return");
        if(returning)submit();
        var principal=f.actor(returning?2:4);
        Runnable action=()->{if(returning)bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED,null),principal);
            else inspections().submitAfter(1L,principal);};
        Runnable change=()->{if(command.endsWith("role"))f.jdbc.update("UPDATE users SET role=? WHERE id=?",returning?"CUSTOMER":"OWNER",principal.getId());
            else f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=?",principal.getId());};
        race(change,false,
                ()->assertThatThrownBy(action::run).isInstanceOf(RuntimeException.class),true);
        assertThat(f.count("payment_obligations")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"amount","purpose","payer","collector","deadline","stale-financial-row"})
    void repeatedReturnRejectsMismatchedCurrentBalanceWithoutResettingReceipt(String invalid) throws Exception {
        submit();accepted();var owner=f.actor(2);
        var returned=bookings().getBookingForView(1L,owner).getVehicleReturnedAt();long notices=f.count("notifications");
        Runnable change=()->{
            switch(invalid) {
                case "amount","stale-financial-row" -> f.jdbc.update("UPDATE payment_obligations SET amount_due=1400001");
                case "purpose" -> f.jdbc.update("UPDATE payment_obligations SET purpose='ADVANCE'");
                case "payer" -> f.jdbc.update("UPDATE payment_obligations SET payer_user_id=1");
                case "collector" -> f.jdbc.update("UPDATE payment_obligations SET collector_user_id=1");
                case "deadline" -> f.jdbc.update("UPDATE payment_obligations SET due_at='2030-01-01'");
            }
        };
        Runnable retry=()->assertThatThrownBy(()->bookings().confirmReturn(1L,receipt(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED,null),owner)).isInstanceOf(BusinessException.class);
        if(invalid.equals("purpose")) {
            assertThatThrownBy(change::run).isInstanceOf(org.springframework.dao.DataAccessException.class);
            accepted();
        } else if(invalid.equals("stale-financial-row"))race(change,false,retry,true);else{change.run();retry.run();}
        assertThat(bookings().getBookingForView(1L,owner).getVehicleReturnedAt()).isEqualTo(returned);
        assertThat(f.count("notifications")).isEqualTo(notices);assertThat(f.count("payment_obligations")).isEqualTo(1);
    }

    void prepareRealAdvanceAndAfter() throws Exception {
        for(var table:List.of("inspection_events","inspection_images","booking_inspections","notifications"))f.jdbc.update("DELETE FROM "+table);
        f.reset();f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE bookings SET status='PENDING',owner_accepted_at=NULL,before_accepted_at=NULL,advance_paid_at=NULL,hold_expires_at=NULL,vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL,advance_amount=NULL,balance_amount=NULL,start_date='2030-12-01',end_date='2030-12-03' WHERE id=1");
        bookings().confirmBooking(1L,f.actor(2));
        var form=new InspectionForm();form.setConditionNote("Xe sạch");form.setVisibleDamage("Không phát hiện");form.setOdometerKm(12000);form.setEnergyPercent(80);form.setAccessoriesNote("Chìa khóa");
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",out);
        var photo=new MockMultipartFile("images","a.png","image/png",out.toByteArray());
        inspections().saveDraft(1L,InspectionPhase.BEFORE,form,f.actor(2));inspections().addImages(1L,InspectionPhase.BEFORE,List.of(photo,photo,photo,photo),f.actor(2));inspections().submitBefore(1L,f.actor(2));
        var intent=bookings().acceptBefore(1L,f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(887001,intent.code(),"600000"));
        f.clock.at.set(Instant.parse("2030-12-01T03:00:00Z"));bookings().confirmHandover(1L,true,f.actor(2));
        inspections().saveDraft(1L,InspectionPhase.AFTER,form,f.actor(4));inspections().addImages(1L,InspectionPhase.AFTER,List.of(photo,photo,photo,photo),f.actor(4));submit();
    }

    void race(Runnable first,boolean firstRollsBack,Runnable second,boolean secondRollsBack) throws Exception {
        var held=new java.util.concurrent.CountDownLatch(1);var snapshot=new java.util.concurrent.CountDownLatch(1);
        var connections=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->runTransaction(()->{
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                f.app.getBean(RentalPaymentHandler.class).lockSubject(1L);held.countDown();await(snapshot);first.run();
            },firstRollsBack));
            var b=pool.submit(()->{await(held);runTransaction(()->{
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                f.em.find(Booking.class,1L).getVehicleReturnedAt();
                f.em.createQuery("select o from PaymentObligation o",PaymentObligation.class).getResultList().forEach(o->o.getStatus());
                f.em.createQuery("select r from BookingInspection r",BookingInspection.class).getResultList().forEach(r->r.getStatus());
                f.em.createQuery("select e from InspectionEvent e",InspectionEvent.class).getResultList().forEach(e->e.getAction());
                snapshot.countDown();second.run();
            },secondRollsBack);});
            try {a.get(40,java.util.concurrent.TimeUnit.SECONDS);b.get(40,java.util.concurrent.TimeUnit.SECONDS);}
            finally {held.countDown();snapshot.countDown();}
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates();
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
