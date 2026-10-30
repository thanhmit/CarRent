package com.carrent.payment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.payment.provider.RefundDestinationVerifier.Destination;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefundRecipientTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); }

    @Test void previewDoesNotWriteAndConfirmationSetsADeadlineOnce() throws Exception {
        long id = refund();
        var service = f.app.getBean(RefundRecipientService.class);
        var form = form();
        var destination = service.preview(id, form, f.actor(4));
        assertThat(destination.accountName()).isEqualTo("NGUYEN TEST");
        assertThat(f.jdbc.queryForObject("SELECT ready_at FROM refunds", java.sql.Timestamp.class)).isNull();
        form.setConfirmedAccountName(destination.accountName());
        service.submit(id, form, f.actor(4));
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("READY");
        var original = f.jdbc.queryForMap("SELECT bank_bin,account_number,account_name,ready_at,due_at FROM refunds");
        assertThat(original.get("account_number")).isEqualTo("000123456");
        assertThat(original.get("account_name")).isEqualTo("NGUYEN TEST");
        assertThat((java.time.LocalDateTime) original.get("due_at"))
                .isEqualTo(((java.time.LocalDateTime) original.get("ready_at")).plusHours(1));
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(100));
        service.submit(id, form, f.actor(4));
        assertThat(f.jdbc.queryForMap("SELECT bank_bin,account_number,account_name,ready_at,due_at FROM refunds")).isEqualTo(original);
        form.setAccountNumber("000987654");
        service.submit(id, form, f.actor(4));
        assertThat(f.jdbc.queryForObject("SELECT account_number FROM refunds", String.class)).isEqualTo("000987654");
        assertThat(f.jdbc.queryForMap("SELECT ready_at,due_at FROM refunds"))
                .containsEntry("ready_at", original.get("ready_at")).containsEntry("due_at", original.get("due_at"));
        assertThat(f.count("payout_attempts")).isZero();
    }

    @Test void recipientCanPreviewAndConfirmFromTheProtectedForm() throws Exception {
        long id = refund();
        String url = "/payment/refunds/" + id;
        f.mvc.perform(get(url).with(user(f.actor(4)))).andExpect(status().isOk());
        String preview = f.mvc.perform(post(url + "/recipient/preview").with(user(f.actor(4))).with(csrf())
                        .param("bankBin", "970422").param("accountNumber", "000123456"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(preview).contains("NGUYEN TEST", "confirmedAccountName");
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("WAITING_RECIPIENT");
        f.mvc.perform(post(url + "/recipient").with(user(f.actor(4))).with(csrf())
                        .param("bankBin", "970422").param("accountNumber", "000123456")
                        .param("confirmedAccountName", "NGUYEN TEST").param("confirmed", "true")
                        .param("amount", "1").param("recipientUserId", "1").param("payoutChannelId", "104"))
                .andExpect(status().is3xxRedirection());
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("READY");
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds", Long.class)).isEqualTo(540000);
        assertThat(f.jdbc.queryForObject("SELECT payout_channel_id FROM refunds", Long.class)).isEqualTo(103);
    }

    @Test void optionalSavedAccountPrefillsAnotherRefundWithoutChangingItsDestinationOrDeadline() throws Exception {
        long id = refund();
        var service = f.app.getBean(RefundRecipientService.class);
        var form = form(); form.setSaveForLater(true); form.setConfirmedAccountName("NGUYEN TEST");
        service.submit(id, form, f.actor(4));
        assertThat(f.jdbc.queryForObject("SELECT account_number FROM refund_recipient_accounts WHERE user_id=4", String.class))
                .isEqualTo("000123456");
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        long next = f.app.getBean(RefundService.class).request("OWNER_CANCEL:1:" + receipt, receipt, 60000, RefundReason.OWNER_CANCEL);
        String html = f.mvc.perform(get("/payment/refunds/" + next).with(user(f.actor(4))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("value=\"000123456\"");
        assertThat(f.jdbc.queryForObject("SELECT ready_at FROM refunds WHERE id=?", java.sql.Timestamp.class, next)).isNull();
        var original = f.jdbc.queryForMap("SELECT account_number,ready_at,due_at FROM refunds WHERE id=?", id);
        form.setAccountNumber("000987654");
        service.submit(next, form, f.actor(4));
        assertThat(f.jdbc.queryForMap("SELECT account_number,ready_at,due_at FROM refunds WHERE id=?", id)).isEqualTo(original);
        assertThat(f.jdbc.queryForObject("SELECT account_number FROM refund_recipient_accounts WHERE user_id=4", String.class))
                .isEqualTo("000987654");
        f.restart(true);
        String reloaded = f.mvc.perform(get("/payment/refunds/" + id).with(user(f.actor(4))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(reloaded).contains("000123456");
    }

    @Test void onlyTheActiveRecipientCanViewPreviewOrConfirmAndBothPostsRequireCsrf() throws Exception {
        long id = refund();
        String url = "/payment/refunds/" + id;
        f.mvc.perform(get(url)).andExpect(status().is3xxRedirection());
        for (long actor : new long[]{1, 2, 5}) {
            f.mvc.perform(get(url).with(user(f.actor(actor)))).andExpect(status().isForbidden());
            for (String suffix : new String[]{"/recipient/preview", "/recipient"}) {
                f.mvc.perform(post(url + suffix).with(user(f.actor(actor))).with(csrf())
                        .param("bankBin", "970422").param("accountNumber", "000123456")
                        .param("confirmedAccountName", "NGUYEN TEST").param("confirmed", "true"))
                        .andExpect(status().isForbidden());
            }
        }
        for (String suffix : new String[]{"/recipient/preview", "/recipient"}) {
            f.mvc.perform(post(url + suffix).with(user(f.actor(4)))).andExpect(status().isForbidden());
            f.mvc.perform(post(url + suffix).with(user(f.actor(4))).with(csrf().useInvalidToken()))
                    .andExpect(status().isForbidden());
        }
        var staleActor = f.actor(4);
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=4");
        f.mvc.perform(get(url).with(user(staleActor))).andExpect(status().isForbidden());
        assertThat(f.jdbc.queryForObject("SELECT ready_at FROM refunds", java.sql.Timestamp.class)).isNull();
    }

    @Test void invalidInputsAndMissingConfirmationDoNotChangeTheRefund() throws Exception {
        long id = refund();
        String url = "/payment/refunds/" + id;
        for (String[] invalid : new String[][]{{"97042", "123"}, {"９７０４２２", "123"},
                {"970422", "12 3"}, {"970422", "1".repeat(51)}}) {
            f.mvc.perform(post(url + "/recipient/preview").with(user(f.actor(4))).with(csrf())
                    .param("bankBin", invalid[0]).param("accountNumber", invalid[1]))
                    .andExpect(status().isBadRequest());
        }
        f.mvc.perform(post(url + "/recipient").with(user(f.actor(4))).with(csrf())
                .param("bankBin", "970422").param("accountNumber", "123")
                .param("confirmedAccountName", "NGUYEN TEST")).andExpect(status().isConflict());
        f.mvc.perform(post(url + "/recipient").with(user(f.actor(4))).with(csrf())
                .param("bankBin", "970422").param("accountNumber", "123")
                .param("confirmed", "true")).andExpect(status().isConflict());
        assertThat(f.jdbc.queryForObject("SELECT ready_at FROM refunds", java.sql.Timestamp.class)).isNull();
        assertThat(f.count("refund_recipient_accounts")).isZero();
    }

    @Test void providerFailureMismatchedEchoOrChangedNameNeverMarksTheRefundReady() throws Exception {
        long id = refund();
        var service = f.app.getBean(RefundRecipientService.class);
        var state = f.app.getBean(PaymentFlowFixture.FixtureState.class);
        var form = form(); form.setConfirmedAccountName("NGUYEN TEST");
        state.verifier = (channel, bank, account) -> { throw new IllegalStateException("private credential details"); };
        assertThatThrownBy(() -> service.submit(id, form, f.actor(4))).isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("private credential");
        state.verifier = (channel, bank, account) -> new Destination(bank, "999", "NGUYEN TEST");
        assertThatThrownBy(() -> service.submit(id, form, f.actor(4))).isInstanceOf(BusinessException.class);
        state.verifier = (channel, bank, account) -> new Destination(bank, account, "OTHER NAME");
        assertThatThrownBy(() -> service.submit(id, form, f.actor(4))).isInstanceOf(BusinessException.class);
        assertThat(f.jdbc.queryForObject("SELECT ready_at FROM refunds", java.sql.Timestamp.class)).isNull();
        assertThat(f.count("payout_attempts")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"actor", "source", "lease", "attempt"})
    void changesDuringProviderVerificationAreRecheckedBeforeCommit(String change) throws Exception {
        long id = refund();
        var actor = f.actor(4);
        var form = form(); form.setConfirmedAccountName("NGUYEN TEST");
        f.app.getBean(PaymentFlowFixture.FixtureState.class).verifier = (channel, bank, account) -> {
            switch (change) {
                case "actor" -> f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=4");
                case "source" -> f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=103");
                case "lease" -> f.jdbc.update("UPDATE refunds SET lease_owner='fixture-worker',lease_until=? WHERE id=?",
                        java.time.LocalDateTime.ofInstant(PaymentFlowFixture.NOW.plusSeconds(60), java.time.ZoneOffset.UTC), id);
                case "attempt" -> insertAttempt(id);
            }
            return new Destination(bank, account, "NGUYEN TEST");
        };
        assertThatThrownBy(() -> f.app.getBean(RefundRecipientService.class).submit(id, form, actor))
                .isInstanceOf(change.equals("actor") ? ForbiddenException.class : BusinessException.class);
        assertThat(f.jdbc.queryForObject("SELECT ready_at FROM refunds", java.sql.Timestamp.class)).isNull();
        assertThat(f.count("refund_recipient_accounts")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"WAITING_FUNDS", "PROCESSING", "SUCCEEDED", "REVIEW_REQUIRED"})
    void processingAndTerminalRefundsCannotChangeTheirDestination(String status) throws Exception {
        long id=refund();
        var service=f.app.getBean(RefundRecipientService.class);
        var form=form(); form.setConfirmedAccountName("NGUYEN TEST");
        service.submit(id,form,f.actor(4));
        f.jdbc.update("UPDATE refunds SET status=? WHERE id=?",status,id);
        form.setAccountNumber("999");
        assertThatThrownBy(() -> service.submit(id,form,f.actor(4))).isInstanceOf(BusinessException.class);
        assertThat(service.view(id,f.actor(4)).editable()).isFalse();
        assertThat(f.jdbc.queryForObject("SELECT account_number FROM refunds",String.class)).isEqualTo("000123456");
    }

    @Test void anOlderSnapshotCannotHideAnAttemptAndAllowTheDestinationToChange() throws Exception {
        long id=refund();
        var service=f.app.getBean(RefundRecipientService.class);
        var form=form(); form.setConfirmedAccountName("NGUYEN TEST");
        service.submit(id,form,f.actor(4));
        var actor=f.actor(4);
        var transactions=f.app.getBean(RefundRecipientTransactions.class);
        var check=transactions.prepare(id,actor);
        f.tx.executeWithoutResult(status -> {
            assertThat(f.app.getBean(PayoutAttemptRepository.class).findByRefundIdOrderByAttemptNoAsc(id)).isEmpty();
            try {
                java.util.concurrent.CompletableFuture.runAsync(() -> insertAttempt(id))
                        .get(30,java.util.concurrent.TimeUnit.SECONDS);
            } catch(Exception ex) { throw new RuntimeException(ex); }
            assertThatThrownBy(() -> transactions.confirm(check,new Destination("970422","999","NGUYEN TEST"),false,actor))
                    .isInstanceOf(BusinessException.class);
            status.setRollbackOnly();
        });
        assertThat(f.jdbc.queryForObject("SELECT account_number FROM refunds",String.class)).isEqualTo("000123456");
    }

    @Test void savingADefaultIsOptInAndItsWriteFailureRollsBackConfirmation() throws Exception {
        long id=refund();
        var service=f.app.getBean(RefundRecipientService.class);
        var form=form(); form.setConfirmedAccountName("NGUYEN TEST");
        service.submit(id,form,f.actor(4));
        assertThat(f.count("refund_recipient_accounts")).isZero();
        var before=f.jdbc.queryForMap("SELECT account_number,ready_at,due_at FROM refunds");
        f.db.execute("CREATE TRIGGER reject_saved_refund_account BEFORE INSERT ON refund_recipient_accounts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Fixture save failure'");
        try {
            form.setSaveForLater(true); form.setAccountNumber("999");
            assertThatThrownBy(() -> service.submit(id,form,f.actor(4))).isInstanceOf(RuntimeException.class);
            assertThat(f.jdbc.queryForMap("SELECT account_number,ready_at,due_at FROM refunds")).isEqualTo(before);
        } finally { f.db.execute("DROP TRIGGER reject_saved_refund_account"); }
    }

    @Test void providerNameIsEscapedAndNotificationLinksToTheRefund() throws Exception {
        long id=refund();
        f.app.getBean(PaymentFlowFixture.FixtureState.class).verifier = (channel, bank, account) ->
                new Destination(bank,account,"<script>alert(1)</script>");
        String html=f.mvc.perform(post("/payment/refunds/"+id+"/recipient/preview").with(user(f.actor(4))).with(csrf())
                .param("bankBin","970422").param("accountNumber","123"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)</script>");
        String notifications=f.mvc.perform(get("/notifications").with(user(f.actor(4))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(notifications).contains("/payment/refunds/"+id);
    }

    @Test void subscriptionRefundUsesAdminFundsAndIsConfirmedByTheOwnerRecipient() throws Exception {
        f.jdbc.update("""
                UPDATE payment_obligations SET business_key='SUBSCRIPTION:1',purpose='SUBSCRIPTION',
                subject_type='SUBSCRIPTION_ORDER',payer_user_id=2,collector_user_id=1,amount_due=5000 WHERE id=201
                """);
        var intent=f.payments.openAttempt(201L,f.actor(2));
        f.payments.cancelAttempt(intent.id(),f.actor(2));
        byte[] body=new String(SePayInboxTest.body(9001,intent.code(),"5000"),java.nio.charset.StandardCharsets.UTF_8)
                .replace("000123456","000999999").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L,f.signed(body,"another-fixture-secret"),body);
        f.app.getBean(ReceiptProcessor.class).process(f.jdbc.queryForObject("SELECT id FROM webhook_inbox",Long.class));
        long receipt=f.jdbc.queryForObject("SELECT id FROM payment_receipts",Long.class);
        long id=f.app.getBean(RefundService.class).request("CANCELLED_SUBSCRIPTION:"+receipt,receipt,5000,RefundReason.CANCELLED_SUBSCRIPTION);
        assertThat(f.jdbc.queryForObject("SELECT payout_channel_id FROM refunds",Long.class)).isEqualTo(104);
        assertThat(f.jdbc.queryForObject("SELECT recipient_user_id FROM refunds",Long.class)).isEqualTo(2);
        var form=form(); form.setConfirmedAccountName("NGUYEN TEST");
        f.app.getBean(RefundRecipientService.class).submit(id,form,f.actor(2));
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("READY");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='REFUND_WAITING_RECIPIENT' AND user_id=2",Long.class)).isEqualTo(1);
    }

    @Test void productionDefaultCannotVerifyAnAccountOrPretendItHasBeenConfirmed() throws Exception {
        try (var local=new PaymentFlowFixture(true,false)) {
            local.reset();
            var intent=local.payments.openAttempt(201L,local.actor(4));
            local.app.getBean(ReceiptProcessor.class).process(local.receive(9001,intent.code(),"600000"));
            long receipt=local.jdbc.queryForObject("SELECT id FROM payment_receipts",Long.class);
            long id=local.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:"+receipt,receipt,540000,RefundReason.CUSTOMER_CANCEL);
            var form=form(); form.setConfirmedAccountName("NGUYEN TEST");
            assertThatThrownBy(() -> local.app.getBean(RefundRecipientService.class).submit(id,form,local.actor(4)))
                    .isInstanceOf(BusinessException.class);
            assertThat(local.jdbc.queryForObject("SELECT ready_at FROM refunds",java.sql.Timestamp.class)).isNull();
            assertThat(local.count("payout_attempts")).isZero();
        }
    }

    static void insertAttempt(long id) {
        f.jdbc.update("""
                INSERT INTO payout_attempts(refund_id,attempt_no,payout_channel_id,reference_id,idempotency_key,
                amount,bank_bin,account_number,account_name,status,created_at,updated_at)
                SELECT id,1,payout_channel_id,CONCAT('fixture-refund-',id),CONCAT('fixture-key-',id),
                amount,'970422','000123456','NGUYEN TEST','UNKNOWN',created_at,updated_at FROM refunds WHERE id=?
                """,id);
    }

    static long refund() throws Exception {
        var intent = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, intent.code(), "600000"));
        long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
        return f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt, receipt, 540000, RefundReason.CUSTOMER_CANCEL);
    }

    static RefundRecipientForm form() {
        var form = new RefundRecipientForm();
        form.setBankBin("970422"); form.setAccountNumber("000123456");
        return form;
    }
}
