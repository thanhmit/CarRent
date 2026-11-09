package com.carrent.payment;

import com.carrent.booking.BookingService;
import com.carrent.inspection.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import com.carrent.common.exception.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

class BeforeInspectionTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root", root.toString());
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.inspection.storage-root"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM inspection_events");
        f.jdbc.update("DELETE FROM inspection_images");
        f.jdbc.update("DELETE FROM booking_inspections");
        f.jdbc.update("DELETE FROM notifications");
        f.reset();
        f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at=NULL,before_accepted_at=NULL,hold_expires_at=NULL WHERE id=1");
    }
    @Test void ownerAcceptKeepsPendingAndRepeatedAcceptPreservesTimestampAndNotification() {
        var bookings = f.app.getBean(BookingService.class);
        bookings.confirmBooking(1L, f.actor(2));
        var b = bookings.getBookingForView(1L, f.actor(2));
        assertThat(b.getStatus().name()).isEqualTo("PENDING");
        assertThat(b.getOwnerAcceptedAt()).isEqualTo(PaymentFlowFixture.NOW);
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(120));
        bookings.confirmBooking(1L, f.actor(2));
        assertThat(bookings.getBookingForView(1L, f.actor(2)).getOwnerAcceptedAt()).isEqualTo(PaymentFlowFixture.NOW);
        assertThat(f.count("notifications")).isEqualTo(1);
        assertThat(f.count("payment_obligations")).isZero();
    }
    private InspectionService inspections() { return f.app.getBean(InspectionService.class); }
    private void ready() throws Exception {
        f.app.getBean(BookingService.class).confirmBooking(1L, f.actor(2));
        var form = new InspectionForm();
        form.setConditionNote("Xe sạch"); form.setVisibleDamage("Không phát hiện");
        form.setOdometerKm(12345); form.setEnergyPercent(80); form.setAccessoriesNote("Chìa khóa");
        inspections().saveDraft(1L, InspectionPhase.BEFORE, form, f.actor(2));
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB), "png", out);
        var photo = new MockMultipartFile("images","a.png","image/png",out.toByteArray());
        inspections().addImages(1L, InspectionPhase.BEFORE, List.of(photo,photo,photo,photo), f.actor(2));
    }
    @Test void submitPublishesOnceWithServerTimeAndDoesNotCreatePaymentOrHandover() throws Exception {
        ready();
        inspections().submitBefore(1L, f.actor(2));
        var view = inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4));
        assertThat(view.status()).isEqualTo(InspectionStatus.SUBMITTED);
        assertThat(view.editable()).isFalse();
        assertThat(view.images()).hasSize(4);
        inspections().submitBefore(1L, f.actor(2));
        assertThat(f.count("inspection_events")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='INSPECTION_SUBMITTED'", Long.class)).isEqualTo(1);
        var b = f.app.getBean(BookingService.class).getBookingForView(1L, f.actor(4));
        assertThat(b.getBeforeAcceptedAt()).isNull(); assertThat(b.getVehicleReceivedAt()).isNull();
        assertThat(b.getHoldExpiresAt()).isNull(); assertThat(b.getStatus().name()).isEqualTo("PENDING");
        assertThat(f.count("payment_obligations")).isZero(); assertThat(f.count("payment_intents")).isZero();
    }
    @Test void addendumAndDisputeAppendEvidenceWithoutRewritingOrUnlockingTheOriginal() throws Exception {
        ready(); inspections().submitBefore(1L, f.actor(2));
        var original = inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4));
        inspections().disputeBefore(1L, "  Có vết xước  ", f.actor(4));
        inspections().appendBeforeAddendum(1L, "  Đã ghi nhận vết xước  ", f.actor(2));
        inspections().submitBefore(1L, f.actor(2));
        var view = inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4));
        assertThat(view.status()).isEqualTo(InspectionStatus.DISPUTED);
        assertThat(view.conditionNote()).isEqualTo(original.conditionNote());
        assertThat(view.images()).isEqualTo(original.images());
        assertThat(view.submittedAt()).isEqualTo(PaymentFlowFixture.NOW);
        assertThat(view.events()).extracting(e -> e.action().name()).containsExactly("SUBMITTED", "DISPUTED", "ADDENDUM");
        assertThat(view.events().get(1).actorId()).isEqualTo(4L);
        assertThat(view.events().get(1).note()).isEqualTo("Có vết xước");
        assertThat(view.events().get(2).note()).isEqualTo("Đã ghi nhận vết xước");
        assertThat(view.events()).allSatisfy(e -> assertThat(e.createdAt()).isEqualTo(PaymentFlowFixture.NOW));
        assertThat(f.count("payment_obligations")).isZero();
        assertThat(f.count("payment_intents")).isZero();
        assertThat(f.count("notifications")).isEqualTo(4);
        assertThatThrownBy(() -> inspections().saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(2)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inspections().deleteDraftImage(1L, InspectionPhase.BEFORE, view.images().getFirst().id(), f.actor(2)))
                .isInstanceOf(BusinessException.class);
    }
    @Test void httpSubmitAndFeedbackAreAccessibleAndPreserveEscapedEvidence() throws Exception {
        ready();
        f.mvc.perform(get("/bookings/1/inspections/BEFORE").with(user(f.actor(2))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/BEFORE/submit")));
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/submit").with(user(f.actor(2))).with(csrf())
                        .param("conditionNote", "FORGED").param("submittedAt", "2000-01-01").param("status", "ACCEPTED"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/dispute").with(user(f.actor(4))).with(csrf())
                        .param("note", "<script>alert(1)</script>").param("actorId", "1"))
                .andExpect(status().is3xxRedirection());
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/addendum").with(user(f.actor(2))).with(csrf()).param("note", "Bổ sung bằng chứng"))
                .andExpect(status().is3xxRedirection());
        f.mvc.perform(get("/bookings/1/inspections/BEFORE").with(user(f.actor(4))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(containsString("Bổ sung bằng chứng")))
                .andExpect(content().string(containsString("/BEFORE/dispute")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/BEFORE/draft"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/BEFORE/accept"))));
        assertThat(inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4)).conditionNote()).isEqualTo("Xe sạch");
    }

    @ParameterizedTest @ValueSource(strings={"submit","addendum","dispute"})
    void httpMutationsRequireCsrfAndDoNotAcceptAfterPhase(String action) throws Exception {
        ready();
        var actor = f.actor(action.equals("dispute") ? 4 : 2);
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/"+action).with(user(actor))).andExpect(status().isForbidden());
        f.mvc.perform(post("/bookings/1/inspections/AFTER/"+action).with(user(actor)).with(csrf()).param("note", "test"))
                .andExpect(status().isNotFound());
        assertThat(f.count("inspection_events")).isZero();
    }

    @ParameterizedTest @ValueSource(longs={1,3,4,5})
    void wrongOwnerCannotAcceptOrSubmit(long id) throws Exception {
        ready();
        assertThatThrownBy(() -> f.app.getBean(BookingService.class).confirmBooking(1L, f.actor(id))).isInstanceOfAny(ForbiddenException.class, NotFoundException.class);
        assertThatThrownBy(() -> inspections().submitBefore(1L, f.actor(id))).isInstanceOf(NotFoundException.class);
        assertThat(f.count("inspection_events")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"INACTIVE","BANNED"})
    void revokedSessionsCannotAcceptSubmitOrReact(String status) throws Exception {
        ready(); var owner=f.actor(2); var customer=f.actor(4);
        inspections().submitBefore(1L, owner);
        f.jdbc.update("UPDATE users SET status=? WHERE id IN (2,4)",status);
        assertThatThrownBy(() -> f.app.getBean(BookingService.class).confirmBooking(1L, owner)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> inspections().submitBefore(1L, owner)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> inspections().appendBeforeAddendum(1L, "note", owner)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> inspections().disputeBefore(1L, "note", customer)).isInstanceOf(ForbiddenException.class);
        assertThat(f.count("inspection_events")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"CANCELLED","EXPIRED","REJECTED","COMPLETED","CONFIRMED"})
    void closedOrPaidBookingsCannotSubmitOrReact(String status) throws Exception {
        ready(); f.jdbc.update("UPDATE bookings SET status=? WHERE id=1", status);
        assertThatThrownBy(() -> inspections().submitBefore(1L, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(f.count("inspection_events")).isZero();
    }

    @Test void ownerMustAcceptAndReportMustExistBeforeSubmit() throws Exception {
        assertThatThrownBy(() -> inspections().submitBefore(1L, f.actor(2))).isInstanceOf(BusinessException.class);
        f.app.getBean(BookingService.class).confirmBooking(1L, f.actor(2));
        assertThatThrownBy(() -> inspections().submitBefore(1L, f.actor(2))).isInstanceOf(NotFoundException.class);
    }

    @ParameterizedTest @ValueSource(strings={"condition_note","visible_damage","odometer_km","energy_percent","accessories_note"})
    void submissionRequiresEachOfTheFiveFields(String column) throws Exception {
        ready(); f.jdbc.update("UPDATE booking_inspections SET "+column+"=NULL");
        assertThatThrownBy(() -> inspections().submitBefore(1L, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(inspections().getView(1L, InspectionPhase.BEFORE, f.actor(2)).status()).isEqualTo(InspectionStatus.DRAFT);
        assertThat(f.count("inspection_events")).isZero();
    }

    @Test void threePhotosCannotBeSubmittedAndOriginalsRemainWritable() throws Exception {
        ready(); var view=inspections().getView(1L, InspectionPhase.BEFORE, f.actor(2));
        inspections().deleteDraftImage(1L, InspectionPhase.BEFORE, view.images().getFirst().id(), f.actor(2));
        assertThatThrownBy(() -> inspections().submitBefore(1L, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(inspections().getView(1L, InspectionPhase.BEFORE, f.actor(2)).editable()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"SUBMITTED","DISPUTED","ACCEPTED","RESOLVED"})
    void originalContentAndPhotoBytesStayImmutableAtEveryPublishedState(String status) throws Exception {
        ready(); inspections().submitBefore(1L, f.actor(2));
        f.jdbc.update("UPDATE booking_inspections SET status=?",status);
        var view=inspections().getView(1L, InspectionPhase.BEFORE, f.actor(2));
        var id=view.images().getFirst().id(); var bytes=inspections().readImage(1L, InspectionPhase.BEFORE, id, f.actor(4)).resource().getContentAsByteArray();
        assertThatThrownBy(() -> inspections().saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(2))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inspections().addImages(1L, InspectionPhase.BEFORE, List.of(new MockMultipartFile("images", bytes)), f.actor(2))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inspections().deleteDraftImage(1L, InspectionPhase.BEFORE, id, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4)).conditionNote()).isEqualTo("Xe sạch");
        assertThat(inspections().readImage(1L, InspectionPhase.BEFORE, id, f.actor(4)).resource().getContentAsByteArray()).isEqualTo(bytes);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   "})
    void feedbackNeedsANonblankReason(String note) throws Exception {
        ready(); inspections().submitBefore(1L, f.actor(2));
        assertThatThrownBy(() -> inspections().disputeBefore(1L, note, f.actor(4))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inspections().appendBeforeAddendum(1L, note, f.actor(2))).isInstanceOf(BusinessException.class);
        assertThat(f.count("inspection_events")).isEqualTo(1);
    }

    @Test void feedbackRejectsOversizedNotesAndWrongActors() throws Exception {
        ready(); inspections().submitBefore(1L, f.actor(2));
        assertThatThrownBy(() -> inspections().disputeBefore(1L, "a".repeat(2001), f.actor(4))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inspections().appendBeforeAddendum(1L, "a".repeat(2001), f.actor(2))).isInstanceOf(BusinessException.class);
        for (long id : new long[]{1,3,4,5}) assertThatThrownBy(() -> inspections().appendBeforeAddendum(1L, "note", f.actor(id))).isInstanceOf(NotFoundException.class);
        for (long id : new long[]{1,2,3,5}) assertThatThrownBy(() -> inspections().disputeBefore(1L, "note", f.actor(id))).isInstanceOf(NotFoundException.class);
        assertThat(f.count("inspection_events")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"DRAFT","ACCEPTED","RESOLVED"})
    void feedbackCannotAlterUnsubmittedOrAgreedEvidence(String status) throws Exception {
        ready(); if (!status.equals("DRAFT")) inspections().submitBefore(1L, f.actor(2));
        f.jdbc.update("UPDATE booking_inspections SET status=?",status);
        assertThatThrownBy(() -> inspections().disputeBefore(1L, "note", f.actor(4))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inspections().appendBeforeAddendum(1L, "note", f.actor(2))).isInstanceOf(BusinessException.class);
    }

    @Test void acceptedTimestampClosesFeedbackEvenIfReportStatusWasStale() throws Exception {
        ready(); inspections().submitBefore(1L, f.actor(2));
        f.jdbc.update("UPDATE bookings SET before_accepted_at=CURRENT_TIMESTAMP(6) WHERE id=1");
        assertThatThrownBy(() -> inspections().appendBeforeAddendum(1L, "note", f.actor(2))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inspections().disputeBefore(1L, "note", f.actor(4))).isInstanceOf(BusinessException.class);
        assertThat(inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4)).canDispute()).isFalse();
    }

    @Test void legacyConfirmKeepsItsHistoricalStatusAndDoesNotCreateAcceptanceEvidence() {
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY',inspection_required=0 WHERE id=1");
        var bookings=f.app.getBean(BookingService.class); bookings.confirmBooking(1L,f.actor(2));
        var b=bookings.getBookingForView(1L,f.actor(4));
        assertThat(b.getStatus().name()).isEqualTo("CONFIRMED"); assertThat(b.getOwnerAcceptedAt()).isNull();
        assertThat(f.count("inspection_events")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"ACCEPT","SUBMIT","ADDENDUM","DISPUTE"})
    void notificationFailureRollsBackTheWholeCommand(String action) throws Exception {
        if (!action.equals("ACCEPT")) ready();
        if (action.equals("ADDENDUM") || action.equals("DISPUTE")) inspections().submitBefore(1L, f.actor(2));
        long events=f.count("inspection_events"), notices=f.count("notifications");
        f.db.execute("CREATE TRIGGER p64_reject_notification BEFORE INSERT ON notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='P64 injected notification failure'");
        try {
            assertThatThrownBy(() -> {
                switch(action) {
                    case "ACCEPT" -> f.app.getBean(BookingService.class).confirmBooking(1L, f.actor(2));
                    case "SUBMIT" -> inspections().submitBefore(1L, f.actor(2));
                    case "ADDENDUM" -> inspections().appendBeforeAddendum(1L, "note", f.actor(2));
                    default -> inspections().disputeBefore(1L, "note", f.actor(4));
                }
            }).isInstanceOf(RuntimeException.class);
        } finally { f.db.execute("DROP TRIGGER p64_reject_notification"); }
        assertThat(f.count("inspection_events")).isEqualTo(events); assertThat(f.count("notifications")).isEqualTo(notices);
        if (action.equals("ACCEPT")) assertThat(f.app.getBean(BookingService.class).getBookingForView(1L,f.actor(2)).getOwnerAcceptedAt()).isNull();
        else {
            var view=inspections().getView(1L, InspectionPhase.BEFORE, f.actor(2));
            assertThat(view.status()).isEqualTo(action.equals("SUBMIT") ? InspectionStatus.DRAFT : InspectionStatus.SUBMITTED);
            assertThat(view.submittedAt()).isEqualTo(action.equals("SUBMIT") ? null : PaymentFlowFixture.NOW);
            assertThat(view.images()).hasSize(4);
        }
    }

    @Test void twoConnectionsSubmittingTheSameDraftPublishOnlyOneEvent() throws Exception {
        ready(); var owner=f.actor(2); var barrier=new java.util.concurrent.CyclicBarrier(2);
        var connections=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        Runnable submit=() -> f.tx.executeWithoutResult(s -> {
            connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            try { barrier.await(15,java.util.concurrent.TimeUnit.SECONDS); } catch(Exception e) { throw new AssertionError(e); }
            inspections().submitBefore(1L, owner);
        });
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(submit); var b=pool.submit(submit);
            a.get(20,java.util.concurrent.TimeUnit.SECONDS); b.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates(); assertThat(f.count("inspection_events")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='INSPECTION_SUBMITTED'",Long.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancelVersusSubmitUsesCurrentStateInEitherCommitOrder(boolean submitFirst) throws Exception {
        ready(); var owner=f.actor(2); var customer=f.actor(4);
        var firstHeld=new java.util.concurrent.CountDownLatch(1);
        var secondReady=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var connections=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var bookings=f.app.getBean(BookingService.class);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> f.tx.executeWithoutResult(s -> {
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                if (submitFirst) inspections().submitBefore(1L,owner); else bookings.cancelBooking(1L,customer);
                firstHeld.countDown(); await(release);
            }));
            try {
                assertThat(firstHeld.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second=pool.submit(() -> {
                    try {
                        f.tx.executeWithoutResult(s -> {
                            connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                            f.em.find(com.carrent.booking.Booking.class,1L);
                            inspections().getView(1L,InspectionPhase.BEFORE,owner);
                            secondReady.countDown();
                            if (submitFirst) bookings.cancelBooking(1L,customer); else inspections().submitBefore(1L,owner);
                        });
                        return true;
                    } catch(BusinessException expected) { return false; }
                });
                assertThat(secondReady.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); release.countDown();
                first.get(20,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(second.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(submitFirst);
            } finally { release.countDown(); }
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates();
        assertThat(bookings.getBookingForView(1L,customer).getStatus().name()).isEqualTo("CANCELLED");
        assertThat(inspections().getView(1L,InspectionPhase.BEFORE,owner).status())
                .isEqualTo(submitFirst ? InspectionStatus.SUBMITTED : InspectionStatus.DRAFT);
        assertThat(f.count("inspection_events")).isEqualTo(submitFirst ? 1 : 0);
        assertThat(f.count("payment_intents")).isZero();
    }
    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { if (!latch.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    @Test void ownerAndCustomerPagesShowTheBeforeWorkflowWithoutClaimingRentalOrPayment() throws Exception {
        f.mvc.perform(get("/owner/bookings/1").with(user(f.actor(2))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Chấp nhận yêu cầu")));
        f.mvc.perform(post("/owner/bookings/1/confirm").with(user(f.actor(2))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/bookings/1/inspections/BEFORE"))
                .andExpect(flash().attribute("successMessage", "Đã chấp nhận yêu cầu. Vui lòng chuẩn bị biên bản trước thuê."));
        f.mvc.perform(get("/owner/bookings/1").with(user(f.actor(2))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/bookings/1/inspections/BEFORE")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/owner/bookings/1/confirm\""))));
        f.mvc.perform(get("/bookings/1").with(user(f.actor(4))))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("/inspections/BEFORE"))));
        ready(); inspections().submitBefore(1L,f.actor(2));
        f.mvc.perform(get("/bookings/1").with(user(f.actor(4))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/bookings/1/inspections/BEFORE")));
    }
}
