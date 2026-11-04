package com.carrent.payment;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPermissionsTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); }

    @Test void adminDashboardAndFinanceShowRealCountsAndObligations() throws Exception {
        assertThat(html("/admin/dashboard")).contains("Phí gói", "Toàn hệ thống", "/admin/finance");
        assertThat(html("/admin/finance")).contains("Nghĩa vụ thanh toán", "600.000", "Chủ xe #2");
    }

    @Test void ownerCustomerAndAnonymousCannotReadAdminListsOrDetails() throws Exception {
        for (String url : new String[]{"/admin/dashboard", "/admin/finance", "/admin/finance/OBLIGATIONS/201",
                "/admin/finance/RECEIPTS/1", "/admin/finance/REFUNDS/1"}) {
            f.mvc.perform(get(url)).andExpect(status().is3xxRedirection());
            for (long actor : new long[]{2,4})
                f.mvc.perform(get(url).with(user(f.actor(actor)))).andExpect(status().isForbidden());
        }
    }

    @Test void staleAdminMustStillBeActiveAndAdminInDatabase() throws Exception {
        var actor=f.actor(1);
        try {
            for (String state : new String[]{"INACTIVE","BANNED"}) {
                f.jdbc.update("UPDATE users SET status=? WHERE id=1",state);
                for (String url : new String[]{"/admin/dashboard","/admin/finance","/admin/finance/OBLIGATIONS/201"})
                    f.mvc.perform(get(url).with(user(actor))).andExpect(status().isForbidden());
            }
            f.jdbc.update("UPDATE users SET status='ACTIVE',role='OWNER' WHERE id=1");
            f.mvc.perform(get("/admin/finance").with(user(actor))).andExpect(status().isForbidden());
        } finally { f.jdbc.update("UPDATE users SET status='ACTIVE',role='ADMIN' WHERE id=1"); }
    }

    @Test void receiptPagingAndCollectorFiltersKeepUnmatchedCashAndStableOrdering() throws Exception {
        var processor=f.app.getBean(ReceiptProcessor.class);
        for (int i=0;i<21;i++) processor.process(f.receive(83000+i,"CRUNKNOWN",Integer.toString(100001+i)));
        String url="/admin/finance?kind=RECEIPTS&status=REVIEW_REQUIRED&collectorId=2&sort=AMOUNT";
        String first=html(url);
        assertThat(first).contains("100.021", "100.002", "Chưa khớp nghĩa vụ", "Trang sau", "collectorId=2", "sort=AMOUNT")
                .doesNotContain("100.001 VNĐ", "000123456", "fixture-secret", "CRUNKNOWN");
        assertThat(html(url+"&page=1")).contains("100.001 VNĐ").doesNotContain("100.021 VNĐ", "Trang sau");
        assertThat(html(url.replace("collectorId=2","collectorId=1"))).contains("Không có khoản tài chính phù hợp");
        assertThat(html(url+"&subjectType=BOOKING&subjectId=1")).contains("Không có khoản tài chính phù hợp");
        assertThat(html(url.replace("sort=AMOUNT","sort=OLDEST"))).contains("100.001", "100.020").doesNotContain("100.021 VNĐ");
    }

    @Test void combinedSubjectStatusAndCollectorFiltersSelectOnlyMatchingObligation() throws Exception {
        assertThat(html("/admin/finance?status=OPEN&subjectType=BOOKING&subjectId=1&collectorId=2"))
                .contains("600.000", "/admin/finance/OBLIGATIONS/201");
        assertThat(html("/admin/finance?status=SETTLED&subjectType=BOOKING&subjectId=1&collectorId=2"))
                .contains("Không có khoản tài chính phù hợp");
        assertThat(html("/admin/finance?subjectType=SUBSCRIPTION_ORDER&subjectId=1"))
                .contains("Không có khoản tài chính phù hợp");
        assertThat(html("/admin/finance/OBLIGATIONS/201")).contains("600.000", "Chủ xe #2");
        f.mvc.perform(get("/admin/finance/OBLIGATIONS/999999").with(user(f.actor(1)))).andExpect(status().isNotFound());
    }

    @Test void refundStatusDeadlineAndMaskedAccountDoNotClaimPendingMoneySucceeded() throws Exception {
        long refund=refund();
        var due=java.time.LocalDateTime.ofInstant(f.clock.instant(),java.time.ZoneOffset.UTC);
        f.jdbc.update("UPDATE refunds SET status='WAITING_FUNDS',bank_bin='970422',account_number='123456789012',account_name='Test',ready_at=?,due_at=? WHERE id=?",due.minusHours(1),due,refund);
        assertThat(html("/admin/finance?kind=REFUNDS&overdue=true&collectorId=2"))
                .contains("••••9012", "Quá hạn", "bổ sung số dư").doesNotContain("123456789012");
        assertThat(html("/admin/finance/REFUNDS/"+refund)).contains("••••9012", "bổ sung số dư")
                .doesNotContain("123456789012", "xác nhận hoàn tiền thành công");
        f.jdbc.update("UPDATE refunds SET status='SUCCEEDED' WHERE id=?",refund);
        assertThat(html("/admin/finance?kind=REFUNDS&overdue=true")).contains("Không có khoản tài chính phù hợp");
        assertThat(html("/admin/finance?kind=REFUNDS&status=SUCCEEDED")).contains("xác nhận hoàn tiền thành công");
    }

    @Test void invalidFiltersReturnSafeBadRequestAndCannotInjectSqlOrMarkup() throws Exception {
        for (String query : new String[]{"sort=DROP_TABLE", "kind=invalid", "status=OPEN' OR 1=1", "collectorId=-2",
                "subjectId=0", "page=not-a-number", "subjectType=invalid", "status=<script>alert('xss')</script>"}) {
            String page=f.mvc.perform(get("/admin/finance?"+query).with(user(f.actor(1))))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(page).doesNotContain("DROP_TABLE", "OR 1=1", "alert('xss')", "SELECT q.", "fixture-secret");
        }
    }

    @Test void adminGetRoutesDoNotWriteFinancialOrBookingStateAndNeverRenderProviderPayload() throws Exception {
        long refund=refund();
        var before=snapshot();
        for (String url : new String[]{"/admin/dashboard", "/admin/finance", "/admin/finance?kind=RECEIPTS",
                "/admin/finance?kind=REFUNDS", "/admin/finance/REFUNDS/"+refund, "/admin/finance/OBLIGATIONS/201"})
            assertThat(html(url)).doesNotContain("fixture-secret", "000123456", "raw_payload", "provider_transaction_id");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void maliciousProviderPayloadIsNotRenderedInReceiptListOrDetail() throws Exception {
        f.app.getBean(ReceiptProcessor.class).process(f.receive(88999,"CR<img src=x onerror=alert(7)>","123456"));
        long receipt=f.jdbc.queryForObject("SELECT id FROM payment_receipts",Long.class);
        for (String url : new String[]{"/admin/finance?kind=RECEIPTS", "/admin/finance/RECEIPTS/"+receipt})
            assertThat(html(url)).contains("123.456", "Cần đối soát").doesNotContain("onerror=", "alert(7)", "raw_payload");
    }

    @Test void missingFinancialSchemaShowsUnavailableInsteadOfMisleadingZeroStats() throws Exception {
        f.jdbc.execute("RENAME TABLE payout_attempts TO p52_saved_attempts");
        try {
            for (String url : new String[]{"/admin/dashboard","/admin/finance","/admin/finance/OBLIGATIONS/201"})
                assertThat(html(url)).contains("chưa sẵn sàng").doesNotContain("Doanh thu phí gói", "600.000");
        } finally { f.jdbc.execute("RENAME TABLE p52_saved_attempts TO payout_attempts"); }
    }

    private long refund() throws Exception {
        var intent=f.payments.openAttempt(201L,f.actor(4));
        var processor=f.app.getBean(ReceiptProcessor.class);
        processor.process(f.receive(88001,intent.code(),"600000"));
        processor.process(f.receive(88002,intent.code(),"600000"));
        long receipt=f.jdbc.queryForObject("SELECT id FROM payment_receipts WHERE provider_transaction_id='88002'",Long.class);
        return f.app.getBean(RefundService.class).request("DUPLICATE:"+receipt,receipt,600000,RefundReason.DUPLICATE_PAYMENT);
    }

    private java.util.Map<String,java.util.List<String>> snapshot() {
        var result=new java.util.LinkedHashMap<String,java.util.List<String>>();
        for (String table : new String[]{"bookings","payment_channels","payment_obligations","payment_intents",
                "webhook_inbox","payment_receipts","refunds","payout_attempts","notifications","subscription_orders"}) {
            result.put(table,f.jdbc.query("SELECT * FROM "+table+" ORDER BY id",(rs,n)->{
                var row=new StringBuilder();
                for (int i=1;i<=rs.getMetaData().getColumnCount();i++) row.append(rs.getString(i)).append('|');
                return row.toString();
            }));
        }
        return result;
    }

    private String html(String url) throws Exception {
        return f.mvc.perform(get(url).with(user(f.actor(1)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
