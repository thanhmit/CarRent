package com.carrent.payment;

import com.carrent.booking.BookingService;
import com.carrent.booking.BookingStatus;
import com.carrent.inspection.*;
import com.carrent.payment.provider.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real domain handlers and HTTP actions; bank transfers are signed offline inbox fixtures. */
class FinalRentalJourneyTest {
    static PaymentFlowFixture f;
    @TempDir static Path evidence;
    static final Instant START = Instant.parse("2030-12-01T03:00:00Z");

    @BeforeEach void start() throws Exception {
        configure(Map.of("carrent.subscription.enabled", "true", "carrent.rental.advance.enabled", "true",
                "carrent.rental.balance.enabled", "true", "carrent.rental.workflow.enabled", "true",
                "carrent.inspection.storage-root", evidence.toString()), false);
    }
    @AfterEach void stop() throws Exception { if (f != null) f.close(); }

    @Test void ownerSubscriptionToCompletedRentalRetainsDisputedPrivateEvidence() throws Exception {
        f.jdbc.update("DELETE FROM notifications");
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.reset();
        f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE users SET role='OWNER' WHERE id=2");
        f.jdbc.update("UPDATE users SET role='CUSTOMER' WHERE id=4");
        f.clock.at.set(START);

        String orderUrl = submit(post("/owner/subscriptions/orders").param("plan", "STANDARD"), 2);
        long order = lastId(orderUrl);
        var subscription = f.app.getBean(com.carrent.subscription.SubscriptionService.class).detail(order, f.actor(2));
        assertSubject("SUBSCRIPTION:" + order, "SUBSCRIPTION_ORDER", order, 2, 1, 10000);
        receive(subscription.intent(), 102, 10000, 995001);
        assertThat(f.jdbc.queryForObject("SELECT status FROM subscription_orders WHERE id=?", String.class, order)).isEqualTo("APPLIED");
        assertThat(f.jdbc.queryForObject("SELECT quota FROM owner_subscriptions WHERE owner_id=2", Integer.class)).isEqualTo(8);

        submit(post("/owner/cars/new").param("brand", "Journey").param("model", "P59")
                .param("manufactureYear", "2025").param("seatCount", "5")
                .param("transmission", "AUTOMATIC").param("fuelType", "GASOLINE")
                .param("categoryId", "1").param("pricePerDay", "1000000")
                .param("province", "Ho Chi Minh").param("addressDetail", "Offline journey address"), 2);
        long car = f.jdbc.queryForObject("SELECT id FROM cars WHERE brand='Journey' AND model='P59'", Long.class);
        assertThat(f.jdbc.queryForObject("SELECT approval_status FROM cars WHERE id=?", String.class, car)).isEqualTo("PENDING");
        submit(post("/admin/cars/" + car + "/approve"), 1);
        f.mvc.perform(get("/cars/" + car)).andExpect(status().isOk());

        long booking = lastId(submit(post("/bookings").param("carId", Long.toString(car))
                .param("startDate", "2030-12-01").param("endDate", "2030-12-03")
                .param("workflowVersion", "LEGACY").param("inspectionRequired", "false")
                .param("status", "COMPLETED").param("totalAmount", "1"), 4));
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getWorkflowVersion()).isEqualTo("VIETQR_V1");
        assertThat(bookings().getBookingForView(booking, f.actor(4)).isInspectionRequired()).isTrue();
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getTotalAmount()).isEqualTo(2000000);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE subject_type='BOOKING'", Long.class)).isZero();
        submit(post("/owner/bookings/" + booking + "/confirm"), 2);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getStatus()).isEqualTo(BookingStatus.PENDING);

        publishReport(booking, InspectionPhase.BEFORE, 2, "Clean before handover", "No visible damage", 12000);
        long advanceId = lastId(submit(post("/bookings/" + booking + "/accept-before"), 4));
        assertSubject("BOOKING:" + booking + ":ADVANCE", "BOOKING", booking, 4, 2, 600000);
        assertThat(inspections().getView(booking, InspectionPhase.BEFORE, f.actor(4)).status()).isEqualTo(InspectionStatus.ACCEPTED);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getVehicleReceivedAt()).isNull();
        receive(f.payments.status(advanceId, f.actor(4)), 101, 600000, 995002);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getVehicleReceivedAt()).isNull();
        submit(post("/owner/bookings/" + booking + "/handover").param("contractSigned", "true"), 2);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getContractConfirmedAt()).isEqualTo(START);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getVehicleReceivedAt()).isEqualTo(START);

        f.clock.at.set(START.plusSeconds(2 * 86400L));
        publishReport(booking, InspectionPhase.AFTER, 4, "Returned after rental", "Small scratch on door", 12100);
        var original = inspections().getView(booking, InspectionPhase.AFTER, f.actor(4));
        assertThat(original.status()).isEqualTo(InspectionStatus.SUBMITTED);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getVehicleReturnedAt()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE purpose='BALANCE'", Long.class)).isZero();
        submit(post("/owner/bookings/" + booking + "/return").param("decision", "RECEIVED_DISPUTED")
                .param("note", "Owner disputes the door scratch; retained for review"), 2);
        var disputed = inspections().getView(booking, InspectionPhase.AFTER, f.actor(4));
        assertThat(disputed.status()).isEqualTo(InspectionStatus.DISPUTED);
        assertThat(disputed.conditionNote()).isEqualTo(original.conditionNote());
        assertThat(disputed.visibleDamage()).isEqualTo(original.visibleDamage());
        assertThat(disputed.submittedAt()).isEqualTo(original.submittedAt());
        assertThat(disputed.images()).isEqualTo(original.images());
        assertThat(disputed.events()).anySatisfy(event -> {
            assertThat(event.actorId()).isEqualTo(2L);
            assertThat(event.action()).isEqualTo(InspectionEventAction.DISPUTED);
            assertThat(event.note()).contains("door scratch");
        });
        assertSubject("BOOKING:" + booking + ":BALANCE", "BOOKING", booking, 4, 2, 1400000);
        long obligation = f.jdbc.queryForObject("SELECT id FROM payment_obligations WHERE business_key=?", Long.class, "BOOKING:" + booking + ":BALANCE");
        long balanceId = lastId(submit(post("/payment/obligations/" + obligation + "/attempts"), 4));
        assertThat(f.payments.status(balanceId, f.actor(4)).rental().dueAt()).isEqualTo(f.clock.instant().plusSeconds(3600));
        receive(f.payments.status(balanceId, f.actor(4)), 101, 1400000, 995003);
        assertThat(bookings().getBookingForView(booking, f.actor(4)).getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(inspections().getView(booking, InspectionPhase.AFTER, f.actor(2)).status()).isEqualTo(InspectionStatus.DISPUTED);
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts", Long.class)).isEqualTo(2010000);
        assertThat(f.count("payment_receipts")).isEqualTo(3);
        assertThat(f.count("refunds")).isZero();
        assertThat(f.count("payout_attempts")).isZero();

        for (long actor : new long[]{1, 2, 4}) {
            f.mvc.perform(get("/bookings/" + booking + "/inspections/AFTER/images/" + original.images().getFirst().id())
                    .with(user(f.actor(actor)))).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().contentType("image/png"));
        }
        f.mvc.perform(get("/bookings/" + booking + "/inspections/AFTER/images/" + original.images().getFirst().id())
                .with(user(f.actor(5)))).andExpect(status().isNotFound());
    }

    String submit(AbstractMockHttpServletRequestBuilder<?> request, long actor) throws Exception {
        return f.mvc.perform(request.with(user(f.actor(actor))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(result -> assertThat(result.getFlashMap().containsKey("errorMessage")).isFalse())
                .andReturn().getResponse().getRedirectedUrl();
    }

    @ParameterizedTest @CsvSource({"true,status", "true,role", "false,status", "false,role"})
    void customerRevokedWhileWaitingForCarCannotCreateBooking(boolean workflow, String change) throws Exception {
        if (!workflow) configure(Map.of("carrent.rental.workflow.enabled", "false"), true);
        f.jdbc.update("DELETE FROM notifications");
        f.reset();
        f.jdbc.update("DELETE FROM payment_obligations");
        f.historicalSmallSubscription(2);
        var customer = f.actor(4);
        var form = new com.carrent.booking.BookingCreateForm();
        form.setCarId(1L);
        form.setStartDate(java.time.LocalDate.of(2030, 12, 1));
        form.setEndDate(form.getStartDate().plusDays(2));
        long bookingsBefore = f.count("bookings"), notificationsBefore = f.count("notifications");
        var connection = new java.util.concurrent.atomic.AtomicLong();
        var started = new java.util.concurrent.CountDownLatch(1);
        var pending = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Throwable>>();
        f.tx.executeWithoutResult(status -> {
            f.em.createNativeQuery("SELECT id FROM cars WHERE id=1 FOR UPDATE").getSingleResult();
            pending.set(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    f.tx.executeWithoutResult(worker -> {
                        connection.set(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        started.countDown();
                        bookings().createBooking(form, customer);
                    });
                    return null;
                } catch (Throwable failure) { return failure; }
            }));
            try { assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { throw new AssertionError(ex); }
            org.awaitility.Awaitility.await().atMost(8, java.util.concurrent.TimeUnit.SECONDS).until(() ->
                    f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=? AND trx_state='LOCK WAIT'",
                            Long.class, connection.get()) == 1);
            f.jdbc.update("UPDATE users SET " + (change.equals("status") ? "status='BANNED'" : "role='OWNER'") + " WHERE id=4");
        });
        assertThat(pending.get().get(10, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(com.carrent.common.exception.ForbiddenException.class);
        assertThat(f.count("bookings")).isEqualTo(bookingsBefore);
        assertThat(f.count("notifications")).isEqualTo(notificationsBefore);
        assertThat(f.count("payment_intents")).isZero();
        assertThat(f.count("payment_receipts")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE subject_type='BOOKING'", Long.class)).isZero();
    }

    @Test void inspectionAdmissionIsIndependentOfPaymentPilotAndFailsClosedWhenAHandlerIsMissing() throws Exception {
        // Each restart reads real configuration; posted workflow/inspection fields are never admission controls.
        int day = 1;
        for (String mode : new String[]{"default-off", "no-pair", "invalid-pair", "nonpilot", "valid-no-subscription", "no-advance", "no-balance",
                "missing-admin", "inactive-admin", "wrong-role-admin", "inactive-owner", "wrong-role-owner"}) {
            var settings = new HashMap<>(Map.of("carrent.rental.workflow.enabled", Boolean.toString(!mode.equals("default-off")),
                    "carrent.subscription.enabled", "false", "carrent.rental.advance.enabled", Boolean.toString(!mode.equals("no-advance")),
                    "carrent.rental.balance.enabled", Boolean.toString(!mode.equals("no-balance")),
                    "fixture.payment-pilot.owner-id", mode.equals("no-pair") ? "0" : "2",
                    "fixture.payment-pilot.admin-id", mode.equals("no-pair") ? "0" : mode.equals("invalid-pair") ? "2" : mode.equals("missing-admin") ? "9999" : "1"));
            configure(settings, true);
            f.jdbc.update("DELETE FROM notifications");
            f.reset();
            f.jdbc.update("DELETE FROM payment_obligations");
            f.jdbc.update("UPDATE users SET role='ADMIN' WHERE id=1");
            f.jdbc.update("UPDATE users SET role='OWNER' WHERE id=2");
            if (mode.equals("inactive-admin")) f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=1");
            if (mode.equals("wrong-role-admin")) f.jdbc.update("UPDATE users SET role='CUSTOMER' WHERE id=1");
            if (mode.equals("inactive-owner")) f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=2");
            if (mode.equals("wrong-role-owner")) f.jdbc.update("UPDATE users SET role='CUSTOMER' WHERE id=2");
            long before = f.count("bookings");
            String date = "2031-12-" + String.format("%02d", day++);
            var response = f.mvc.perform(post("/bookings").with(user(f.actor(4))).with(csrf())
                    .param("carId", mode.equals("nonpilot") ? "6" : "1")
                    .param("startDate", date).param("endDate", java.time.LocalDate.parse(date).plusDays(1).toString())
                    .param("workflowVersion", "VIETQR_V1").param("inspectionRequired", "true")
                    .param("status", "COMPLETED").param("advancePaidAt", START.toString())).andReturn();
            if (mode.equals("no-advance") || mode.equals("no-balance") || mode.endsWith("owner")) {
                assertThat(response.getResponse().getStatus()).as(mode).isEqualTo(200);
                assertThat(response.getModelAndView().getViewName()).isEqualTo("booking/create");
                assertThat(f.count("bookings")).isEqualTo(before);
            } else {
                assertThat(response.getResponse().getStatus()).as(mode).isEqualTo(302);
                long id = lastId(response.getResponse().getRedirectedUrl());
                var booking = bookings().getBookingForView(id, f.actor(4));
                assertThat(booking.getWorkflowVersion()).as(mode).isEqualTo(mode.equals("default-off") ? "LEGACY" : "VIETQR_V1");
                assertThat(booking.isInspectionRequired()).isEqualTo(!mode.equals("default-off"));
                assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
                assertThat(booking.getAdvancePaidAt()).isNull();
                assertThat(booking.getVehicleReceivedAt()).isNull();
                assertThat(f.count("bookings")).isEqualTo(before + 1);
            }
            assertThat(f.count("payment_obligations")).isZero();
            assertThat(f.count("payment_intents")).isZero();
            assertThat(f.count("payment_receipts")).isZero();
        }
    }

    void configure(Map<String, String> settings, boolean restart) throws Exception {
        var previous = new HashMap<String, String>();
        settings.forEach((key, value) -> { previous.put(key, System.getProperty(key)); setProperty(key, value); });
        try {
            if (restart) f.restart(false);
            else f = new PaymentFlowFixture(false, false, NoExternalPayout.class);
        } finally { previous.forEach(FinalRentalJourneyTest::setProperty); }
    }
    static void setProperty(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
    static long lastId(String url) { return Long.parseLong(url.substring(url.lastIndexOf('/') + 1)); }
    BookingService bookings() { return f.app.getBean(BookingService.class); }
    InspectionService inspections() { return f.app.getBean(InspectionService.class); }

    void publishReport(long booking, InspectionPhase phase, long actor, String condition, String damage, int km) throws Exception {
        String base = "/bookings/" + booking + "/inspections/" + phase;
        submit(post(base + "/draft").param("conditionNote", condition).param("visibleDamage", damage)
                .param("odometerKm", Integer.toString(km)).param("energyPercent", "70")
                .param("accessoriesNote", "Keys and documents"), actor);
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        var upload = multipart(base + "/images");
        for (int i = 0; i < 4; i++) upload.file(new MockMultipartFile("images", "evidence-" + i + ".png", "image/png", out.toByteArray()));
        submit(upload, actor);
        var draft = inspections().getView(booking, phase, f.actor(actor));
        assertThat(draft.images()).hasSize(4);
        long unrelated = actor == 2 ? 4 : 2;
        f.mvc.perform(get(base + "/images/" + draft.images().getFirst().id()).with(user(f.actor(unrelated))))
                .andExpect(status().isNotFound());
        submit(post(base + "/submit"), actor);
        assertThat(inspections().getView(booking, phase, f.actor(actor)).status()).isEqualTo(InspectionStatus.SUBMITTED);
    }

    void assertSubject(String key, String type, long subject, long payer, long collector, long amount) {
        assertThat(f.jdbc.queryForMap("SELECT subject_type,subject_id,payer_user_id,collector_user_id,amount_due FROM payment_obligations WHERE business_key=?", key))
                .containsEntry("subject_type", type).containsEntry("subject_id", subject)
                .containsEntry("payer_user_id", payer).containsEntry("collector_user_id", collector).containsEntry("amount_due", amount);
    }

    void receive(PaymentIntentView intent, long channel, long amount, long transaction) throws Exception {
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(f.clock.instant());
        String payload = new String(SePayInboxTest.body(transaction, intent.code(), Long.toString(amount)), StandardCharsets.UTF_8)
                .replace("2026-10-30 10:00:00", date);
        if (channel == 102) payload = payload.replace("000123456", "000999999");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        f.mvc.perform(post("/webhooks/sepay/" + channel).contentType("application/json")
                .headers(f.signed(body, channel == 102 ? "another-fixture-secret" : PaymentFlowFixture.SECRET)).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        long inbox = f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?", Long.class, Long.toString(transaction));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipts WHERE intent_id=?", Long.class, intent.id())).isZero();
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox WHERE id=?", String.class, inbox)).isEqualTo("PROCESSED");
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts WHERE intent_id=?", Long.class, intent.id())).isEqualTo(amount);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoExternalPayout {
        @Bean @Primary PayOsPayoutClient noExternalPayout() {
            return new PayOsPayoutClient() {
                public PayoutResult create(Long channel, PayoutCommand command) { throw new AssertionError("Unexpected external payout"); }
                public PayoutResult get(Long channel, String id) { throw new AssertionError("Unexpected external payout lookup"); }
                public PayoutResult findByReference(Long channel, String reference) { throw new AssertionError("Unexpected external payout reconciliation"); }
            };
        }
    }
}
