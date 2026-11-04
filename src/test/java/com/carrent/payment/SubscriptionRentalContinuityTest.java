package com.carrent.payment;

import com.carrent.booking.*;
import com.carrent.inspection.*;
import com.carrent.subscription.*;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubscriptionRentalContinuityTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
    static final Instant HANDOVER = Instant.parse("2030-12-01T03:00:00Z");
    static final Instant RETURN = Instant.parse("2030-12-03T03:00:00Z");

    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root", root.toString());
        for (String flag : List.of("subscription", "rental.advance", "rental.balance")) {
            System.setProperty("carrent." + flag + ".enabled", "true");
        }
        try { f = new PaymentFlowFixture(false, false); }
        finally {
            System.clearProperty("carrent.inspection.storage-root");
            for (String flag : List.of("subscription", "rental.advance", "rental.balance")) {
                System.clearProperty("carrent." + flag + ".enabled");
            }
        }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        for (String table : List.of("owner_subscriptions", "subscription_orders", "inspection_events",
                "inspection_images", "booking_inspections", "notifications")) {
            f.jdbc.update("DELETE FROM " + table);
        }
        f.reset();
        f.jdbc.update("UPDATE cars SET listing_status='ACTIVE' WHERE id=1");
        f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE bookings SET vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL,balance_overdue_notified_at=NULL");
        f.jdbc.update("UPDATE bookings SET status='PENDING',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at=NULL,before_accepted_at=NULL,hold_expires_at=NULL,advance_amount=NULL,balance_amount=NULL,advance_paid_at=NULL,cancelled_at=NULL,cancellation_fee=NULL,start_date='2030-12-01',end_date='2030-12-03',price_per_day_snapshot=1000000,total_amount=2000000 WHERE id=1");
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "expired", "downgraded"})
    void existingRentalCompletesWithEvidenceAndDebtIntactWithoutActiveOwnerPlan(String planState) throws Exception {
        if (!planState.equals("missing")) {
            var plan = planState.equals("downgraded") ? SubscriptionPlan.BUSINESS : SubscriptionPlan.SMALL;
            var order = f.app.getBean(SubscriptionService.class).createOrder(plan, f.actor(2));
            f.app.getBean(SubscriptionService.class).cancelOrder(order.orderId(), f.actor(2));
            var now = LocalDateTime.ofInstant(f.clock.instant(), ZoneOffset.UTC);
            f.jdbc.update("INSERT INTO owner_subscriptions(owner_id,plan_code,quota,last_applied_order_id,valid_from,valid_until,created_at,updated_at) VALUES(2,?,?,?,?,?,?,?)",
                    plan.name(), plan.getQuota(), order.orderId(), now.minusDays(30), now.minusSeconds(1), now.minusDays(30), now);
        }
        var bookings = f.app.getBean(BookingService.class);
        var inspections = f.app.getBean(InspectionService.class);
        f.mvc.perform(get("/cars/1")).andExpect(status().isNotFound());
        bookings.confirmBooking(1L, f.actor(2));
        submitReport(inspections, InspectionPhase.BEFORE, 2L, 12345, 80);
        var advance = bookings.acceptBefore(1L, f.actor(4));
        receive(advance, 600000, 994001);
        assertThat(bookings.getBookingForView(1L, f.actor(4)).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        if (planState.equals("downgraded")) {
            var order = f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL, f.actor(2));
            receive(order.intent(), 5000, 994003, true);
            assertThat(f.jdbc.queryForObject("SELECT listing_status FROM cars WHERE id=1", String.class)).isEqualTo("INACTIVE");
        }

        f.clock.at.set(HANDOVER);
        bookings.confirmHandover(1L, true, f.actor(2));
        f.clock.at.set(RETURN);
        submitReport(inspections, InspectionPhase.AFTER, 4L, 12545, 60);
        var returned = new ReturnReceiptForm();
        returned.setDecision(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED);
        bookings.confirmReturn(1L, returned, f.actor(2));
        long balanceId = f.jdbc.queryForObject("SELECT id FROM payment_obligations WHERE business_key='BOOKING:1:BALANCE'", Long.class);
        assertThat(f.jdbc.queryForMap("SELECT amount_due,amount_settled,status FROM payment_obligations WHERE id=?", balanceId))
                .containsEntry("amount_due", 1400000L).containsEntry("amount_settled", 0L).containsEntry("status", "OPEN");
        var reports = f.jdbc.queryForList("SELECT * FROM booking_inspections ORDER BY id");
        var images = f.jdbc.queryForList("SELECT * FROM inspection_images ORDER BY id");
        var events = f.jdbc.queryForList("SELECT * FROM inspection_events ORDER BY id");

        f.clock.at.set(RETURN.plusSeconds(3601));
        f.app.getBean(BalanceDeadlineService.class).remind(1L);
        var balance = f.payments.openAttempt(balanceId, f.actor(4));
        receive(balance, 1400000, 994002);
        var completed = bookings.getBookingForView(1L, f.actor(4));
        assertThat(completed.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(completed.getVehicleReceivedAt()).isEqualTo(HANDOVER);
        assertThat(completed.getVehicleReturnedAt()).isEqualTo(RETURN);
        assertThat(completed.getBalanceOverdueNotifiedAt()).isEqualTo(RETURN.plusSeconds(3601));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE subject_type='BOOKING' AND subject_id=1 AND status='SETTLED'", Long.class)).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT SUM(r.allocated_amount) FROM payment_receipts r JOIN payment_intents i ON i.id=r.intent_id JOIN payment_obligations o ON o.id=i.obligation_id WHERE o.subject_type='BOOKING'", Long.class)).isEqualTo(2000000);
        assertThat(f.count("payment_receipts")).isEqualTo(planState.equals("downgraded") ? 3 : 2);
        assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForList("SELECT * FROM booking_inspections ORDER BY id")).isEqualTo(reports);
        assertThat(f.jdbc.queryForList("SELECT * FROM inspection_images ORDER BY id")).isEqualTo(images);
        assertThat(f.jdbc.queryForList("SELECT * FROM inspection_events ORDER BY id")).isEqualTo(events);
        assertThat(f.jdbc.queryForObject("SELECT listing_status FROM cars WHERE id=1", String.class)).isEqualTo(planState.equals("downgraded") ? "INACTIVE" : "ACTIVE");
        f.mvc.perform(get("/bookings/1").with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(model().attribute("carPublic", false));
        f.mvc.perform(get("/cars/1")).andExpect(status().isNotFound());
    }

    private void submitReport(InspectionService inspections, InspectionPhase phase, long actor, int km, int energy) throws Exception {
        var form = new InspectionForm();
        form.setConditionNote("Xe sạch"); form.setVisibleDamage("Không phát hiện");
        form.setOdometerKm(km); form.setEnergyPercent(energy); form.setAccessoriesNote("Chìa khóa");
        inspections.saveDraft(1L, phase, form, f.actor(actor));
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        var image = new MockMultipartFile("images", "evidence.png", "image/png", out.toByteArray());
        inspections.addImages(1L, phase, List.of(image, image, image, image), f.actor(actor));
        if (phase == InspectionPhase.BEFORE) inspections.submitBefore(1L, f.actor(actor));
        else inspections.submitAfter(1L, f.actor(actor));
    }

    private void receive(PaymentIntentView intent, long amount, long transaction) throws Exception {
        receive(intent, amount, transaction, false);
    }
    private void receive(PaymentIntentView intent, long amount, long transaction, boolean admin) throws Exception {
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(f.clock.instant());
        byte[] body = new String(SePayInboxTest.body(transaction, intent.code(), Long.toString(amount)), StandardCharsets.UTF_8)
                .replace("2026-10-30 10:00:00", date).replace("000123456", admin ? "000999999" : "000123456").getBytes(StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(admin ? 102L : 101L, f.signed(body, admin ? "another-fixture-secret" : PaymentFlowFixture.SECRET), body);
        long inbox = f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?", Long.class, Long.toString(transaction));
        f.app.getBean(ReceiptProcessor.class).process(inbox);
    }
}
