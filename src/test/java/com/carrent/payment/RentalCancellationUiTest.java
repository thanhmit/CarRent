package com.carrent.payment;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RentalCancellationUiTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(false, false); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void ready() {
        f.reset();
        f.jdbc.update("""
                UPDATE bookings SET status='CONFIRMED',workflow_version='VIETQR_V1',inspection_required=1,
                advance_amount=600000,balance_amount=1400000,advance_paid_at='2026-10-29 00:00:00',
                start_date='2026-11-03',end_date='2026-11-05',price_per_day_snapshot=1000000,total_amount=2000000,vehicle_received_at=NULL,
                vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL WHERE id=1
                """);
    }
    String html(String path, long actor) throws Exception {
        return f.mvc.perform(get(path).with(user(f.actor(actor)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
    @Test void customerSeesPaidCancellationQuoteAndReasonWithoutTrustedMoneyInputs() throws Exception {
        String page = html("/bookings/1", 4);
        assertThat(page).contains("Phí hủy dự kiến", "60.000 VNĐ", "540.000 VNĐ", "/bookings/1/cancel",
                "name=\"reason\"", "maxlength=\"500\"", "tính lại khi xác nhận")
                .doesNotContain("name=\"fee\"", "name=\"refund\"", "name=\"cancelledAt\"");
        assertThat(f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1", String.class)).isEqualTo("CONFIRMED");
        assertThat(f.count("refunds")).isZero();
    }
    @Test void ownerSeesFullRefundAndCustomerCannotSeeOwnerForm() throws Exception {
        assertThat(html("/owner/bookings/1", 2)).contains("id=\"cancellation-form\"", "/owner/bookings/1/cancel", "600.000 VNĐ")
                .doesNotContain("540.000 VNĐ");
        assertThat(html("/bookings/1", 2)).doesNotContain("id=\"cancellation-form\"");
    }
    @Test void unpaidCancellationStoresReasonAndDoesNotClaimRefundSucceeded() throws Exception {
        f.jdbc.update("UPDATE bookings SET status='PENDING',advance_paid_at=NULL WHERE id=1");
        f.mvc.perform(post("/bookings/1/cancel").with(user(f.actor(4))).with(csrf())
                        .param("reason", "  Đổi lịch <script>  ").param("fee", "1").param("refund", "99999999"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        String page = html("/bookings/1", 4);
        assertThat(page).contains("id=\"cancellation-summary\"", "Đổi lịch &lt;script&gt;", "Chưa có nghĩa là đã hoàn tiền",
                        "không tạo khoản hoàn", "id=\"cancellation-saved-fee\"")
                .doesNotContain("id=\"cancellation-form\"", "Đổi lịch <script>");
        assertThat(f.count("refunds")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT cancellation_fee FROM bookings WHERE id=1", Long.class)).isZero();
    }
    @Test void ownerCanCancelUnpaidBookingUsingReasonOnly() throws Exception {
        f.jdbc.update("UPDATE bookings SET status='PENDING',advance_paid_at=NULL WHERE id=1");
        f.mvc.perform(post("/owner/bookings/1/cancel").with(user(f.actor(2))).with(csrf()).param("reason", "Xe cần bảo trì"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/owner/bookings/1"))
                .andExpect(flash().attributeExists("successMessage"));
        assertThat(html("/owner/bookings/1", 2)).contains("Xe cần bảo trì", "id=\"cancellation-summary\"");
        assertThat(f.count("refunds")).isZero();
    }
    @Test void blankReasonAndCsrfAndWrongRoleCannotCancel() throws Exception {
        f.mvc.perform(post("/bookings/1/cancel").with(user(f.actor(4))).with(csrf()).param("reason", "  "))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
        f.mvc.perform(post("/bookings/1/cancel").with(user(f.actor(4))).param("reason", "Hủy"))
                .andExpect(status().isForbidden());
        f.mvc.perform(post("/owner/bookings/1/cancel").with(user(f.actor(2))).param("reason", "Hủy"))
                .andExpect(status().isForbidden());
        f.mvc.perform(post("/owner/bookings/1/cancel").with(user(f.actor(4))).with(csrf()).param("reason", "Hủy"))
                .andExpect(status().isForbidden());
        assertThat(f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1", String.class)).isEqualTo("CONFIRMED");
        assertThat(f.count("refunds")).isZero();
    }
    @Test void handoverHidesBothCancellationForms() throws Exception {
        f.jdbc.update("UPDATE bookings SET vehicle_received_at='2026-10-30 03:00:00',contract_confirmed_at='2026-10-30 03:00:00',contract_confirmed_by=2 WHERE id=1");
        assertThat(html("/bookings/1", 4)).doesNotContain("id=\"cancellation-form\"");
        assertThat(html("/owner/bookings/1", 2)).doesNotContain("id=\"cancellation-form\"");
    }
}
