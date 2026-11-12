package com.carrent.payment;

import com.carrent.subscription.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubscriptionOwnerUiTest {
    static PaymentFlowFixture f;
    static long transaction=987000;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled","true");
        try { f=new PaymentFlowFixture(false,true,PayoutWorkerTest.Configuration.class); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if(f!=null)f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.jdbc.update("DELETE FROM notifications");
        f.reset();
        f.jdbc.update("DELETE FROM car_images WHERE car_id>6");
        f.jdbc.update("DELETE FROM cars WHERE id>6");
        f.jdbc.update("UPDATE cars SET approval_status='APPROVED' WHERE id=2");
        f.app.getBean(PayoutWorkerTest.FakePayouts.class).reset();
    }
    @Test void ownerSeesMissingPlanAndActivatedPlanWithReservedPendingSlotsAndOnlySamePlanRenewal() throws Exception {
        assertThat(page("/owner/subscriptions")).contains("Chưa có gói đăng xe", "không hoàn phí sau khi kích hoạt");
        var order=create(SubscriptionPlan.SMALL);
        pay(order);
        f.jdbc.update("UPDATE cars SET approval_status='PENDING' WHERE id=2");
        String html=page("/owner/subscriptions");
        assertThat(html).contains("Gói hiện tại", "Suất đang dùng", "2 / 4", "1 xe chờ duyệt", "29/11/2026")
                .contains("Gia hạn SMALL").doesNotContain("Chọn gói STANDARD", "Chọn gói BUSINESS");
        assertThat(page("/owner/cars")).contains("Suất đang dùng", "2 / 4", "/owner/subscriptions");
        f.mvc.perform(post("/owner/subscriptions/orders").with(user(f.actor(2))).with(csrf()).param("plan","BUSINESS"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/owner/subscriptions"));
        assertThat(f.count("subscription_orders")).isOne();
    }
    @Test void fractionalClockPurchaseWebhookCarApprovalAndHideJourney() throws Exception {
        f.clock.at.set(PaymentFlowFixture.NOW.plusNanos(123456789));
        String path = f.mvc.perform(post("/owner/subscriptions/orders").with(user(f.actor(2))).with(csrf())
                        .param("plan", "SMALL"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(path).startsWith("/owner/subscriptions/orders/");
        long orderId = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        var order = f.app.getBean(SubscriptionService.class).detail(orderId, f.actor(2));
        assertThat(page("/payment/intents/" + order.intent().id())).contains("img.vietqr.io", order.intent().code());
        f.mvc.perform(post("/owner/cars/new").with(user(f.actor(2))).with(csrf())
                        .param("brand", "Journey").param("model", "Tier E2E").param("manufactureYear", "2024")
                        .param("seatCount", "5").param("transmission", "AUTOMATIC").param("fuelType", "GASOLINE")
                        .param("pricePerDay", "500000").param("categoryId", "1").param("province", "Ha Noi"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("errorMessage"));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM cars WHERE brand='Journey'", Long.class)).isZero();
        f.clock.at.set(f.clock.instant().plusSeconds(1));
        pay(order);
        assertThat(page(path)).contains("data-status=\"APPLIED\"");
        assertThat(f.app.getBean(SubscriptionService.class).overview(f.actor(2)).quota()).isEqualTo(4);
        f.mvc.perform(post("/owner/cars/new").with(user(f.actor(2))).with(csrf())
                        .param("brand", "Journey").param("model", "Tier E2E").param("manufactureYear", "2024")
                        .param("seatCount", "5").param("transmission", "AUTOMATIC").param("fuelType", "GASOLINE")
                        .param("pricePerDay", "500000").param("categoryId", "1").param("province", "Ha Noi"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/owner/cars"));
        long carId = f.jdbc.queryForObject("SELECT id FROM cars WHERE brand='Journey'", Long.class);
        f.mvc.perform(get("/cars/" + carId)).andExpect(status().isNotFound());
        f.mvc.perform(post("/admin/cars/" + carId + "/approve").with(user(f.actor(1))).with(csrf()))
                .andExpect(status().is3xxRedirection());
        f.mvc.perform(get("/cars/" + carId)).andExpect(status().isOk());
        long used = f.app.getBean(SubscriptionService.class).overview(f.actor(2)).reserved();
        f.mvc.perform(post("/owner/cars/" + carId + "/deactivate").with(user(f.actor(2))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/owner/cars"));
        f.mvc.perform(get("/cars/" + carId)).andExpect(status().isNotFound());
        assertThat(page("/cars")).doesNotContain("Tier E2E");
        assertThat(page("/owner/cars")).contains("Tier E2E");
        assertThat(f.app.getBean(SubscriptionService.class).overview(f.actor(2)).reserved()).isEqualTo(used - 1);
        assertThat(f.jdbc.queryForMap("SELECT approval_status,listing_status FROM cars WHERE id=?", carId))
                .containsEntry("approval_status", "APPROVED").containsEntry("listing_status", "INACTIVE");
    }
    @Test void expiredPlanShowsTemporarilyHiddenApprovedIntentAndAllPurchaseChoicesWithoutMutatingCars() throws Exception {
        pay(create(SubscriptionPlan.SMALL));
        f.clock.at.set(f.clock.instant().plus(Duration.ofDays(30)));
        var before=f.jdbc.queryForList("SELECT * FROM cars ORDER BY id");
        assertThat(page("/owner/subscriptions")).contains("Đã hết hạn", "Chọn gói SMALL", "Chọn gói STANDARD", "Chọn gói BUSINESS");
        assertThat(page("/owner/cars")).contains("tạm ẩn", "tự chọn bật xe");
        var overview=f.app.getBean(SubscriptionService.class).overview(f.actor(2));
        assertThat(overview.temporarilyHidden()).isEqualTo(2);
        assertThat(overview.reserved()).isEqualTo(2);
        assertThat(f.jdbc.queryForList("SELECT * FROM cars ORDER BY id")).isEqualTo(before);
    }
    @Test void appliedOrderAndPaymentResultLinkToEachDuplicateRefundWithoutLosingActivationState() throws Exception {
        var order=create(SubscriptionPlan.SMALL); pay(order); pay(order); pay(order);
        var ids=f.jdbc.queryForList("SELECT id FROM refunds ORDER BY id",Long.class);
        assertThat(ids).hasSize(2);
        String html=page("/owner/subscriptions/orders/"+order.orderId());
        assertThat(html).contains("data-status=\"APPLIED\"", "Đã kích hoạt", "Các khoản hoàn của lần thanh toán này");
        for(long id:ids) {
            assertThat(html).contains("/payment/finances/refunds/"+id);
            assertThat(page("/payment/intents/"+order.intent().id()+"/result")).contains("/payment/finances/refunds/"+id);
        }
        f.mvc.perform(get("/payment/intents/"+order.intent().id()+"/result").with(user(f.actor(3)))).andExpect(status().isForbidden());
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","late"})
    void refundRequiresRecipientOnlyWhenNeededAndUsesAdminFundsWithOverdueAlerts(String reason) throws Exception {
        var order=create(SubscriptionPlan.SMALL);
        assertThat(page("/owner/subscriptions/orders/"+order.orderId())).doesNotContain("id=\"intent-refunds\"", "name=\"accountNumber\"");
        if(reason.equals("duplicate")) pay(order);
        else f.clock.at.set(order.intent().expiresAt());
        pay(order);
        long id=f.jdbc.queryForObject("SELECT id FROM refunds",Long.class);
        assertThat(page("/owner/subscriptions/orders/"+order.orderId())).contains("/payment/refunds/"+id);
        assertThat(page("/payment/intents/"+order.intent().id()+"/result")).contains("/payment/finances/refunds/"+id);
        assertThat(f.jdbc.queryForMap("SELECT payout_channel_id,recipient_user_id,status FROM refunds"))
                .containsEntry("payout_channel_id",104L).containsEntry("recipient_user_id",2L).containsEntry("status","WAITING_RECIPIENT");
        String recipient="/payment/refunds/"+id;
        f.mvc.perform(post(recipient+"/recipient").with(user(f.actor(2))).with(csrf())
                .param("bankBin","970422").param("accountNumber","000777888").param("confirmed","true"))
                .andExpect(status().is3xxRedirection());
        var provider=f.app.getBean(PayoutWorkerTest.FakePayouts.class);
        provider.onCreate=(channel,c) -> {
            assertThat(channel).isEqualTo(104);
            return new com.carrent.payment.provider.PayoutResult("fixture-insufficient-funds",c.referenceId(),com.carrent.payment.provider.TransferOutcome.NOT_SENT,
                    c.bankBin(),c.accountNumber(),c.amount(),com.carrent.payment.provider.PayoutResult.NotSentReason.INSUFFICIENT_FUNDS);
        };
        f.app.getBean(PayoutWorker.class).dispatch(id);
        assertThat(page("/payment/finances/refunds/"+id)).contains("Chờ bên hoàn tiền bổ sung số dư");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_FUNDS' AND user_id=1",Long.class)).isOne();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_FUNDS' AND user_id<>1",Long.class)).isZero();
        f.clock.at.set(f.clock.instant().plusSeconds(3601));
        f.app.getBean(PaymentMaintenanceWork.class).notifyOverdue(id);
        f.app.getBean(PaymentMaintenanceWork.class).notifyOverdue(id);
        assertThat(page("/payment/finances/refunds/"+id)).contains("Quá thời hạn xử lý");
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("WAITING_FUNDS");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_OVERDUE' AND user_id IN (1,2)",Long.class)).isEqualTo(2);
        assertThat(provider.creates.get()).isOne();
        String list=page("/payment/finances?view=refunds");
        assertThat(list).contains("••••7888").doesNotContain("000777888","admin-payout-fixture","checksum-key","credentialReference");
    }
    @Test void otherOwnerCannotReadOrMutateOrderPaymentRefundRecipientOrStatusAndPostsRequireCsrf() throws Exception {
        var order=create(SubscriptionPlan.SMALL); pay(order); pay(order);
        long id=f.jdbc.queryForObject("SELECT id FROM refunds",Long.class);
        var foreign=f.actor(3); var owner=f.actor(2);
        for(String url:new String[]{"/owner/subscriptions/orders/"+order.orderId(),"/payment/intents/"+order.intent().id(),
                "/payment/intents/"+order.intent().id()+"/status","/payment/finances/refunds/"+id,"/payment/refunds/"+id,"/payment/refunds/"+id+"/status"}) {
            f.mvc.perform(get(url).with(user(foreign))).andExpect(status().isForbidden());
        }
        for(String suffix:new String[]{"/recipient"}) {
            var request=post("/payment/refunds/"+id+suffix).with(user(foreign)).with(csrf())
                    .param("bankBin","970422").param("accountNumber","000777888").param("confirmed","true");
            f.mvc.perform(request).andExpect(status().isForbidden());
            f.mvc.perform(post("/payment/refunds/"+id+suffix).with(user(owner)).param("bankBin","970422").param("accountNumber","000777888"))
                    .andExpect(status().isForbidden());
        }
        assertThat(f.jdbc.queryForObject("SELECT account_number FROM refunds",String.class)).isNull();
        var stale=owner;
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=2");
        f.mvc.perform(get("/owner/subscriptions").with(user(stale))).andExpect(status().isForbidden());
        f.mvc.perform(get("/payment/finances/refunds/"+id).with(user(stale))).andExpect(status().isForbidden());
    }
    @Test void missingAdminPayoutChannelNeverFallsBackToOwnerChannel() throws Exception {
        var order=create(SubscriptionPlan.SMALL); pay(order);
        f.jdbc.update("DELETE FROM payment_channels WHERE id=104");
        long inbox=pay(order);
        assertThat(f.count("refunds")).isZero();
        assertThat(f.count("payout_attempts")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT last_error FROM webhook_inbox WHERE id=?",String.class,inbox)).isEqualTo("APPLY_FAILED");
        assertThat(f.jdbc.queryForObject("SELECT status FROM subscription_orders",String.class)).isEqualTo("APPLIED");
    }
    String page(String url) throws Exception {
        return f.mvc.perform(get(url).with(user(f.actor(2)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
    SubscriptionOrderView create(SubscriptionPlan plan) { return f.app.getBean(SubscriptionService.class).createOrder(plan,f.actor(2)); }
    long pay(SubscriptionOrderView order) throws Exception { return pay(order,order.priceSnapshot()); }
    long pay(SubscriptionOrderView order,long amount) throws Exception {
        long id=++transaction;
        String date=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(f.clock.instant());
        byte[] body=new String(SePayInboxTest.body(id,order.intent().code(),Long.toString(amount)),StandardCharsets.UTF_8)
                .replace("000123456","000999999").replace("2026-10-30 10:00:00",date).getBytes(StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L,f.signed(body,"another-fixture-secret"),body);
        long inbox=f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?",Long.class,Long.toString(id));
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        return inbox;
    }
}
