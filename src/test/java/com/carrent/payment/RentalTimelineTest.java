package com.carrent.payment;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RentalTimelineTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(false, false); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void ready() {
        for (var table : java.util.List.of("inspection_events", "inspection_images", "booking_inspections")) f.jdbc.update("DELETE FROM " + table);
        f.reset();
        f.jdbc.update("UPDATE bookings SET status='EXPIRED',workflow_version='VIETQR_V1',inspection_required=1,advance_amount=600000,balance_amount=1400000,owner_accepted_at=NULL,before_accepted_at=NULL,advance_paid_at=NULL,vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL WHERE id=1");
    }
    String html(String path, long actor) throws Exception {
        return f.mvc.perform(get(path).with(user(f.actor(actor)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test void expiredBookingHasExplicitStatusWithoutDebtClaim() throws Exception {
        assertThat(html("/bookings/1", 4)).contains("Đã hết hạn").doesNotContain("Còn lại 70%:");
    }
    @Test void adminHasReadOnlyTimelineAndCannotOfferInspectionDraft() throws Exception {
        String page = html("/admin/bookings/1", 1);
        assertThat(page).contains("id=\"rental-timeline\"", "Chủ xe chấp nhận", "Hai bên đồng ý BEFORE",
                "Đã nhận trả trước 30%", "Chủ xe xác nhận hợp đồng", "Giao xe thực tế",
                "AFTER đã gửi", "Nhận lại xe thực tế", "Đã nhận thanh toán 70%", "Khoản hoàn tiền")
                .doesNotContain("/inspections/BEFORE", "/inspections/AFTER", "/cancel", "/handover", "/return");
        assertThat(f.count("refunds")).isZero();
        assertThat(f.count("payment_intents")).isZero();
    }
    @Test void allThreeViewsShowDistinctMilestonesWithoutCreatingFinancialState() throws Exception {
        f.jdbc.update("UPDATE bookings SET owner_accepted_at='2026-10-29 00:00:00',before_accepted_at='2026-10-29 01:00:00',advance_paid_at='2026-10-29 02:00:00' WHERE id=1");
        for (var entry : java.util.Map.of("/bookings/1",4L,"/owner/bookings/1",2L,"/admin/bookings/1",1L).entrySet()) {
            assertThat(html(entry.getKey(),entry.getValue())).contains("data-milestone=\"owner-accepted\"",
                    "2026-10-29T00:00:00Z", "2026-10-29T01:00:00Z", "2026-10-29T02:00:00Z", "Chưa ghi nhận");
        }
        assertThat(f.count("payment_intents")).isZero();
        assertThat(f.count("refunds")).isZero();
    }
    void settledWithRefund(RefundStatus refundStatus) {
        f.jdbc.update("UPDATE bookings SET status='COMPLETED',advance_paid_at='2026-10-29 00:00:00' WHERE id=1");
        f.tx.executeWithoutResult(s -> {
            var obligation=f.em.find(PaymentObligation.class,201L);
            obligation.setBusinessKey("BOOKING:1:BALANCE"); obligation.setPurpose(PaymentPurpose.BALANCE);
            obligation.setAmountDue(1400000L); obligation.setAmountSettled(1400000L); obligation.setStatus(PaymentObligationStatus.SETTLED);
            var channel=f.em.find(PaymentChannel.class,101L);
            var intent=PaymentIntent.builder().obligation(obligation).channel(channel).code("CRTMDISPLAY001")
                    .amount(1400000L).status(PaymentIntentStatus.RECEIVED).createdAt(PaymentFlowFixture.NOW)
                    .expiresAt(PaymentFlowFixture.NOW.plusSeconds(600)).build(); f.em.persist(intent);
            var inbox=WebhookInbox.builder().channel(channel).providerTransactionId("66001").rawPayload(new byte[]{1})
                    .receivedAt(PaymentFlowFixture.NOW).status(WebhookInboxStatus.PROCESSED).build(); f.em.persist(inbox);
            var receipt=PaymentReceipt.builder().channel(channel).providerTransactionId("66001").inbox(inbox)
                    .intent(intent).amount(1400000L).allocatedAmount(1400000L).creditedAt(java.time.Instant.parse("2026-10-29T12:34:56Z"))
                    .createdAt(PaymentFlowFixture.NOW).status(PaymentReceiptStatus.APPLIED).build(); f.em.persist(receipt);
            f.em.persist(Refund.builder().businessKey("DUPLICATE_PAYMENT:timeline").receipt(receipt)
                    .payoutChannel(f.em.find(PaymentChannel.class,103L)).recipient(f.em.find(com.carrent.user.User.class,4L))
                    .amount(10L).reason(RefundReason.DUPLICATE_PAYMENT).status(refundStatus)
                    .createdAt(PaymentFlowFixture.NOW).updatedAt(PaymentFlowFixture.NOW).build());
        });
    }
    @Test void completedUsesBankCreditTimestampAndNeverClaimsRemainingDebt() throws Exception {
        settledWithRefund(RefundStatus.WAITING_RECIPIENT);
        for (var entry : java.util.Map.of("/bookings/1",4L,"/owner/bookings/1",2L,"/admin/bookings/1",1L).entrySet()) {
            assertThat(html(entry.getKey(),entry.getValue())).contains("Đã nhận đủ tiền thuê 30% + 70%.",
                    "2026-10-29T12:34:56Z", "data-refund-status=\"WAITING_RECIPIENT\"")
                    .doesNotContain("Còn lại 70%:", "— còn 70% thanh toán sau trả xe.");
        }
    }
    @Test void cancelledAndWaitingFundsKeepMoneyReceivedSeparateFromRefundSuccess() throws Exception {
        settledWithRefund(RefundStatus.WAITING_FUNDS);
        f.jdbc.update("UPDATE bookings SET status='CANCELLED' WHERE id=1");
        String page=html("/bookings/1",4);
        assertThat(page).contains("data-refund-status=\"WAITING_FUNDS\"", "Chờ bên hoàn tiền bổ sung số dư")
                .doesNotContain("Còn lại 70%:", "Nhà cung cấp đã xác nhận hoàn tiền thành công");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_receipts",String.class)).isEqualTo("APPLIED");
    }
    @Test void draftDoesNotLeakIntoAdminTimelineOrInspectionLinks() throws Exception {
        f.tx.executeWithoutResult(s -> f.em.persist(com.carrent.inspection.BookingInspection.builder()
                .booking(f.em.find(com.carrent.booking.Booking.class,1L)).phase(com.carrent.inspection.InspectionPhase.AFTER)
                .author(f.em.find(com.carrent.user.User.class,4L)).status(com.carrent.inspection.InspectionStatus.DRAFT)
                .conditionNote("PRIVATE-DRAFT-TEXT").createdAt(PaymentFlowFixture.NOW).updatedAt(PaymentFlowFixture.NOW).build()));
        assertThat(html("/admin/bookings/1",1)).doesNotContain("PRIVATE-DRAFT-TEXT", "/inspections/AFTER");
    }
    @Test void adminRouteRejectsOtherRolesAndBannedStalePrincipalAndHasNoMutation() throws Exception {
        f.mvc.perform(get("/admin/bookings/1").with(user(f.actor(4)))).andExpect(status().isForbidden());
        var admin=f.actor(1); f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=1");
        f.mvc.perform(get("/admin/bookings/1").with(user(admin))).andExpect(status().isForbidden());
        f.jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=1");
        f.mvc.perform(post("/admin/bookings/1/return").with(user(admin)).with(csrf())).andExpect(status().isNotFound());
        assertThat(f.count("refunds")).isZero();
    }
    @Test void legacyViewKeepsExistingPaymentPresentation() throws Exception {
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY',inspection_required=0 WHERE id=1");
        assertThat(html("/bookings/1",4)).doesNotContain("id=\"rental-timeline\"", "Đã nhận đủ tiền thuê 30% + 70%.");
        assertThat(html("/admin/bookings/1",1)).contains("Đơn thuê legacy").doesNotContain("id=\"rental-timeline\"");
    }
    @Test void completedWithoutReceiptNeitherInventsReceivedMoneyNorClaimsDebt() throws Exception {
        f.jdbc.update("UPDATE bookings SET status='COMPLETED' WHERE id=1");
        assertThat(html("/bookings/1",4)).contains("Đơn thuê đã hoàn thành; xem các giao dịch")
                .doesNotContain("Còn lại 70%:", "— còn 70% thanh toán sau trả xe.", "Đã nhận đủ tiền thuê 30% + 70%.");
    }
}
