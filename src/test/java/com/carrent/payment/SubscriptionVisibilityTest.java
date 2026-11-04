package com.carrent.payment;

import com.carrent.car.Car;
import com.carrent.subscription.SubscriptionPlan;
import com.carrent.subscription.SubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Page;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubscriptionVisibilityTest {
    static PaymentFlowFixture f;
    static long transaction;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.jdbc.update("DELETE FROM notifications");
        f.reset();
    }

    @Test void missingEntitlementHidesSeedListingsFromHomeSearchAndDirectUrls() throws Exception {
        assertThat(page("/")).doesNotContain("Toyota Vios", "Ford Everest", "VinFast VF 8");
        assertThat(search("/cars").getTotalElements()).isZero();
        f.mvc.perform(get("/cars/1")).andExpect(status().isNotFound());
        f.mvc.perform(get("/cars/1").with(user(f.actor(4)))).andExpect(status().isNotFound());
    }

    @Test void activeEntitlementPublishesApprovedIntentOnlyAcrossHomeSearchAndDetail() throws Exception {
        grant(2);
        f.historicalSmallSubscription(3);
        assertThat(page("/")).contains("Toyota Vios", "Ford Everest", "VinFast VF 8")
                .doesNotContain("Mitsubishi Xpander", "Mercedes-Benz C200", "Kia Morning");
        assertThat(search("/cars").getTotalElements()).isEqualTo(3);
        assertThat(page("/cars/1")).contains("Đặt thuê xe").doesNotContain("Bản xem trước:");
        for (int id : new int[]{3, 4, 5}) f.mvc.perform(get("/cars/" + id)).andExpect(status().isNotFound());
    }

    @Test void filtersCountsAndPaginationExcludeExpiredOwnersBeforePaging() throws Exception {
        grant(2);
        var first = search("/cars?size=1&sort=priceAsc");
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(Car::getId).containsExactly(1L);
        assertThat(search("/cars?size=1&page=1&sort=priceAsc").getContent()).extracting(Car::getId).containsExactly(2L);
        assertThat(search("/cars?fuelType=ELECTRIC").getTotalElements()).isZero();
        assertThat(search("/cars?q=Toyota&maxPrice=600000").getTotalElements()).isOne();
    }

    @Test void exactExpiryImmediatelyHidesListingButOwnerAndAdminRetainPrivatePreview() throws Exception {
        grant(2);
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(30L * 86400));
        assertThat(search("/cars").getTotalElements()).isZero();
        assertThat(page("/")).doesNotContain("Toyota Vios");
        f.mvc.perform(get("/cars/1")).andExpect(status().isNotFound());
        f.mvc.perform(get("/cars/1").with(user(f.actor(3)))).andExpect(status().isNotFound());
        f.mvc.perform(get("/cars/1").with(user(f.actor(4)))).andExpect(status().isNotFound());
        for (long viewer : new long[]{1, 2}) {
            String html = f.mvc.perform(get("/cars/1").with(user(f.actor(viewer)))).andExpect(status().isOk())
                    .andExpect(model().attribute("publicListing", false))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).contains("Bản xem trước:").doesNotContain("/bookings/new");
        }
        assertThat(f.jdbc.queryForObject("SELECT listing_status FROM cars WHERE id=1", String.class)).isEqualTo("ACTIVE");
        assertThat(f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1", String.class)).isEqualTo("PENDING");
    }

    @Test void renewalRestoresIntendedActiveListingsWithoutReactivatingDeliberatelyHiddenCar() throws Exception {
        grant(2);
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(30L * 86400));
        grant(2);
        assertThat(search("/cars").getContent()).extracting(Car::getId).containsExactlyInAnyOrder(1L, 2L);
        f.mvc.perform(get("/cars/5")).andExpect(status().isNotFound());
        assertThat(f.jdbc.queryForObject("SELECT listing_status FROM cars WHERE id=5", String.class)).isEqualTo("INACTIVE");
    }

    @Test void ownerCanDiscoverRejectedCarResubmissionWithoutJavascript() throws Exception {
        String html = f.mvc.perform(get("/owner/cars").with(user(f.actor(3)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("/owner/cars/4/activate", "Gửi duyệt lại Mercedes-Benz C200", "name=\"_csrf\"");
    }

    private String page(String path) throws Exception {
        return f.mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
    @SuppressWarnings("unchecked")
    private Page<Car> search(String path) throws Exception {
        return (Page<Car>) f.mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("page");
    }
    private void grant(long owner) throws Exception {
        var order = f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL, f.actor(owner));
        long id = ++transaction + 98000;
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(f.clock.instant());
        byte[] body = new String(SePayInboxTest.body(id, order.intent().code(), "5000"), StandardCharsets.UTF_8)
                .replace("000123456", "000999999").replace("2026-10-30 10:00:00", date).getBytes(StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L, f.signed(body, "another-fixture-secret"), body);
        long inbox = f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?", Long.class, Long.toString(id));
        f.app.getBean(ReceiptProcessor.class).process(inbox);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM owner_subscriptions WHERE owner_id=?", Long.class, owner)).isOne();
    }
}
