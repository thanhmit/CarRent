package com.carrent.payment;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FinancialViewsTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); }

    @Test void reviewReceiptsAreVisibleToTheirCollectorAndAdminWithoutExposingProviderPayload() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        var processor = f.app.getBean(ReceiptProcessor.class);
        processor.process(f.receive(9901, intent.code(), "123457"));
        processor.process(f.receive(9902, "CRUNKNOWNCODE", "234569"));
        for (String url : new String[]{"/payment/finances?view=collected", "/payment/finances?view=managed"}) {
            String page = html(url, url.contains("managed") ? 1 : 2);
            assertThat(page).contains("Giao dịch cần kiểm tra", "123.457", "234.569", "Chưa khớp lần thanh toán")
                    .doesNotContain("000123456", "fixture-secret", "CRUNKNOWNCODE", "rawPayload", "/payment/receipts/");
        }
        assertThat(html("/payment/finances?view=paid", 4)).contains("123.457").doesNotContain("234.569");
    }

    @Test void financeListUsesTheSameDerivedStatusAsCheckoutIncludingExpiryReviewAndRefunds() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        assertListMatchesStatus(intent.id(), 4);
        f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=101");
        assertListMatchesStatus(intent.id(), 4);
        f.jdbc.update("UPDATE payment_channels SET enabled=1 WHERE id=101");
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9901, intent.code(), "123457"));
        assertListMatchesStatus(intent.id(), 4);
        f.reset();
        intent = f.payments.openAttempt(201L, f.actor(4));
        f.clock.at.set(intent.expiresAt());
        assertListMatchesStatus(intent.id(), 4);
        f.reset();
        intent = f.payments.openAttempt(201L, f.actor(4));
        f.payments.cancelAttempt(intent.id(), f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9902, intent.code(), "600000"));
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        long refund = f.app.getBean(RefundService.class).request("LATE_ADVANCE:" + receipt, receipt, 600000, RefundReason.LATE_ADVANCE);
        assertListMatchesStatus(intent.id(), 4);
        f.jdbc.update("UPDATE refunds SET status='SUCCEEDED' WHERE id=?", refund);
        assertListMatchesStatus(intent.id(), 4);
        assertThat(html("/payment/finances?view=collected", 2)).contains("Lần thanh toán gần nhất: Đã hoàn tiền");
    }

    @Test void cancellationRefundOfAnAppliedReceiptIsReflectedInBothCheckoutAndFinanceList() throws Exception {
        long refund = refund();
        long intent = f.jdbc.queryForObject("SELECT id FROM payment_intents", Long.class);
        assertThat(f.payments.status(intent, f.actor(4)).status()).isEqualTo("REFUND_PENDING");
        assertListMatchesStatus(intent, 4);
        f.jdbc.update("UPDATE refunds SET status='SUCCEEDED' WHERE id=?", refund);
        assertThat(f.payments.status(intent, f.actor(4)).status()).isEqualTo("REFUNDED");
        assertListMatchesStatus(intent, 4);
    }

    private void assertListMatchesStatus(long intent, long actor) throws Exception {
        assertThat(html("/payment/finances?view=paid", actor))
                .contains("Lần thanh toán gần nhất: " + f.payments.status(intent, f.actor(actor)).message());
    }

    @Test void reviewReceiptsArePagedAndUnmatchedMoneyNeverLeaksAcrossCollectorsOrToPayers() throws Exception {
        var processor = f.app.getBean(ReceiptProcessor.class);
        for (int i = 0; i < 21; i++) processor.process(f.receive(12000 + i, null, Integer.toString(100001 + i)));
        String first = html("/payment/finances?view=collected", 2);
        assertThat(first).contains("100.021", "100.002", "Trang sau").doesNotContain("100.001 VNĐ");
        assertThat(html("/payment/finances?view=collected&page=1", 2)).contains("100.001 VNĐ").doesNotContain("100.021", "Trang sau");
        assertThat(html("/payment/finances?view=collected", 1)).doesNotContain("100.021");
        assertThat(html("/payment/finances?view=paid", 4)).doesNotContain("100.021");
        f.jdbc.update("INSERT INTO users(id,full_name,email,password_hash,role,status) SELECT 99001,'Other Owner','review-other@example.test',password_hash,'OWNER','ACTIVE' FROM users WHERE id=2");
        try {
            assertThat(html("/payment/finances?view=collected", 99001)).doesNotContain("100.021");
            var stale = f.actor(2);
            f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=2");
            f.mvc.perform(get("/payment/finances?view=collected").with(user(stale))).andExpect(status().isForbidden());
        } finally { f.jdbc.update("DELETE FROM users WHERE id=99001"); }
    }

    @Test void adminCollectionReviewUsesAdminChannelAndOwnerCanSeeOnlyTheirMatchedSubscription() throws Exception {
        f.jdbc.update("UPDATE payment_obligations SET business_key='SUBSCRIPTION:1',purpose='SUBSCRIPTION',subject_type='SUBSCRIPTION_ORDER',payer_user_id=2,collector_user_id=1");
        var intent = f.payments.openAttempt(201L, f.actor(2));
        for (int i = 0; i < 2; i++) {
            byte[] payload = new String(SePayInboxTest.body(13000 + i, i == 0 ? intent.code() : null, Integer.toString(345671 + i)), StandardCharsets.UTF_8)
                    .replace("000123456", "000999999").getBytes(StandardCharsets.UTF_8);
            f.app.getBean(SePayWebhookService.class).receive(102L, f.signed(payload, "another-fixture-secret"), payload);
        }
        for (long id : f.jdbc.queryForList("SELECT id FROM webhook_inbox", Long.class)) f.app.getBean(ReceiptProcessor.class).process(id);
        assertThat(html("/payment/finances?view=collected", 1)).contains("345.671", "345.672").doesNotContain("000999999");
        assertThat(html("/payment/finances?view=paid", 2)).contains("345.671").doesNotContain("345.672");
        assertThat(html("/payment/finances?view=collected", 2)).doesNotContain("345.671", "345.672");
        assertListMatchesStatus(intent.id(), 2);
    }

    @Test void payerCollectorAndAdminHaveScopedReadOnlyViewsWithMaskedAccounts() throws Exception {
        assertThat(f.app.containsBean("paymentMaintenanceTick")).isFalse();
        long id = refund();
        String customer = html("/payment/finances?view=refunds", 4);
        assertThat(customer).contains("540.000", "••••3456").doesNotContain("000123456", "NGUYEN TEST", "fixture-secret");
        assertThat(html("/payment/finances?view=paid", 4)).contains("600.000", "/payment/intents/");
        assertThat(html("/payment/finances?view=collected", 2)).contains("600.000", "540.000");
        assertThat(html("/payment/finances?view=managed", 1)).contains("540.000");
        assertThat(html("/payment/finances/refunds/" + id, 2)).contains("540.000").doesNotContain("000123456", "confirmedAccountName");
        f.mvc.perform(get("/payment/refunds/" + id).with(user(f.actor(2)))).andExpect(status().isForbidden());
        f.mvc.perform(post("/payment/refunds/" + id + "/recipient").with(user(f.actor(2)))).andExpect(status().isForbidden());
    }

    @Test void legacySchemaShowsUnavailableAndStatusDoesNotExposeASqlError() throws Exception {
        f.jdbc.execute("RENAME TABLE payout_attempts TO fixture_hidden_attempts");
        try {
            assertThat(html("/payment/finances", 4)).contains("Chức năng tài chính chưa sẵn sàng");
            String json = f.mvc.perform(get("/payment/refunds/1/status").with(user(f.actor(4))))
                    .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(json).doesNotContain("SQLException", "payment_obligations", "credential");
        } finally { f.jdbc.execute("RENAME TABLE fixture_hidden_attempts TO payout_attempts"); }
    }

    @Test void otherOwnersAndCustomersCannotReadRowsOrPollAndBannedSessionsAreRejected() throws Exception {
        long id = refund();
        f.jdbc.update("INSERT INTO users(id,full_name,email,password_hash,role,status) SELECT 99001,'Other Owner','other-owner@example.test',password_hash,'OWNER','ACTIVE' FROM users WHERE id=2");
        f.jdbc.update("INSERT INTO users(id,full_name,email,password_hash,role,status) SELECT 99002,'Other Customer','other-customer@example.test',password_hash,'CUSTOMER','ACTIVE' FROM users WHERE id=4");
        try {
            for (long actor : new long[]{99001, 99002}) {
                assertThat(html("/payment/finances?view=refunds", actor)).doesNotContain("540.000");
                f.mvc.perform(get("/payment/finances/refunds/" + id).with(user(f.actor(actor)))).andExpect(status().isForbidden());
                f.mvc.perform(get("/payment/refunds/" + id + "/status").with(user(f.actor(actor)))).andExpect(status().isForbidden());
            }
            assertThat(html("/payment/finances?view=collected", 99001)).doesNotContain("600.000", "540.000");
            f.mvc.perform(get("/payment/finances?view=managed").with(user(f.actor(2)))).andExpect(status().isForbidden());
            f.mvc.perform(get("/payment/finances?view=collected").with(user(f.actor(4)))).andExpect(status().isForbidden());
            var session = f.actor(4);
            f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=4");
            f.mvc.perform(get("/payment/finances").with(user(session))).andExpect(status().isForbidden());
            f.mvc.perform(get("/payment/refunds/" + id + "/status").with(user(session))).andExpect(status().isForbidden());
        } finally { f.jdbc.update("DELETE FROM users WHERE id IN (99001,99002)"); }
    }

    @Test void statusIsRedactedAndOverdueNeverOverridesCommittedSuccess() throws Exception {
        long id = refund();
        f.clock.at.set(f.clock.instant().plusSeconds(3600));
        for (long actor : new long[]{1, 2, 4}) {
            String json = f.mvc.perform(get("/payment/refunds/" + id + "/status").with(user(f.actor(actor))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json).contains("\"status\":\"READY\"", "\"overdue\":true")
                    .doesNotContain("000123456", "NGUYEN TEST", "bankBin", "accountNumber", "credential", "receipt", "payload");
        }
        f.jdbc.update("UPDATE refunds SET status='SUCCEEDED'");
        assertThat(html("/payment/finances/refunds/" + id, 4)).contains("đã xác nhận hoàn tiền thành công").doesNotContain("Quá thời hạn xử lý");
        assertThat(f.mvc.perform(get("/payment/refunds/" + id + "/status").with(user(f.actor(4))))
                .andReturn().getResponse().getContentAsString()).contains("\"overdue\":false");
    }

    @Test void ownerSubscriptionIsPayerAndRecipientWhileRentalIsCollectedAndAdminReadDoesNotAllowEdits() throws Exception {
        f.jdbc.update("UPDATE payment_obligations SET business_key='SUBSCRIPTION:1',purpose='SUBSCRIPTION',subject_type='SUBSCRIPTION_ORDER',payer_user_id=2,collector_user_id=1");
        var intent = f.payments.openAttempt(201L, f.actor(2));
        f.payments.cancelAttempt(intent.id(), f.actor(2));
        byte[] body = new String(SePayInboxTest.body(9001, intent.code(), "600000"), StandardCharsets.UTF_8)
                .replace("000123456", "000999999").getBytes(StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L, f.signed(body, "another-fixture-secret"), body);
        f.app.getBean(ReceiptProcessor.class).process(f.jdbc.queryForObject("SELECT id FROM webhook_inbox", Long.class));
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        long id = f.app.getBean(RefundService.class).request("CANCELLED_SUBSCRIPTION:" + receipt, receipt, 540000, RefundReason.CANCELLED_SUBSCRIPTION);
        assertThat(html("/payment/finances?view=paid", 2)).contains("Phí gói chủ xe", "600.000");
        assertThat(html("/payment/finances?view=refunds", 2)).contains("540.000");
        assertThat(html("/payment/finances?view=collected", 2)).doesNotContain("540.000", "600.000");
        assertThat(html("/payment/finances?view=collected", 1)).contains("540.000", "600.000");
        f.mvc.perform(post("/payment/refunds/" + id + "/recipient").with(user(f.actor(1)))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
        f.jdbc.update("UPDATE payment_receipts SET intent_id=NULL");
        assertThat(html("/payment/finances?view=managed", 1)).contains("540.000");
        assertThat(html("/payment/finances/refunds/" + id, 1)).contains("540.000");
    }

    long refund() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, intent.code(), "600000"));
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        long id = f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt, receipt, 540000, RefundReason.CUSTOMER_CANCEL);
        var form = new RefundRecipientForm(); form.setBankBin("970422"); form.setAccountNumber("000123456"); form.setConfirmedAccountName("NGUYEN TEST");
        f.app.getBean(RefundRecipientService.class).submit(id, form, f.actor(4));
        return id;
    }

    String html(String url, long actor) throws Exception {
        return f.mvc.perform(get(url).with(user(f.actor(actor)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
