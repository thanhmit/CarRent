package com.carrent.payment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceUiTest {
    static PaymentFlowFixture f;

    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled", "true");
        f = new PaymentFlowFixture(false, false);
        f.reset();
        f.historicalSmallSubscription(2);
    }

    @AfterAll static void stop() throws Exception {
        try { if (f != null) f.close(); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
    }

    @Test void publicNavigationMarksThePageBeingViewed() throws Exception {
        for (String[] route : new String[][]{{"/", "/"}, {"/cars", "/cars"},
                {"/cars/1", "/cars"}, {"/bookings", "/bookings"}, {"/bookings/1", "/bookings"}}) {
            assertThat(currentLinks(nav(html(route[0], 4), "Điều hướng chính")))
                    .as(route[0]).containsExactly(route[1]);
        }
    }

    @Test void pagesWithoutAPublicSectionDoNotMarkHome() throws Exception {
        assertThat(currentLinks(nav(html("/profile", 4), "Điều hướng chính"))).isEmpty();
        assertThat(currentLinks(nav(html("/payment/finances", 4), "Điều hướng chính"))).isEmpty();
    }

    @Test void ownerShellIsConsistentAcrossAllFourCallers() throws Exception {
        for (String[] route : new String[][]{{"/owner/cars", "/owner/cars"},
                {"/owner/cars/new", "/owner/cars"}, {"/owner/bookings", "/owner/bookings"},
                {"/owner/bookings/1", "/owner/bookings"}}) {
            String html = html(route[0], 2);
            String menu = nav(html, "Điều hướng chủ xe");
            assertThat(currentLinks(menu)).as(route[0]).containsExactly(route[1]);
            assertThat(menu).contains("/owner/dashboard", "/owner/subscriptions",
                    "/payment/finances?view=collected", "/payment/finances?view=paid",
                    "/payment/finances?view=refunds").doesNotContain("/admin/");
            assertThat(html).contains("Menu chủ xe", "Menu tài khoản", "action=\"/logout\"");
            assertThat(Pattern.compile("<form[^>]*action=\"/logout\"[^>]*>(.*?)</form>", Pattern.DOTALL)
                    .matcher(html).results().map(m -> m.group(1)).toList())
                    .hasSize(1).allSatisfy(form -> assertThat(form).contains("name=\"_csrf\""));
        }
    }

    @Test void adminShellShowsAdminNavigationAcrossBothCallers() throws Exception {
        for (String route : List.of("/admin/cars", "/admin/cars/1")) {
            String html = html(route, 1);
            String menu = nav(html, "Điều hướng quản trị");
            assertThat(currentLinks(menu)).as(route).containsExactly("/admin/cars");
            assertThat(menu).contains("/admin/dashboard", "/admin/bookings", "/admin/users", "/admin/finance")
                    .doesNotContain("/owner/");
            assertThat(html).contains("Menu quản trị", "Menu tài khoản");
        }
    }

    @Test void disabledSubscriptionsDoNotAdvertiseThePurchaseRoute() throws Exception {
        System.setProperty("carrent.subscription.enabled", "false");
        try {
            assertThat(nav(html("/owner/cars", 2), "Điều hướng chủ xe"))
                    .doesNotContain("/owner/subscriptions");
        } finally { System.setProperty("carrent.subscription.enabled", "true"); }
    }

    @Test void ownerDashboardUsesWorkspaceNavigation() throws Exception {
        String page = html("/owner/dashboard", 2);
        assertThat(currentLinks(nav(page, "Điều hướng chủ xe"))).containsExactly("/owner/dashboard");
        assertThat(page).doesNotContain("aria-label=\"Điều hướng chính\"");
        assertThat(Pattern.compile("data-metric=\"([^\"]+)\"").matcher(page).results()
                .map(m -> m.group(1)).toList()).containsExactlyInAnyOrder("publicCars", "pendingCars",
                        "hiddenCars", "bookings", "allocatedRental", "refundsSucceeded", "refundsPending",
                        "refundsPendingAmount", "refundsOverdue", "refundsOverdueAmount",
                        "outstandingDebt", "overdueDebts", "overdueDebtAmount");
    }

    @Test void financeWorkspaceMarksEachOwnerScope() throws Exception {
        for (String view : List.of("paid", "collected", "refunds")) {
            String page = html("/payment/finances?view=" + view, 2);
            assertThat(currentLinks(nav(page, "Điều hướng chủ xe")))
                    .containsExactly("/payment/finances?view=" + view);
            assertThat(page).doesNotContain("aria-label=\"Điều hướng chính\"");
        }
    }

    @Test void adminFinanceNavigationDistinguishesPersonalAndManagedScope() throws Exception {
        for (String view : List.of("paid", "collected", "refunds", "managed")) {
            String page = html("/payment/finances?view=" + view, 1);
            assertThat(currentLinks(nav(page, "Điều hướng quản trị")))
                    .containsExactly(view.equals("managed") ? "/admin/finance" : "/payment/finances");
            assertThat(currentLinks(nav(page, "Phạm vi tài chính")))
                    .containsExactly("/payment/finances?view=" + view);
        }
    }

    private static String html(String route, long actor) throws Exception {
        return f.mvc.perform(get(route).with(user(f.actor(actor)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String nav(String html, String label) {
        var match = Pattern.compile("<nav\\b[^>]*aria-label=\"" + Pattern.quote(label)
                + "\"[^>]*>(.*?)</nav>", Pattern.DOTALL).matcher(html);
        assertThat(match.find()).as("Navigation: " + label).isTrue();
        return match.group(1);
    }

    private static List<String> currentLinks(String nav) {
        var links = new ArrayList<String>();
        var tags = Pattern.compile("<a\\b[^>]*>").matcher(nav);
        while (tags.find()) {
            String tag = tags.group();
            if (!tag.contains("aria-current=\"page\"")) continue;
            var href = Pattern.compile("href=\"([^\"]+)\"").matcher(tag);
            assertThat(href.find()).isTrue();
            links.add(href.group(1));
        }
        return links;
    }
}
