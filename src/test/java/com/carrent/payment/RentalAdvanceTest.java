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

class RentalAdvanceTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
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
        f.jdbc.update("DELETE FROM bookings WHERE customer_note='P66 race B'");
        f.jdbc.update("UPDATE bookings SET status='PENDING',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at=NULL,before_accepted_at=NULL,hold_expires_at=NULL,advance_amount=NULL,balance_amount=NULL,advance_paid_at=NULL,cancelled_at=NULL,cancellation_fee=NULL,vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL,start_date='2030-12-01',end_date='2030-12-02',price_per_day_snapshot=2000001,total_amount=2000001 WHERE id=1");
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

    @Test void delayedSystemConfirmationIsExplainedOnResultAndRefundPage() throws Exception {
        var view = agree(); f.clock.at.set(view.expiresAt()); processor().expire(view.id());
        processor().process(f.receive(991001, view.code(), "600000"));
        var customer = f.actor(4);
        var display = f.payments.status(view.id(), customer);
        assertThat(display.message()).contains("hệ thống xác nhận chậm", "hoàn");
        f.mvc.perform(get("/payment/intents/" + view.id() + "/result").with(user(customer)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("hệ thống xác nhận chậm")));
        long refund = f.jdbc.queryForObject("SELECT id FROM refunds", Long.class);
        f.mvc.perform(get("/payment/finances/refunds/" + refund).with(user(customer)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("hệ thống xác nhận chậm")));
    }

    @Test void cancelledBookingDoesNotHidePendingOrSucceededRefund() throws Exception {
        var view = agree(); bookings().cancelBooking(1L, f.actor(4));
        processor().process(f.receive(991001, view.code(), "600000"));
        assertThat(f.payments.status(view.id(), f.actor(4)).message()).contains("đang chờ hoàn");
        f.jdbc.update("UPDATE refunds SET status='SUCCEEDED'");
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("REFUNDED");
        assertThat(f.payments.status(view.id(), f.actor(4)).message()).contains("Đã hoàn tiền");
    }

    @ParameterizedTest @ValueSource(strings = {"300000", "600001"})
    void mismatchedAdvancesStayReviewWithoutAccumulatingOrResettingHold(String amount) throws Exception {
        var view = agree(); processor().process(f.receive(991001, view.code(), amount));
        processor().process(f.receive(991002, view.code(), amount));
        assertThat(state("bookings")).isEqualTo("PENDING");
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipts WHERE status='REVIEW_REQUIRED'", Long.class)).isEqualTo(2);
        assertThat(f.count("refunds")).isZero();
        assertThat(f.payments.status(view.id(), f.actor(4)).expiresAt()).isEqualTo(view.expiresAt());
        f.clock.at.set(view.expiresAt()); processor().expire(view.id());
        assertThat(state("bookings")).isEqualTo("EXPIRED");
    }

    @ParameterizedTest @ValueSource(strings = {"payer", "collector", "account", "code"})
    void untrustedReceiptCannotConfirmRental(String mismatch) throws Exception {
        var view = agree();
        if (mismatch.equals("payer")) f.jdbc.update("UPDATE payment_obligations SET payer_user_id=5");
        if (mismatch.equals("collector")) f.jdbc.update("UPDATE payment_obligations SET collector_user_id=3");
        byte[] body = SePayInboxTest.body(991001, mismatch.equals("code") ? "UNKNOWNCODE" : view.code(), "600000");
        if (mismatch.equals("account")) body = new String(body, java.nio.charset.StandardCharsets.UTF_8)
                .replace("000123456", "000999999").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (mismatch.equals("account")) {
            final byte[] invalid = body;
            assertThatThrownBy(() -> f.app.getBean(SePayWebhookService.class).receive(101L, f.signed(invalid), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(f.count("webhook_inbox")).isZero();
            assertThat(state("bookings")).isEqualTo("PENDING");
            return;
        }
        f.app.getBean(SePayWebhookService.class).receive(101L, f.signed(body), body);
        processor().process(f.jdbc.queryForObject("SELECT id FROM webhook_inbox", Long.class));
        assertThat(state("payment_receipts")).isEqualTo("REVIEW_REQUIRED");
        assertThat(state("bookings")).isEqualTo("PENDING");
        assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations", Long.class)).isZero();
    }

    @ParameterizedTest @ValueSource(longs = {2, 4})
    void disabledUserDoesNotDiscardVerifiedBankMoney(long userId) throws Exception {
        var view = agree(); f.jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?", userId);
        processor().process(f.receive(991001, view.code(), "600000"));
        assertThat(state("webhook_inbox")).isEqualTo("PROCESSED"); assertThat(state("bookings")).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest @ValueSource(strings = {"confirm", "expire", "refund"})
    void notificationFailureRollsBackAllMoneyAndDomainChangesThenRetryCommitsOnce(String action) throws Exception {
        var view = agree();
        boolean expiry = action.equals("expire"), refund = action.equals("refund");
        if (refund) bookings().cancelBooking(1L, f.actor(4));
        long inbox = expiry ? 0 : f.receive(991001, view.code(), "600000");
        if (expiry) f.clock.at.set(view.expiresAt());
        String type = expiry ? "BOOKING_EXPIRED" : refund ? "REFUND_WAITING_RECIPIENT" : "BOOKING_CONFIRMED";
        f.jdbc.execute("CREATE TRIGGER p66_notify_failure BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='" + type + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='P66 injected notification failure'; END IF; END");
        try {
            if (expiry) assertThatThrownBy(() -> processor().expire(view.id())).isInstanceOf(RuntimeException.class);
            else processor().process(inbox);
            assertThat(state("bookings")).isEqualTo(refund ? "CANCELLED" : "PENDING");
            assertThat(f.count("refunds")).isZero(); assertThat(f.count("payment_receipts")).isZero();
            assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations", Long.class)).isZero();
            assertThat(state("payment_intents")).isEqualTo(refund ? "CANCELLED" : "ACTIVE");
        } finally { f.jdbc.execute("DROP TRIGGER p66_notify_failure"); }
        f.clock.at.set(f.clock.instant().plusSeconds(60));
        if (expiry) processor().expire(view.id()); else processor().process(inbox);
        assertThat(state("bookings")).isEqualTo(expiry ? "EXPIRED" : refund ? "CANCELLED" : "CONFIRMED");
        if (!expiry) assertThat(state("webhook_inbox")).isEqualTo("PROCESSED");
        assertThat(f.count("refunds")).isEqualTo(refund ? 1 : 0);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type=?", Long.class, type)).isEqualTo(refund ? 1 : 2);
    }

    @Test void expiryHidesQrAndKeepsResultPollingForAlreadyScannedBankTransfer() throws Exception {
        var view = agree(); var customer = f.actor(4);
        f.clock.at.set(view.expiresAt());
        assertThat(f.payments.status(view.id(), customer).qrUrl()).isNull();
        f.mvc.perform(get("/payment/intents/" + view.id()).with(user(customer)))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("id=\"payment-qr\""))))
                .andExpect(content().string(containsString("data-terminal=\"false\"")));
        processor().expire(view.id()); processor().process(receive(991001, view, view.expiresAt()));
        assertThat(f.payments.status(view.id(), customer).status()).isEqualTo("REFUND_PENDING");
        assertThat(state("bookings")).isEqualTo("EXPIRED");
    }

    @ParameterizedTest @ValueSource(ints = {899, 900, 901})
    void bankCreditBoundaryIsStrictEvenWhenWebhookArrivesLater(int seconds) throws Exception {
        var view = agree(); f.clock.at.set(view.expiresAt().plusSeconds(5));
        processor().process(receive(991001, view, PaymentFlowFixture.NOW.plusSeconds(seconds)));
        assertThat(state("bookings")).isEqualTo(seconds < 900 ? "CONFIRMED" : "EXPIRED");
        assertThat(f.count("refunds")).isEqualTo(seconds < 900 ? 0 : 1);
        if (seconds < 900) assertThat(bookings().getBookingForView(1L, f.actor(4)).getAdvancePaidAt()).isEqualTo(PaymentFlowFixture.NOW.plusSeconds(899));
        else assertThat(f.jdbc.queryForObject("SELECT reason FROM refunds", String.class)).isEqualTo("LATE_ADVANCE");
        assertCalendarAndRefundInvariant();
    }

    @Test void missingOwnerPayoutRetainsInboxForRetryWithoutUsingAdminMoney() throws Exception {
        var view = agree(); bookings().cancelBooking(1L, f.actor(4)); f.jdbc.update("DELETE FROM payment_channels WHERE id=103");
        long inbox = f.receive(991001, view.code(), "600000"); processor().process(inbox);
        assertThat(state("webhook_inbox")).isEqualTo("RECEIVED");
        assertThat(f.count("payment_receipts")).isZero(); assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT last_error FROM webhook_inbox", String.class)).isEqualTo("APPLY_FAILED");
        f.jdbc.update("INSERT INTO payment_channels(id,user_id,owner_role,provider,purpose,credential_reference,enabled,created_at,updated_at) VALUES(103,2,'OWNER','PAYOS','PAYOUT','owner-payout-fixture',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        f.clock.at.set(f.clock.instant().plusSeconds(60)); processor().process(inbox);
        assertThat(state("webhook_inbox")).isEqualTo("PROCESSED");
        assertThat(f.jdbc.queryForObject("SELECT payout_channel_id FROM refunds", Long.class)).isEqualTo(103);
    }

    @Test void expiredHoldCannotOpenAnotherAttemptAndCallbacksRequireHeldLocks() throws Exception {
        var view = agree();
        assertThatThrownBy(() -> bookings().expireHold(1L)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> f.tx.executeWithoutResult(s -> bookings().expireHold(1L))).isInstanceOf(IllegalStateException.class);
        f.clock.at.set(view.expiresAt()); processor().expire(view.id());
        long obligation = f.jdbc.queryForObject("SELECT id FROM payment_obligations", Long.class);
        var customer = f.actor(4);
        assertThatThrownBy(() -> f.payments.openAttempt(obligation, customer)).isInstanceOf(com.carrent.common.exception.BusinessException.class);
        assertThatThrownBy(() -> bookings().acceptBefore(1L, customer)).isInstanceOf(com.carrent.common.exception.BusinessException.class);
        assertThat(f.count("payment_intents")).isEqualTo(1);
        assertThat(f.payments.status(view.id(), customer).expiresAt()).isEqualTo(view.expiresAt());
    }

    @Test void twoDistinctBankTransfersAgainstOldSnapshotsAllocateOnlyOneAndRefundTheOther() throws Exception {
        var view = agree(); var firstInbox = f.receive(991001, view.code(), "600000"); var secondInbox = f.receive(991002, view.code(), "600000");
        var first = f.app.getBean(InboxWorkService.class).claim(firstInbox); var second = f.app.getBean(InboxWorkService.class).claim(secondInbox);
        var application = f.app.getBean(ReceiptApplicationService.class);
        race(view, () -> application.apply(firstInbox, first.leaseOwner()), () -> application.apply(secondInbox, second.leaseOwner()));
        assertThat(f.count("payment_receipts")).isEqualTo(2); assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts", Long.class)).isEqualTo(600000);
        assertThat(f.jdbc.queryForObject("SELECT reason FROM refunds", String.class)).isEqualTo("DUPLICATE_PAYMENT");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM webhook_inbox WHERE status='PROCESSED'", Long.class)).isEqualTo(2);
        assertCalendarAndRefundInvariant();
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "bank-late", "cancelled", "duplicate"})
    void moneyThatCannotGrantCalendarCreatesOneFullRefund(String outcome) throws Exception {
        var view = agree();
        if (outcome.equals("cancelled")) bookings().cancelBooking(1L, f.actor(4));
        if (outcome.equals("duplicate")) processor().process(f.receive(991001, view.code(), "600000"));
        f.clock.at.set(view.expiresAt().plusSeconds(5));
        if (outcome.equals("expired")) processor().expire(view.id());
        long inbox = receive(991002, view, outcome.equals("bank-late") ? view.expiresAt() : PaymentFlowFixture.NOW);
        processor().process(inbox); processor().process(inbox);
        assertThat(state("webhook_inbox")).isEqualTo("PROCESSED");
        assertThat(f.count("refunds")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds", Long.class)).isEqualTo(600000);
        assertThat(f.jdbc.queryForObject("SELECT reason FROM refunds", String.class)).isEqualTo(
                outcome.equals("expired") ? "SYSTEM_CONFIRMATION_DELAY" : outcome.equals("duplicate") ? "DUPLICATE_PAYMENT" : "LATE_ADVANCE");
        assertThat(state("refunds")).isEqualTo("WAITING_RECIPIENT");
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts WHERE provider_transaction_id='991002'", Long.class)).isZero();
        assertThat(state("bookings")).isEqualTo(outcome.equals("duplicate") ? "CONFIRMED" : outcome.equals("cancelled") ? "CANCELLED" : "EXPIRED");
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts", Long.class)).isEqualTo(outcome.equals("duplicate") ? 600000L : 0L);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_RECIPIENT'", Long.class)).isEqualTo(1);
        var display = f.payments.status(view.id(), f.actor(4));
        assertThat(display.status()).isEqualTo("REFUND_PENDING"); assertThat(display.qrUrl()).isNull();
    }

    @Test void expiryCommitsCalendarReleaseAndRepeatingItDoesNotNotifyAgain() throws Exception {
        var view = agree();
        f.clock.at.set(view.expiresAt().minusNanos(1000)); processor().expire(view.id());
        assertThat(state("bookings")).isEqualTo("PENDING");
        f.clock.at.set(view.expiresAt()); processor().expire(view.id()); processor().expire(view.id());
        assertThat(state("bookings")).isEqualTo("EXPIRED");
        assertThat(state("payment_obligations")).isEqualTo("EXPIRED");
        assertThat(state("payment_intents")).isEqualTo("EXPIRED");
        assertThat(f.count("payment_receipts")).isZero(); assertThat(f.count("refunds")).isZero();
        assertThat(bookings().getBookingForView(1L, f.actor(4)).getBeforeAcceptedAt()).isEqualTo(PaymentFlowFixture.NOW);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_EXPIRED'", Long.class)).isEqualTo(2);
        var conflicts = f.tx.execute(s -> f.app.getBean(com.carrent.booking.BookingRepository.class)
                .findConflictsForUpdate(1L, java.time.LocalDate.parse("2030-12-01"), java.time.LocalDate.parse("2030-12-02")));
        assertThat(conflicts).isEmpty();
    }

    @Test void timelyAdvanceConfirmsOnceUsingBankTimeWithoutContractOrHandover() throws Exception {
        var view = agree();
        f.clock.at.set(view.expiresAt().plusSeconds(5));
        var inbox = f.receive(991001, view.code(), "600000");
        processor().process(inbox); processor().process(inbox);
        assertThat(state("webhook_inbox")).isEqualTo("PROCESSED");
        var booking = bookings().getBookingForView(1L, f.actor(4));
        assertThat(booking.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(booking.getAdvancePaidAt()).isEqualTo(PaymentFlowFixture.NOW);
        assertThat(booking.getContractConfirmedAt()).isNull(); assertThat(booking.getContractConfirmedBy()).isNull();
        assertThat(booking.getVehicleReceivedAt()).isNull();
        assertThat(f.count("payment_receipts")).isEqualTo(1); assertThat(f.count("refunds")).isZero();
        assertThat(state("payment_obligations")).isEqualTo("SETTLED");
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("RECEIVED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_CONFIRMED'", Long.class)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void expiryAndApplyReadCurrentStateInBothCommitOrders(boolean applyFirst) throws Exception {
        var view = agree(); var inbox = f.receive(991001, view.code(), "600000");
        f.clock.at.set(view.expiresAt().plusSeconds(5));
        var claim = f.app.getBean(InboxWorkService.class).claim(inbox);
        Runnable apply = () -> f.app.getBean(ReceiptApplicationService.class).apply(inbox, claim.leaseOwner());
        Runnable expire = () -> f.app.getBean(ReceiptApplicationService.class).expire(view.id());
        race(view, applyFirst ? apply : expire, applyFirst ? expire : apply);
        assertThat(state("bookings")).isEqualTo(applyFirst ? "CONFIRMED" : "EXPIRED");
        assertThat(f.count("refunds")).isEqualTo(applyFirst ? 0 : 1);
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts", Long.class)).isEqualTo(applyFirst ? 600000L : 0L);
        assertCalendarAndRefundInvariant();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void bookingBAfterExpiryAndTimelyDelayedReceiptANeverEvictsB(boolean bookingBFirst) throws Exception {
        var view = agree(); f.clock.at.set(view.expiresAt()); processor().expire(view.id());
        var inbox = f.receive(991001, view.code(), "600000"); var claim = f.app.getBean(InboxWorkService.class).claim(inbox);
        var customer = f.actor(4); var owner = f.actor(2);
        Runnable createB = () -> {
            var form = new com.carrent.booking.BookingCreateForm(); form.setCarId(1L); form.setCustomerNote("P66 race B");
            form.setStartDate(java.time.LocalDate.parse("2030-12-01")); form.setEndDate(java.time.LocalDate.parse("2030-12-02"));
            var b = bookings().createBooking(form, customer); bookings().confirmBooking(b.getId(), owner);
        };
        Runnable applyA = () -> f.app.getBean(ReceiptApplicationService.class).apply(inbox, claim.leaseOwner());
        race(view, bookingBFirst ? createB : applyA, bookingBFirst ? applyA : createB);
        assertThat(state("bookings")).isEqualTo("EXPIRED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE customer_note='P66 race B' AND status='CONFIRMED'", Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT reason FROM refunds", String.class)).isEqualTo("SYSTEM_CONFIRMATION_DELAY");
        assertCalendarAndRefundInvariant();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void cancellationAndApplyCannotBothWin(boolean applyFirst) throws Exception {
        var view = agree(); var inbox = f.receive(991001, view.code(), "600000"); var customer = f.actor(4);
        var claim = f.app.getBean(InboxWorkService.class).claim(inbox);
        Runnable apply = () -> f.app.getBean(ReceiptApplicationService.class).apply(inbox, claim.leaseOwner());
        Runnable cancel = () -> {
            if (applyFirst) assertThatThrownBy(() -> bookings().cancelBooking(1L, customer)).isInstanceOf(com.carrent.common.exception.BusinessException.class);
            else bookings().cancelBooking(1L, customer);
        };
        // A rejected REQUIRED cancellation rolls back its outer transaction as expected.
        if (applyFirst) {
            race(view, apply, () -> {
                assertThat(state("bookings")).isIn("PENDING", "CONFIRMED");
                cancel.run();
            }, true);
        } else race(view, cancel, apply);
        assertThat(state("bookings")).isEqualTo(applyFirst ? "CONFIRMED" : "CANCELLED");
        assertThat(f.count("refunds")).isEqualTo(applyFirst ? 0 : 1);
        assertCalendarAndRefundInvariant();
    }

    void race(PaymentIntentView view, Runnable first, Runnable second) throws Exception { race(view, first, second, false); }
    void race(PaymentIntentView view, Runnable first, Runnable second, boolean secondRollsBack) throws Exception {
        var held = new java.util.concurrent.CountDownLatch(1); var snapshot = new java.util.concurrent.CountDownLatch(1);
        var connections = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> f.tx.executeWithoutResult(s -> {
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                f.app.getBean(RentalPaymentHandler.class).lockSubject(1L);
                held.countDown(); await(snapshot); first.run();
            }));
            var b = pool.submit(() -> {
                await(held);
                try { f.tx.executeWithoutResult(s -> {
                    connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    f.em.find(com.carrent.booking.Booking.class, 1L);
                    var intent = f.em.find(PaymentIntent.class, view.id()); intent.getObligation().getStatus();
                    snapshot.countDown(); second.run();
                }); } catch (org.springframework.transaction.UnexpectedRollbackException ex) {
                    if (!secondRollsBack) throw ex;
                }
            });
            try { a.get(30, java.util.concurrent.TimeUnit.SECONDS); b.get(30, java.util.concurrent.TimeUnit.SECONDS); }
            finally { held.countDown(); snapshot.countDown(); }
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates();
    }
    static void await(java.util.concurrent.CountDownLatch latch) {
        try { if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Race timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
    void assertCalendarAndRefundInvariant() {
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM bookings a JOIN bookings b ON a.id<b.id AND a.car_id=b.car_id AND a.start_date<b.end_date AND a.end_date>b.start_date WHERE a.status='CONFIRMED' AND b.status='CONFIRMED'", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipts p JOIN refunds r ON r.receipt_id=p.id WHERE p.allocated_amount>0 AND r.amount=p.amount", Long.class)).isZero();
    }
}
