package com.carrent.payment;

import com.carrent.payment.provider.PayoutCommand;
import com.carrent.payment.provider.TransferOutcome;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BankAccountRefundFlowTest {
    static PaymentFlowFixture f;
    PayoutWorkerTest.FakePayouts provider;
    final AtomicInteger lookups = new AtomicInteger();

    @BeforeAll static void start() throws Exception {
        f = new PaymentFlowFixture(true, true, PayoutWorkerTest.Configuration.class);
    }
    @AfterAll static void close() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.reset();
        provider = f.app.getBean(PayoutWorkerTest.FakePayouts.class);
        provider.reset();
        f.app.getBean(PaymentFlowFixture.FixtureState.class).verifier = (channel, bank, account) -> {
            lookups.incrementAndGet();
            throw new IllegalStateException("Lookup is unavailable and must not be called");
        };
    }

    @Test void bankAndAccountAloneCanReachPayosWithoutLookupOrFabricatedNames() throws Exception {
        long id = refund();
        f.mvc.perform(post("/payment/refunds/" + id + "/recipient").with(user(f.actor(4))).with(csrf())
                .param("bankBin", "970422").param("accountNumber", "000123456").param("confirmed", "true")
                .param("saveForLater", "true")).andExpect(status().is3xxRedirection());
        assertThat(lookups).hasValue(0);
        assertThat(f.jdbc.queryForObject("SELECT account_name FROM refund_recipient_accounts WHERE user_id=4", String.class)).isNull();
        assertThat(f.jdbc.queryForMap("SELECT amount,payout_channel_id,status,account_name FROM refunds WHERE id=?", id))
                .containsEntry("amount", 540000L).containsEntry("payout_channel_id", 103L)
                .containsEntry("status", "READY").containsEntry("account_name", null);
        f.app.getBean(PayoutWorker.class).dispatch(id);
        assertThat(provider.creates).hasValue(1);
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, id)).isEqualTo("SUCCEEDED");
        assertThat(f.jdbc.queryForObject("SELECT account_number FROM payout_attempts WHERE refund_id=?", String.class, id))
                .isEqualTo("000123456");
        f.app.getBean(PayoutWorker.class).dispatch(id);
        assertThat(provider.creates).hasValue(1);
    }

    @Test void recipientPageOffersBankNamesAndOneConfirmationWithoutANameStep() throws Exception {
        long id = refund();
        String html = f.mvc.perform(get("/payment/refunds/" + id).with(user(f.actor(4))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("<select", "970422", "MB", "/recipient\"")
                .doesNotContain("confirmedAccountName", "/recipient/preview", "Tên do nhà cung cấp xác minh");
        assertThat(lookups).hasValue(0);
    }

    @Test void namelessDestinationRecoversAfterRestartWithoutASecondTransfer() throws Exception {
        long id = refund();
        confirm(id);
        var command = new AtomicReference<PayoutCommand>();
        provider.onCreate = (channel, value) -> { command.set(value); return null; };
        f.app.getBean(PayoutWorker.class).dispatch(id);
        long attempt = f.jdbc.queryForObject("SELECT id FROM payout_attempts WHERE refund_id=?", Long.class, id);
        assertThat(f.jdbc.queryForObject("SELECT account_name FROM payout_attempts WHERE id=?", String.class, attempt)).isNull();
        assertThat(provider.creates).hasValue(1);
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(120));
        f.restart(true);
        var restarted = f.app.getBean(PayoutWorkerTest.FakePayouts.class);
        restarted.reset();
        restarted.onFind = (channel, reference) -> {
            assertThat(reference).isEqualTo(command.get().referenceId());
            return PayoutWorkerTest.result(command.get(), TransferOutcome.SUCCEEDED);
        };
        f.app.getBean(PayoutWorker.class).reconcile(attempt);
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, id)).isEqualTo("SUCCEEDED");
        assertThat(restarted.creates).hasValue(0);
        assertThat(restarted.finds).hasValue(1);
        assertThat(f.count("payout_attempts")).isEqualTo(1);
    }

    void confirm(long id) throws Exception {
        f.mvc.perform(post("/payment/refunds/" + id + "/recipient").with(user(f.actor(4))).with(csrf())
                .param("bankBin", "970422").param("accountNumber", "000123456").param("confirmed", "true")
                .param("confirmedAccountName", "UNTRUSTED NAME").param("amount", "1").param("payoutChannelId", "104"))
                .andExpect(status().is3xxRedirection());
    }

    long refund() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, intent.code(), "600000"));
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        return f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt, receipt, 540000, RefundReason.CUSTOMER_CANCEL);
    }
}
