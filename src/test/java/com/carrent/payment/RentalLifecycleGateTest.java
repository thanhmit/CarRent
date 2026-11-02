package com.carrent.payment;

import com.carrent.booking.*;
import com.carrent.inspection.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class RentalLifecycleGateTest {
    static PaymentFlowFixture f;
    static Steps steps;
    @TempDir static Path root;

    @BeforeAll static void start() throws Exception {
        f = Steps.boot(root); steps = new Steps(f);
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { steps.reset(); }

    @Test void realEvidenceAndBankReceiptsCompleteRentalWithoutErasingAfterDispute() throws Exception {
        var advance = steps.agree();
        var before = steps.inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4));
        assertThat(before.images()).hasSize(4);
        assertThat(steps.state()).isEqualTo("PENDING");
        steps.receive(advance, 600000, 61001);
        assertThat(steps.state()).isEqualTo("CONFIRMED");
        steps.bookings().confirmHandover(1L, true, f.actor(2));
        f.clock.at.set(f.clock.instant().plusSeconds(7200));
        steps.after(); steps.returnDisputed();
        var evidence = f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE phase='AFTER'");
        var images = f.jdbc.queryForList("SELECT * FROM inspection_images ORDER BY id");
        var events = f.jdbc.queryForList("SELECT * FROM inspection_events ORDER BY id");
        var balance = steps.balance();
        steps.receive(balance, 1400000, 61002);
        assertThat(steps.state()).isEqualTo("COMPLETED");
        assertThat(f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE phase='AFTER'")).isEqualTo(evidence);
        assertThat(f.jdbc.queryForList("SELECT * FROM inspection_images ORDER BY id")).isEqualTo(images);
        assertThat(f.jdbc.queryForList("SELECT * FROM inspection_events ORDER BY id")).isEqualTo(events);
        assertThat(steps.inspections().getView(1L, InspectionPhase.BEFORE, f.actor(4)).images()).isEqualTo(before.images());
        assertThat(f.jdbc.queryForObject("SELECT SUM(amount_settled) FROM payment_obligations", Long.class)).isEqualTo(2000000);
        assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_COMPLETED'", Long.class)).isEqualTo(2);
        steps.receive(balance, 1400000, 61002); // Provider replay must not allocate or notify again.
        assertThat(f.count("payment_receipts")).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_COMPLETED'", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> steps.bookings().cancelBooking(1L, f.actor(4), "cannot erase debt/history")).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest @ValueSource(strings={"cancel", "expire"})
    void endedHoldPreservesPublishedBeforeAndLateBankMoneyCreatesOnlyRefund(String action) throws Exception {
        var advance = steps.agree();
        var evidence = f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE phase='BEFORE'");
        if (action.equals("cancel")) steps.bookings().cancelBooking(1L, f.actor(4), "cancel before payment");
        else { f.clock.at.set(advance.expiresAt()); f.app.getBean(ReceiptProcessor.class).expire(advance.id()); }
        assertThat(steps.state()).isEqualTo(action.equals("cancel") ? "CANCELLED" : "EXPIRED");
        assertThat(f.count("refunds")).isZero();
        steps.receive(advance, 600000, 62001);
        assertThat(f.jdbc.queryForMap("SELECT amount,status FROM refunds")).containsEntry("amount",600000L).containsEntry("status","WAITING_RECIPIENT");
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts",Long.class)).isZero();
        assertThat(f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE phase='BEFORE'")).isEqualTo(evidence);
        assertThat(f.jdbc.queryForObject("SELECT vehicle_received_at FROM bookings WHERE id=1",LocalDateTime.class)).isNull();
        steps.receive(advance, 600000, 62001);
        assertThat(f.count("refunds")).isEqualTo(1);
    }

    @Test void completionNoticeFailureRollsBackFinancialAndDomainStateAcrossWholeLifecycleThenRetries() throws Exception {
        steps.paidAndHandedOver(); steps.after(); steps.returnDisputed();
        var intent = steps.balance();
        var evidence = f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE phase='AFTER'");
        f.jdbc.execute("CREATE TRIGGER gate_fail_notice BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='BOOKING_COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='gate notification failure'; END IF; END");
        long inbox;
        try { inbox = steps.receive(intent,1400000,63001); }
        finally { f.jdbc.execute("DROP TRIGGER gate_fail_notice"); }
        assertThat(steps.state()).isEqualTo("CONFIRMED");
        assertThat(f.count("payment_receipts")).isEqualTo(1); // Only the earlier advance remains.
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE purpose='BALANCE'",Long.class)).isZero();
        assertThat(f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE phase='AFTER'")).isEqualTo(evidence);
        f.clock.at.set(f.clock.instant().plusSeconds(3600));
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(steps.state()).isEqualTo("COMPLETED");
        assertThat(f.count("payment_receipts")).isEqualTo(2);
    }

    static final class Steps {
        final PaymentFlowFixture f;
        Steps(PaymentFlowFixture f) { this.f=f; }
        static PaymentFlowFixture boot(Path root) throws Exception {
            System.setProperty("carrent.inspection.storage-root",root.toString());
            System.setProperty("carrent.rental.advance.enabled","true");
            System.setProperty("carrent.rental.balance.enabled","true");
            try { return new PaymentFlowFixture(false,false); }
            finally {
                System.clearProperty("carrent.inspection.storage-root");
                System.clearProperty("carrent.rental.advance.enabled");
                System.clearProperty("carrent.rental.balance.enabled");
            }
        }
        void reset() {
            for (String table : List.of("inspection_events","inspection_images","booking_inspections","notifications")) f.jdbc.update("DELETE FROM "+table);
            f.reset(); f.jdbc.update("DELETE FROM payment_obligations");
            f.jdbc.update("UPDATE users SET role=CASE WHEN id=2 THEN 'OWNER' ELSE 'CUSTOMER' END WHERE id IN(2,4)");
            f.jdbc.update("UPDATE bookings SET status='PENDING',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at=NULL,before_accepted_at=NULL,hold_expires_at=NULL,advance_amount=NULL,balance_amount=NULL,advance_paid_at=NULL,cancelled_at=NULL,cancellation_fee=NULL,cancellation_reason=NULL,vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL,after_reminder_sent_at=NULL,balance_overdue_notified_at=NULL,start_date='2026-10-30',end_date='2026-11-01',price_per_day_snapshot=1000000,total_amount=2000000 WHERE id=1");
        }
        BookingService bookings() { return f.app.getBean(BookingService.class); }
        InspectionService inspections() { return f.app.getBean(InspectionService.class); }
        String state() { return f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class); }
        void report(InspectionPhase phase,long author) throws Exception {
            var form=new InspectionForm(); form.setConditionNote("Gate condition");form.setVisibleDamage("Immutable scratch");form.setOdometerKm(phase==InspectionPhase.BEFORE?12500:12700);form.setEnergyPercent(70);form.setAccessoriesNote("Two keys");
            inspections().saveDraft(1L,phase,form,f.actor(author));
            var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",bytes);
            var photo=new MockMultipartFile("images","gate.png","image/png",bytes.toByteArray());
            inspections().addImages(1L,phase,List.of(photo,photo,photo,photo),f.actor(author));
        }
        PaymentIntentView agree() throws Exception {
            bookings().confirmBooking(1L,f.actor(2));report(InspectionPhase.BEFORE,2);
            inspections().submitBefore(1L,f.actor(2));return bookings().acceptBefore(1L,f.actor(4));
        }
        void paidAndHandedOver() throws Exception { receive(agree(),600000,64001);bookings().confirmHandover(1L,true,f.actor(2)); }
        void after() throws Exception { report(InspectionPhase.AFTER,4);inspections().submitAfter(1L,f.actor(4)); }
        ReturnReceiptForm disputed() { var form=new ReturnReceiptForm();form.setDecision(ReturnReceiptForm.Decision.RECEIVED_DISPUTED);form.setNote("Owner records disputed scratch");return form; }
        void returnDisputed() { bookings().confirmReturn(1L,disputed(),f.actor(2)); }
        PaymentIntentView balance() { long id=f.jdbc.queryForObject("SELECT id FROM payment_obligations WHERE purpose='BALANCE'",Long.class);return f.payments.openAttempt(id,f.actor(4)); }
        long enqueue(PaymentIntentView view,long amount,long transaction) throws Exception {
            String time=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(f.clock.instant());
            byte[] body=new String(SePayInboxTest.body(transaction,view.code(),Long.toString(amount)),StandardCharsets.UTF_8).replace("2026-10-30 10:00:00",time).getBytes(StandardCharsets.UTF_8);
            f.app.getBean(SePayWebhookService.class).receive(101L,f.signed(body),body);
            return f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?",Long.class,Long.toString(transaction));
        }
        long receive(PaymentIntentView view,long amount,long transaction) throws Exception { long inbox=enqueue(view,amount,transaction);f.app.getBean(ReceiptProcessor.class).process(inbox);return inbox; }
    }
}
