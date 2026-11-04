package com.carrent.payment;

import com.carrent.inspection.InspectionForm;
import com.carrent.inspection.InspectionPhase;
import com.carrent.inspection.InspectionService;
import com.carrent.subscription.SubscriptionPlan;
import com.carrent.subscription.SubscriptionService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentAccessAuditTest {
    static PaymentFlowFixture f;
    static PayoutWorkerTest.FakePayouts payouts;
    @TempDir static Path images;
    static final long[] ACTORS = {1, 2, 3, 4, 5, 0};
    final AtomicInteger verifications = new AtomicInteger();

    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        System.setProperty("carrent.inspection.storage-root", images.toString());
        try { f = new PaymentFlowFixture(false, true, PayoutWorkerTest.Configuration.class, AuditHandlers.class); }
        finally {
            System.clearProperty("carrent.subscription.enabled");
            System.clearProperty("carrent.inspection.storage-root");
        }
        payouts = f.app.getBean(PayoutWorkerTest.FakePayouts.class);
        assertThat(f.jdbc.queryForList("SELECT role FROM users WHERE id BETWEEN 1 AND 5 ORDER BY id", String.class))
                .containsExactly("ADMIN", "OWNER", "OWNER", "CUSTOMER", "CUSTOMER");
        assertThat(f.jdbc.queryForMap("SELECT b.customer_id,c.owner_id FROM bookings b JOIN cars c ON c.id=b.car_id WHERE b.id=1"))
                .containsEntry("customer_id", 4L).containsEntry("owner_id", 2L);
    }

    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }

    @BeforeEach void reset() throws Exception {
        for (String table : List.of("inspection_events", "inspection_images", "booking_inspections", "owner_subscriptions", "subscription_orders"))
            f.jdbc.update("DELETE FROM " + table);
        try (var files = Files.list(images)) { for (Path image : files.toList()) Files.delete(image); }
        f.reset();
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY',inspection_required=0 WHERE id=1");
        payouts.reset();
        f.app.getBean(PaymentFlowFixture.FixtureState.class).verifier = (channel, bank, account) -> {
            verifications.incrementAndGet();
            return new com.carrent.payment.provider.RefundDestinationVerifier.Destination(bank, account, "NGUYEN TEST");
        };
    }

    @AfterEach void noPayoutHttp() {
        assertThat(payouts.creates.get() + payouts.gets.get() + payouts.finds.get()).isZero();
    }

    @Test void everyMappedUserPostRejectsMissingAndInvalidCsrfBeforeAnyMutation() throws Exception {
        var mapping = f.app.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        var routes = new java.util.TreeSet<String>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("com.carrent")
                    && (info.getMethodsCondition().getMethods().isEmpty()
                    || info.getMethodsCondition().getMethods().contains(RequestMethod.POST)))
                routes.addAll(info.getPatternValues());
        });
        assertThat(routes).contains("/webhooks/sepay/{channelId}", "/owner/subscriptions/orders",
                "/bookings/{bookingId}/inspections/{phase}/submit");
        routes.remove("/webhooks/sepay/{channelId}");
        routes.addAll(List.of("/login", "/logout")); // Security filters own these POST routes.
        for (String route : routes) {
            String path = route.replace("{phase}", "BEFORE").replaceAll("\\{[^}]+}", "1");
            long actor = path.startsWith("/admin/") ? 1 : path.startsWith("/owner/") ? 2 : 4;
            unchanged(post(path), actor, 403);
            unchanged(post(path).with(csrf().useInvalidToken()), actor, 403);
        }
        assertThat(routes.size()).isGreaterThan(35);
        System.out.println("P5.8 CSRF inventory (missing + invalid tokens): " + routes);
    }

    @Test void payerOwnershipControlsCheckoutStatusResultCreateAndCancelAcrossAllSixIdentities() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        for (long actor : ACTORS) {
            int read = actor == 0 ? 302 : actor == 4 ? 200 : 403;
            for (String suffix : List.of("", "/status", "/result"))
                unchanged(get("/payment/intents/" + intent.id() + suffix), actor, read);
            unchanged(post("/payment/obligations/201/attempts").with(csrf()), actor,
                    actor == 0 || actor == 4 ? 302 : 403);
            if (actor != 4) unchanged(post("/payment/intents/" + intent.id() + "/cancel").with(csrf()), actor,
                    actor == 0 ? 302 : 403);
        }
        requestAs(post("/payment/intents/" + intent.id() + "/cancel").with(csrf()), 4, 302);
        assertThat(f.payments.status(intent.id(), f.actor(4)).status()).isEqualTo("CANCELLED");
        assertThat(verifications.get()).isZero();
    }

    @Test void recipientAndCollectorStatusPermissionsDifferFromRecipientEditingPermissions() throws Exception {
        long id = refund();
        String url = "/payment/refunds/" + id;
        for (long actor : ACTORS) {
            unchanged(get(url), actor, actor == 0 ? 302 : actor == 4 ? 200 : 403);
            unchanged(get(url + "/status"), actor, actor == 0 ? 302 : actor == 1 || actor == 2 || actor == 4 ? 200 : 403);
            if (actor != 4) for (String suffix : List.of("/recipient/preview", "/recipient"))
                unchanged(recipient(url + suffix), actor, actor == 0 ? 302 : 403);
        }
        assertThat(verifications.get()).isZero();
        var before = snapshot();
        requestAs(recipient(url + "/recipient/preview"), 4, 200);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(verifications.get()).isOne();
        requestAs(recipient(url + "/recipient").param("amount", "1").param("payoutChannelId", "104")
                .param("recipientUserId", "1").param("status", "SUCCEEDED")
                .param("readyAt", "2000-01-01T00:00:00Z").param("dueAt", "2099-01-01T00:00:00Z"), 4, 302);
        assertThat(verifications.get()).isEqualTo(2);
        assertThat(f.jdbc.queryForMap("SELECT amount,payout_channel_id,recipient_user_id,status,ready_at,due_at FROM refunds WHERE id=?", id))
                .containsEntry("amount", 540000L).containsEntry("payout_channel_id", 103L)
                .containsEntry("recipient_user_id", 4L).containsEntry("status", "READY")
                .containsEntry("ready_at", java.time.LocalDateTime.parse("2026-10-30T03:00:00"))
                .containsEntry("due_at", java.time.LocalDateTime.parse("2026-10-30T04:00:00"));
        assertThat(f.count("payout_attempts")).isZero();
    }

    @Test void subscriptionDetailAndCancelRequireItsOwnerWhileCatalogRequiresOwnerRole() throws Exception {
        var order = f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL, f.actor(2));
        String url = "/owner/subscriptions/orders/" + order.orderId();
        for (long actor : ACTORS) {
            unchanged(get("/owner/subscriptions"), actor, actor == 0 ? 302 : actor == 2 || actor == 3 ? 200 : 403);
            unchanged(get(url), actor, actor == 0 ? 302 : actor == 2 ? 200 : 403);
            if (actor != 2) unchanged(post(url + "/cancel").with(csrf()), actor, actor == 0 ? 302 : 403);
            // Other Owner is authorized to request their own order, but is outside this fixture's pilot.
            unchanged(post("/owner/subscriptions/orders").with(csrf()).param("plan", "SMALL"), actor,
                    actor == 0 || actor == 2 || actor == 3 ? 302 : 403);
        }
        requestAs(post(url + "/cancel").with(csrf()), 2, 302);
        assertThat(f.payments.status(order.intent().id(), f.actor(2)).status()).isEqualTo("CANCELLED");
    }

    @Test void draftAndSubmittedPrivateImageMatrixUsesReportMembershipAndNeverCreatesPublicFiles() throws Exception {
        f.jdbc.update("UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1 WHERE id=1");
        var inspections = f.app.getBean(InspectionService.class);
        inspections.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(2));
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        var photo = new MockMultipartFile("images", "audit.png", "image/png", out.toByteArray());
        inspections.addImages(1L, InspectionPhase.BEFORE, List.of(photo), f.actor(2));
        long id = inspections.getView(1L, InspectionPhase.BEFORE, f.actor(2)).images().getFirst().id();
        String url = "/bookings/1/inspections/BEFORE/images/" + id;
        for (long actor : ACTORS) {
            unchanged(get(url), actor, actor == 0 ? 302 : actor == 2 ? 200 : 404);
            if (actor != 2) unchanged(post(url + "/delete").with(csrf()), actor, actor == 0 ? 302 : 404);
        }
        f.jdbc.update("UPDATE booking_inspections SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP(6)");
        for (long actor : ACTORS) {
            unchanged(get(url), actor, actor == 0 ? 302 : actor == 1 || actor == 2 || actor == 4 ? 200 : 404);
            unchanged(post(url + "/delete").with(csrf()), actor, actor == 0 || actor == 2 ? 302 : 404);
        }
        f.mvc.perform(get(url).with(user(f.actor(4)))).andExpect(content().bytes(photo.getBytes()))
                .andExpect(header().string("Cache-Control", "no-store"));
        String key = f.jdbc.queryForObject("SELECT storage_key FROM inspection_images WHERE id=?", String.class, id);
        unchanged(get("/uploads/" + key), 0, 404);
        unchanged(get("/private-uploads/inspections/" + key), 2, 404);
        assertThat(f.count("inspection_images")).isOne();
        try (var files = Files.list(images)) { assertThat(files.count()).isOne(); }
    }

    @Test void adminReadsAndMutationsRequireAdmin() throws Exception {
        for (long actor : ACTORS) {
            for (String path : List.of("/admin/dashboard", "/admin/users", "/admin/users/4", "/admin/cars",
                    "/admin/cars/1", "/admin/bookings", "/admin/bookings/1", "/admin/finance"))
                unchanged(get(path), actor, actor == 0 ? 302 : actor == 1 ? 200 : 403);
            for (String path : List.of("/admin/cars/999999/approve", "/admin/cars/999999/reject",
                    "/admin/cars/999999/activate", "/admin/cars/999999/deactivate", "/admin/users/999999/status"))
                unchanged(post(path).with(csrf()).param("status", "BANNED"), actor,
                        actor == 0 ? 302 : actor == 1 ? 404 : 403);
        }
        assertThat(verifications.get()).isZero();
    }

    @Test void webhookCsrfExceptionRequiresExactPostPathAndHmacForEveryIdentity() throws Exception {
        byte[] body = SePayInboxTest.body(5858001, null, "600000");
        for (long actor : ACTORS) {
            unchanged(post("/webhooks/sepay/101").contentType("application/json").content(body), actor, 401);
            unchanged(post("/webhooks/sepay/101").contentType("application/json")
                    .headers(f.signed(body, "incorrect-secret")).content(body), actor, 401);
            for (String path : List.of("/webhooks/sepay", "/webhooks/sepay/", "/webhooks/sepay/101/",
                    "/webhooks/sepay/101/extra", "/webhooks/other/101"))
                unchanged(post(path).contentType("application/json").headers(f.signed(body)).content(body), actor, 403);
            for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE))
                unchanged(request(method, "/webhooks/sepay/101").contentType("application/json")
                        .headers(f.signed(body)).content(body), actor, 403);
            if (actor != 0) for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE))
                unchanged(request(method, "/webhooks/sepay/101").with(csrf()).contentType("application/json")
                        .headers(f.signed(body)).content(body), actor, 405);
            unchanged(get("/webhooks/sepay/101"), actor, actor == 0 ? 302 : 405);
        }
        f.mvc.perform(get("/webhooks/sepay/101").with(user(f.actor(1))))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Phương thức không được hỗ trợ")));
        for (long actor : ACTORS) {
            byte[] signedBody = SePayInboxTest.body(5858010 + actor, null, "600000");
            requestAs(post("/webhooks/sepay/101").contentType("application/json")
                    .headers(f.signed(signedBody)).content(signedBody), actor, 200);
        }
        assertThat(f.count("webhook_inbox")).isEqualTo(6);
        assertThat(f.count("payment_receipts")).isZero();
        assertThat(verifications.get()).isZero();
    }

    @Test void httpCheckoutIgnoresAmountStatusChannelAndTimestampTampering() throws Exception {
        requestAs(post("/payment/obligations/201/attempts").with(csrf())
                .param("amount", "1").param("amountDue", "1").param("channelId", "102")
                .param("status", "RECEIVED").param("payerUserId", "1").param("collectorUserId", "1")
                .param("createdAt", "2000-01-01T00:00:00Z").param("expiresAt", "2099-01-01T00:00:00Z"), 4, 302);
        var intent = f.jdbc.queryForMap("SELECT amount,channel_id,status,created_at,expires_at FROM payment_intents");
        assertThat(intent).containsEntry("amount", 600000L).containsEntry("channel_id", 101L)
                .containsEntry("status", "ACTIVE")
                .containsEntry("created_at", java.time.LocalDateTime.parse("2026-10-30T03:00:00"))
                .containsEntry("expires_at", java.time.LocalDateTime.parse("2026-10-30T03:15:00"));
        long id = f.jdbc.queryForObject("SELECT id FROM payment_intents", Long.class);
        unchanged(get("/payment/intents/" + id + "/status").param("status", "RECEIVED").param("amount", "1")
                .param("expiresAt", "2099-01-01T00:00:00Z"), 4, 200);
        f.mvc.perform(get("/payment/intents/" + id + "/status").with(user(f.actor(4))))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.amount").value(600000));
    }

    @Test void readPagesCompleteWhileTheirCarCommandLaneIsOccupied() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        var order = f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL, f.actor(2));
        f.jdbc.update("UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1 WHERE id=1");
        var before = snapshot();
        var occupied = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var commands = f.app.getBean(com.carrent.concurrency.CarCommandExecutor.class);
        var blocker = java.util.concurrent.CompletableFuture.runAsync(() -> commands.execute(1L, () -> {
            occupied.countDown();
            try { assertThat(release.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { throw new AssertionError(ex); }
            return null;
        }));
        assertThat(occupied.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    for (String suffix : List.of("", "/status", "/result"))
                        requestAs(get("/payment/intents/" + intent.id() + suffix), 4, 200);
                    requestAs(get("/admin/bookings/1"), 1, 200);
                    requestAs(get("/owner/dashboard"), 2, 200);
                    requestAs(get("/owner/subscriptions/orders/" + order.orderId()), 2, 200);
                    requestAs(get("/bookings/1/inspections/BEFORE/draft"), 2, 200);
                } catch (Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
            }).get(8, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(blocker).isNotDone();
            assertThat(snapshot()).isEqualTo(before);
            assertThat(verifications.get()).isZero();
        } finally {
            release.countDown();
            blocker.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private long refund() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(5858999, intent.code(), "600000"));
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        return f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt, receipt, 540000, RefundReason.CUSTOMER_CANCEL);
    }

    private MockHttpServletRequestBuilder recipient(String path) {
        return post(path).with(csrf()).param("bankBin", "970422").param("accountNumber", "000123456")
                .param("confirmedAccountName", "NGUYEN TEST").param("confirmed", "true");
    }

    private void unchanged(MockHttpServletRequestBuilder request, long actor, int status) throws Exception {
        var before = snapshot();
        int calls = verifications.get();
        var executor = f.app.getBean("paymentDomainExecutor", ThreadPoolTaskExecutor.class).getThreadPoolExecutor();
        long submitted = executor.getTaskCount();
        requestAs(request, actor, status);
        assertThat(snapshot()).as("DB after actor %s request", actor).isEqualTo(before);
        assertThat(verifications.get()).as("recipient provider calls").isEqualTo(calls);
        assertThat(executor.getTaskCount()).as("domain queue submissions").isEqualTo(submitted);
    }

    private void requestAs(MockHttpServletRequestBuilder request, long actor, int expected) throws Exception {
        if (actor != 0) request.with(user(f.actor(actor)));
        var response = f.mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("actor %s HTTP %s", actor, expected).isEqualTo(expected);
        if (actor == 0 && expected == 302) assertThat(response.getRedirectedUrl()).endsWith("/login");
    }

    private Map<String, String> snapshot() throws Exception {
        var result = new TreeMap<String, String>();
        for (String table : f.jdbc.queryForList("SHOW TABLES", String.class)) {
            var rows = new ArrayList<String>();
            for (var row : f.jdbc.queryForList("SELECT * FROM `" + table + "`")) {
                var values = new TreeMap<String, String>();
                row.forEach((name, value) -> values.put(name, value instanceof byte[] bytes
                        ? HexFormat.of().formatHex(bytes) : String.valueOf(value)));
                rows.add(values.toString());
            }
            rows.sort(String::compareTo);
            result.put(table, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rows.toString().getBytes(StandardCharsets.UTF_8))));
        }
        return result;
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class AuditHandlers {
        @org.springframework.context.annotation.Bean
        PaymentDomainHandler auditAdvance(jakarta.persistence.EntityManager em, org.springframework.jdbc.core.JdbcTemplate jdbc,
                PaymentFlowFixture.FixtureState state, RefundService refunds) {
            return new PaymentFlowFixture.Configuration().fixtureAdvance(em, jdbc, state, refunds);
        }
    }
}
