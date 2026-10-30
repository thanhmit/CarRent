package com.carrent.payment;

import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserRepository;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentModelTest {
    @Test
    void repositoriesRoundTripFinancialSnapshotsAndUtcInstantsOnTheMigratedSchema() throws Exception {
        try (var db = PaymentMigrationTest.populatedDatabase()) {
            TimeZone original = TimeZone.getDefault();
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            try (var app = new SpringApplicationBuilder(com.carrent.CarRentApplication.class)
                    .run("--server.port=0", "--spring.datasource.url=" + db.url,
                            "--spring.datasource.username=" + db.username,
                            "--spring.datasource.password=" + db.password,
                            "--spring.jpa.hibernate.ddl-auto=validate", "--spring.main.banner-mode=off",
                            "--logging.level.root=WARN", "--debug=false")) {
                var channels = app.getBean(PaymentChannelRepository.class);
                var obligations = app.getBean(PaymentObligationRepository.class);
                var intents = app.getBean(PaymentIntentRepository.class);
                var inbox = app.getBean(WebhookInboxRepository.class);
                var receipts = app.getBean(PaymentReceiptRepository.class);
                var refunds = app.getBean(RefundRepository.class);
                var attempts = app.getBean(PayoutAttemptRepository.class);
                var users = app.getBean(UserRepository.class);
                var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
                Instant at = Instant.parse("2026-10-29T04:05:06.123456Z");
                Long channelId = tx.execute(status -> {
                    User owner = users.findById(2L).orElseThrow();
                    PaymentChannel channel = channels.saveAndFlush(PaymentChannel.builder()
                            .user(owner).ownerRole(Role.OWNER).provider(PaymentProvider.SEPAY)
                            .purpose(PaymentChannelPurpose.COLLECT).credentialReference("utc-fixture")
                            .bankBin("970422").accountNumber("000123456").enabled(false)
                            .createdAt(at).updatedAt(at).build());
                    var originalObligation = obligations.findByBusinessKey("BOOKING:1:ADVANCE").orElseThrow();
                    var obligation = obligations.saveAndFlush(PaymentObligation.builder()
                            .businessKey("BOOKING:2:BALANCE").purpose(PaymentPurpose.BALANCE)
                            .subjectType(PaymentSubjectType.BOOKING).subjectId(2L)
                            .payer(originalObligation.getPayer()).collector(owner)
                            .amountDue(9_007_199_254_740_993L).dueAt(at.plusSeconds(3600))
                            .createdAt(at).updatedAt(at).build());
                    var intent = intents.saveAndFlush(PaymentIntent.builder().obligation(obligation)
                            .channel(channel).code("CRMODEL02").amount(9_007_199_254_740_993L)
                            .createdAt(at).expiresAt(at.plusSeconds(900)).build());
                    var event = inbox.saveAndFlush(WebhookInbox.builder().channel(channel)
                            .providerTransactionId("99001").rawPayload(new byte[]{0, (byte) 255, 13, 10})
                            .receivedAt(at).leaseOwner("model-worker").leaseUntil(at.plusSeconds(30)).build());
                    var receipt = receipts.saveAndFlush(PaymentReceipt.builder().channel(channel).inbox(event)
                            .intent(intent).providerTransactionId("99001").amount(9_007_199_254_740_993L)
                            .allocatedAmount(9_007_199_254_740_993L).creditedAt(at).createdAt(at)
                            .status(PaymentReceiptStatus.APPLIED).build());
                    var refund = refunds.saveAndFlush(Refund.builder().businessKey("EXCESS:" + receipt.getId())
                            .receipt(receipt).payoutChannel(channels.findById(102L).orElseThrow())
                            .recipient(originalObligation.getPayer()).amount(600000L)
                            .reason(RefundReason.EXCESS_BALANCE).bankBin("970422").accountNumber("000123456")
                            .accountName("NGUYEN THI TEST").readyAt(at).dueAt(at.plusSeconds(3600))
                            .status(RefundStatus.PROCESSING).createdAt(at).updatedAt(at).build());
                    attempts.saveAndFlush(PayoutAttempt.builder().refund(refund).attemptNo(1)
                            .payoutChannel(refund.getPayoutChannel()).referenceId("model-refund-attempt-1")
                            .idempotencyKey("model-key-1").providerPayoutId("model-provider-id-1")
                            .amount(600000L).bankBin(refund.getBankBin()).accountNumber(refund.getAccountNumber())
                            .accountName(refund.getAccountName()).status(PayoutAttemptStatus.UNKNOWN)
                            .submittedAt(at).nextReconcileAt(at.plusSeconds(30)).createdAt(at).updatedAt(at).build());
                    return channel.getId();
                });
                tx.executeWithoutResult(status -> {
                    var channel = channels.findById(channelId).orElseThrow();
                    assertThat(channel.getCreatedAt()).isEqualTo(at);
                    assertThat(channel.getAccountNumber()).isEqualTo("000123456");
                    assertThat(channel.getUser().getRole()).isEqualTo(Role.OWNER);
                    assertThat(channels.findByUserIdAndPurposeAndEnabledTrue(2L, PaymentChannelPurpose.COLLECT)).isEmpty();
                    var obligation = obligations.findByBusinessKey("BOOKING:1:ADVANCE").orElseThrow();
                    assertThat(obligation.getAmountDue()).isEqualTo(600000L);
                    assertThat(obligations.findByIdForUpdate(obligation.getId())).isPresent();
                    assertThat(intents.findByCode("CRTEST01").orElseThrow().getObligation().getId()).isEqualTo(201L);
                    assertThat(inbox.findByChannelIdAndProviderTransactionId(101L, "9001").orElseThrow().getRawPayload())
                            .containsExactly((byte) 0x7b, (byte) 0x00, (byte) 0xff, (byte) 0x7d);
                    var receipt = receipts.findByChannelIdAndProviderTransactionId(101L, "9001").orElseThrow();
                    assertThat(receipt.getAllocatedAmount()).isEqualTo(600000L);
                    assertThat(receipt.getIntent().getId()).isEqualTo(301L);
                    var refund = refunds.findByBusinessKey("DUPLICATE:501").orElseThrow();
                    assertThat(refund.getStatus()).isEqualTo(RefundStatus.WAITING_RECIPIENT);
                    assertThat(refund.getPayoutChannel().getCredentialReference()).isEqualTo("owner-payout");
                    var attempt = attempts.findByReferenceId("refund:601:attempt:1").orElseThrow();
                    assertThat(attempt.getStatus()).isEqualTo(PayoutAttemptStatus.UNKNOWN);
                    assertThat(attempt.getIdempotencyKey()).isEqualTo("refund-601-attempt-1");
                    assertThat(attempt.getAccountNumber()).isEqualTo("000123456");
                    assertThat(attempt.getAmount()).isEqualTo(600000L);
                    assertThat(attempts.findByRefundIdOrderByAttemptNoAsc(refund.getId())).hasSize(1);
                    var modelObligation = obligations.findByBusinessKey("BOOKING:2:BALANCE").orElseThrow();
                    assertThat(modelObligation.getAmountDue()).isEqualTo(9_007_199_254_740_993L);
                    assertThat(modelObligation.getDueAt()).isEqualTo(at.plusSeconds(3600));
                    assertThat(intents.findByCode("CRMODEL02").orElseThrow().getExpiresAt()).isEqualTo(at.plusSeconds(900));
                    var modelEvent = inbox.findByChannelIdAndProviderTransactionId(channelId, "99001").orElseThrow();
                    assertThat(modelEvent.getRawPayload()).containsExactly((byte) 0, (byte) 255, (byte) 13, (byte) 10);
                    assertThat(modelEvent.getLeaseUntil()).isEqualTo(at.plusSeconds(30));
                    var modelReceipt = receipts.findByChannelIdAndProviderTransactionId(channelId, "99001").orElseThrow();
                    assertThat(modelReceipt.getAmount()).isEqualTo(9_007_199_254_740_993L);
                    assertThat(modelReceipt.getCreditedAt()).isEqualTo(at);
                    var modelRefund = refunds.findByBusinessKey("EXCESS:" + modelReceipt.getId()).orElseThrow();
                    assertThat(modelRefund.getReadyAt()).isEqualTo(at);
                    assertThat(modelRefund.getDueAt()).isEqualTo(at.plusSeconds(3600));
                    var modelAttempt = attempts.findByIdempotencyKey("model-key-1").orElseThrow();
                    assertThat(modelAttempt.getProviderPayoutId()).isEqualTo("model-provider-id-1");
                    assertThat(modelAttempt.getSubmittedAt()).isEqualTo(at);
                    assertThat(modelAttempt.getNextReconcileAt()).isEqualTo(at.plusSeconds(30));
                });
                try (var s = db.connection.createStatement(); var rows = s.executeQuery(
                        "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM payment_channels WHERE id=" + channelId)) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("2026-10-29 04:05:06.123456");
                }
            } finally {
                TimeZone.setDefault(original);
            }
        }
    }
}
