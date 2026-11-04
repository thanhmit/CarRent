package com.carrent.payment;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubscriptionUiTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.reset();
    }

    String page(String path) throws Exception {
        return f.mvc.perform(get(path).with(user(f.actor(2)))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
    long create() throws Exception {
        String redirect = f.mvc.perform(post("/owner/subscriptions/orders").with(user(f.actor(2))).with(csrf())
                        .param("plan", "SMALL").param("price", "1").param("amount", "1")
                        .param("ownerId", "3").param("collectorId", "3"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).startsWith("/owner/subscriptions/orders/");
        return Long.parseLong(redirect.substring(redirect.lastIndexOf('/') + 1));
    }

    @Test void catalogRendersThreeServerPlansAndCsrfWithoutCreatingOrders() throws Exception {
        assertThat(page("/owner/subscriptions")).contains("SMALL", "STANDARD", "BUSINESS", "5.000 VNĐ",
                "10.000 VNĐ", "15.000 VNĐ", "name=\"_csrf\"", "name=\"plan\"");
        assertThat(f.count("subscription_orders")).isZero();
    }

    @Test void createIgnoresClientFinancialFieldsAndRefreshReusesIntent() throws Exception {
        long id = create();
        String path = "/owner/subscriptions/orders/" + id;
        String first = page(path);
        assertThat(first).contains("data-status=\"PENDING\"", "5.000 VNĐ", "/payment/intents/", "name=\"_csrf\"");
        assertThat(page(path)).contains("data-order-id=\"" + id + "\"");
        assertThat(f.count("payment_intents")).isOne();
        assertThat(f.jdbc.queryForObject("SELECT owner_id FROM subscription_orders", Long.class)).isEqualTo(2L);
        assertThat(f.jdbc.queryForObject("SELECT collector_user_id FROM payment_obligations WHERE purpose='SUBSCRIPTION'", Long.class)).isEqualTo(1L);
        f.mvc.perform(post(path + "/cancel").with(user(f.actor(2))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(path));
        assertThat(page(path)).contains("data-status=\"CANCELLED\"").doesNotContain("Hủy yêu cầu mua gói</button>");
        assertThat(f.count("owner_subscriptions")).isZero();
    }

    @Test void invalidPlanAndMissingCsrfCannotCreateOrders() throws Exception {
        f.mvc.perform(post("/owner/subscriptions/orders").with(user(f.actor(2))).with(csrf()).param("plan", "FREE"))
                .andExpect(status().isBadRequest());
        f.mvc.perform(post("/owner/subscriptions/orders").with(user(f.actor(2))).param("plan", "SMALL"))
                .andExpect(status().isForbidden());
        assertThat(f.count("subscription_orders")).isZero();
    }

    @Test void rolesOtherOwnerAndStaleBannedPrincipalCannotReadOrCancel() throws Exception {
        f.mvc.perform(get("/owner/subscriptions").with(user(f.actor(4)))).andExpect(status().isForbidden());
        f.mvc.perform(get("/owner/subscriptions").with(user(f.actor(1)))).andExpect(status().isForbidden());
        long id = create();
        String path = "/owner/subscriptions/orders/" + id;
        f.mvc.perform(get(path).with(user(f.actor(3)))).andExpect(status().isForbidden());
        f.mvc.perform(post(path + "/cancel").with(user(f.actor(3))).with(csrf())).andExpect(status().isForbidden());
        var stale = f.actor(2);
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=2");
        f.mvc.perform(get("/owner/subscriptions").with(user(stale))).andExpect(status().isForbidden());
        f.mvc.perform(get(path).with(user(stale))).andExpect(status().isForbidden());
        assertThat(f.jdbc.queryForObject("SELECT status FROM subscription_orders WHERE id=?", String.class, id)).isEqualTo("PENDING");
    }
}
