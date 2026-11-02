package com.carrent.payment;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

class RentalBalanceUiTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.rental.balance.enabled", "true");
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.rental.balance.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void ready() {
        for (var table : List.of("inspection_events", "inspection_images", "booking_inspections", "notifications")) f.jdbc.update("DELETE FROM " + table);
        f.reset();
        f.jdbc.update("""
                UPDATE bookings SET status='CONFIRMED',workflow_version='VIETQR_V1',inspection_required=1,
                advance_amount=600000,balance_amount=1400000,advance_paid_at='2026-10-29 00:00:00',
                vehicle_received_at='2026-10-29 00:00:00',vehicle_returned_at='2026-10-30 03:00:00',
                contract_confirmed_by=2,contract_confirmed_at='2026-10-29 00:00:00',
                balance_overdue_notified_at=NULL WHERE id=1
                """);
        var at = LocalDateTime.ofInstant(PaymentFlowFixture.NOW, ZoneOffset.UTC);
        f.jdbc.update("""
                INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,
                collector_user_id,amount_due,amount_settled,status,due_at,created_at,updated_at)
                VALUES(202,'BOOKING:1:BALANCE','BALANCE','BOOKING',1,4,2,1400000,0,'OPEN',?,?,?)
                """, at.plusHours(1), at, at);
        f.jdbc.update("""
                INSERT INTO payment_intents(id,obligation_id,channel_id,code,amount,status,created_at,expires_at)
                VALUES(301,202,101,'CRBALANCEUI',1400000,'ACTIVE',?,?)
                """, at, at.plusMinutes(15));
    }
    String getHtml(String path) throws Exception {
        return f.mvc.perform(get(path).with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
    }
    @Test void checkoutSeparatesDebtDeadlineFromQrAndNeverOffersBookingCancel() throws Exception {
        String html = getHtml("/payment/intents/301");
        assertThat(html).contains("Thanh toán phần còn lại 70%", "Khoản nợ còn lại: 1.400.000 VNĐ",
                "Hạn trả nợ", "Hạn mã QR", "Hủy lần thanh toán", "data-booking-advance=\"false\"")
                .doesNotContain("Hủy đặt xe", "Đã đồng ý BEFORE", "Trả trước cho đơn thuê");
        f.mvc.perform(get("/bookings/1").with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(content().string(containsString("/payment/obligations/202/attempts")))
                .andExpect(content().string(containsString("Thanh toán phần còn lại 70% / tạo QR mới")));
    }
    @Test void expiryGetAndPollAreReadOnlyAndOfferPostRetry() throws Exception {
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(4000));
        String html = getHtml("/payment/intents/301");
        assertThat(html).contains("QR đã hết hạn", "Tạo lần thanh toán mới", "method=\"post\"", "/payment/obligations/202/attempts")
                .doesNotContain("id=\"payment-qr\"", "Hủy đặt xe");
        f.mvc.perform(get("/payment/intents/301/status").with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.rental.remainingAmount").value(1400000));
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=301", String.class)).isEqualTo("ACTIVE");
        assertThat(f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1", String.class)).isEqualTo("CONFIRMED");
        assertThat(f.jdbc.queryForObject("SELECT balance_overdue_notified_at FROM bookings WHERE id=1", java.sql.Timestamp.class)).isNull();
    }
    @Test void anotherQrSettlesDebtAndSuppressesStillActiveQr() throws Exception {
        f.jdbc.update("UPDATE payment_obligations SET amount_settled=1400000,status='SETTLED' WHERE id=202");
        f.jdbc.update("UPDATE bookings SET status='COMPLETED' WHERE id=1");
        String html = getHtml("/payment/intents/301");
        assertThat(html).contains("Đã nhận đủ phần còn lại 70%", "Đơn thuê đã hoàn tất")
                .doesNotContain("id=\"payment-qr\"", "Tạo lần thanh toán mới", "Hủy đặt xe");
        f.mvc.perform(get("/payment/intents/301/status").with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.qrUrl").doesNotExist())
                .andExpect(jsonPath("$.rental.completed").value(true));
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=301", String.class)).isEqualTo("ACTIVE");
    }
    @Test void cancelledAttemptPreservesDebtAndShowsRetry() throws Exception {
        var due = f.jdbc.queryForObject("SELECT due_at FROM payment_obligations WHERE id=202", java.sql.Timestamp.class);
        f.mvc.perform(post("/payment/intents/301/cancel").with(user(f.actor(4))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/payment/intents/301/result"));
        assertThat(getHtml("/payment/intents/301/result")).contains("Khoản nợ và hạn trả vẫn giữ nguyên", "Tạo lần thanh toán mới");
        assertThat(f.jdbc.queryForObject("SELECT due_at FROM payment_obligations WHERE id=202", java.sql.Timestamp.class)).isEqualTo(due);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=202", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM bookings WHERE id=1", String.class)).isEqualTo("CONFIRMED");
    }
    @Test void authorizationAndCsrfProtectBalanceActions() throws Exception {
        f.mvc.perform(get("/payment/intents/301/status").with(user(f.actor(2)))).andExpect(status().isForbidden());
        f.mvc.perform(post("/payment/intents/301/cancel").with(user(f.actor(4)))).andExpect(status().isForbidden());
        f.mvc.perform(post("/payment/obligations/202/attempts").with(user(f.actor(4)))).andExpect(status().isForbidden());
        f.mvc.perform(get("/bookings/1").with(user(f.actor(2)))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/payment/obligations/202/attempts"))));
    }
    @Test void excessRefundPendingDoesNotDescribeCompletedRentalAsUnpaid() throws Exception {
        long inbox = f.receive(881, "CRBALANCEUI", "1500000");
        var at = LocalDateTime.ofInstant(PaymentFlowFixture.NOW, ZoneOffset.UTC);
        f.jdbc.update("""
                INSERT INTO payment_receipts(id,channel_id,provider_transaction_id,inbox_id,intent_id,amount,
                allocated_amount,credited_at,created_at,status)
                VALUES(401,101,'881',?,301,1500000,1400000,?,?,'REFUND_REQUIRED')
                """, inbox, at, at);
        f.jdbc.update("""
                INSERT INTO refunds(business_key,receipt_id,payout_channel_id,recipient_user_id,amount,reason,
                status,created_at,updated_at) VALUES('EXCESS:401',401,103,4,100000,'EXCESS_BALANCE','WAITING_RECIPIENT',?,?)
                """, at, at);
        f.jdbc.update("UPDATE payment_obligations SET amount_settled=1400000,status='SETTLED' WHERE id=202");
        f.jdbc.update("UPDATE bookings SET status='COMPLETED' WHERE id=1");
        String html = getHtml("/payment/intents/301/result");
        assertThat(html).contains("Đang chờ hoàn khoản chuyển dư hoặc trùng", "Đã nhận đủ phần còn lại 70%", "Đơn thuê đã hoàn tất")
                .doesNotContain("Tạo lần thanh toán mới", "id=\"payment-qr\"");
        f.mvc.perform(get("/payment/intents/301/status").with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.rental.remainingAmount").value(0))
                .andExpect(jsonPath("$.rental.completed").value(true));
    }
    @Test void shortReceiptStillNeedsReviewAfterAnotherPaymentSettlesDebt() throws Exception {
        long inbox = f.receive(882, "CRBALANCEUI", "1300000");
        var at = LocalDateTime.ofInstant(PaymentFlowFixture.NOW, ZoneOffset.UTC);
        f.jdbc.update("""
                INSERT INTO payment_receipts(id,channel_id,provider_transaction_id,inbox_id,intent_id,amount,
                allocated_amount,credited_at,created_at,status)
                VALUES(402,101,'882',?,301,1300000,0,?,?,'REVIEW_REQUIRED')
                """, inbox, at, at);
        f.jdbc.update("UPDATE payment_obligations SET amount_settled=1400000,status='SETTLED' WHERE id=202");
        f.jdbc.update("UPDATE bookings SET status='COMPLETED' WHERE id=1");
        f.mvc.perform(get("/payment/intents/301/status").with(user(f.actor(4)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.rental.remainingAmount").value(0))
                .andExpect(jsonPath("$.rental.completed").value(true));
        assertThat(getHtml("/payment/intents/301/result")).contains("Giao dịch cần được kiểm tra", "Đã nhận đủ phần còn lại 70%", "Đơn thuê đã hoàn tất")
                .doesNotContain("Tạo lần thanh toán mới", "id=\"payment-qr\"");
    }
    @Test void shortReceiptCanEndActiveAttemptBeforeCreatingAnotherQr() throws Exception {
        long inbox = f.receive(883, "CRBALANCEUI", "1300000");
        var at = LocalDateTime.ofInstant(PaymentFlowFixture.NOW, ZoneOffset.UTC);
        f.jdbc.update("""
                INSERT INTO payment_receipts(id,channel_id,provider_transaction_id,inbox_id,intent_id,amount,
                allocated_amount,credited_at,created_at,status)
                VALUES(403,101,'883',?,301,1300000,0,?,?,'REVIEW_REQUIRED')
                """, inbox, at, at);
        String html = getHtml("/payment/intents/301/result");
        assertThat(html).contains("Hủy lần thanh toán", "/payment/intents/301/cancel", "Giao dịch cần được kiểm tra")
                .doesNotContain("Hủy đặt xe", "Tạo lần thanh toán mới", "id=\"payment-qr\"");
        f.mvc.perform(post("/payment/intents/301/cancel").with(user(f.actor(4))).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(getHtml("/payment/intents/301/result")).contains("Tạo lần thanh toán mới").doesNotContain("Hủy đặt xe");
        f.mvc.perform(post("/payment/obligations/202/attempts").with(user(f.actor(4))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("/payment/intents/*"));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents WHERE obligation_id=202", Long.class)).isEqualTo(2L);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=202", Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts WHERE id=403", String.class)).isEqualTo("REVIEW_REQUIRED");
    }
}
