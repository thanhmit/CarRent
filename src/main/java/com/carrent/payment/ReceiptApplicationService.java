package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiptApplicationService {
    private final EntityManager em;
    private final PaymentLocks locks;
    private final PaymentRoutes routes;
    private final PaymentIntentRepository intents;
    private final PaymentReceiptRepository receipts;
    private final Clock clock;

    public ReceiptApplicationService(EntityManager em, PaymentLocks locks, PaymentRoutes routes,
            PaymentIntentRepository intents, PaymentReceiptRepository receipts, Clock clock) {
        this.em = em; this.locks = locks; this.routes = routes;
        this.intents = intents; this.receipts = receipts; this.clock = clock;
    }

    /** Called through the Spring proxy by ReceiptProcessor after the independent claim has committed. */
    @Transactional
    public void apply(Long inboxId, String leaseOwner) {
        var snapshot = em.find(WebhookInbox.class, inboxId);
        if (snapshot == null) return;
        var event = SePayIncomingPayment.parse(snapshot.getRawPayload());
        var candidate = event.code() == null ? null : intents.findByCode(event.code())
                .filter(i -> i.getChannel().getId().equals(snapshot.getChannel().getId())).orElse(null);
        PaymentDomainHandler handler = null;
        PaymentObligation obligation = null;
        PaymentIntent intent = null;
        if (candidate != null) {
            var route = routes.intent(candidate.getId());
            handler = routes.handler(route.subject());
            handler.lockSubject(route.subjectId());
            obligation = locks.current(PaymentObligation.class, route.obligationId());
            intent = locks.current(PaymentIntent.class, candidate.getId());
            if (!intent.getObligation().getId().equals(obligation.getId())
                    || PaymentSubject.of(obligation) != route.subject()
                    || !obligation.getSubjectId().equals(route.subjectId())) {
                throw new BusinessException("Đối tượng thanh toán đã thay đổi.");
            }
        }
        var inbox = locks.current(WebhookInbox.class, inboxId);
        if (leaseOwner == null || !Objects.equals(leaseOwner, inbox.getLeaseOwner())
                || inbox.getStatus() != WebhookInboxStatus.PROCESSING
                || inbox.getLeaseUntil() == null || !clock.instant().isBefore(inbox.getLeaseUntil())) return;
        if (receipts.findByChannelIdAndProviderTransactionId(inbox.getChannel().getId(), event.transactionId()).isPresent()) {
            finish(inbox, WebhookInboxStatus.PROCESSED);
            return;
        }
        var receipt = PaymentReceipt.builder().channel(inbox.getChannel()).inbox(inbox)
                .providerTransactionId(event.transactionId()).amount(event.amount()).creditedAt(event.creditedAt())
                .createdAt(clock.instant()).build();
        em.refresh(inbox.getChannel());
        if (intent == null || !intent.getChannel().getId().equals(inbox.getChannel().getId())
                || !Objects.equals(intent.getCode(), event.code())
                || !Objects.equals(event.accountNumber(), inbox.getChannel().getAccountNumber())
                || !inbox.getChannel().getUser().getId().equals(obligation.getCollector().getId())
                || inbox.getChannel().getOwnerRole() != obligation.getCollector().getRole()
                || !routes.matchesParties(obligation)
                // Bank timestamps have second precision; intent timestamps preserve microseconds.
                || event.creditedAt().isBefore(intent.getCreatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
            receipts.saveAndFlush(receipt);
            finish(inbox, WebhookInboxStatus.REVIEW_REQUIRED);
            return;
        }
        receipt.setIntent(intent);
        long remaining = obligation.getAmountDue() - obligation.getAmountSettled();
        boolean amountMismatch = obligation.getPurpose() != PaymentPurpose.BALANCE
                ? event.amount() != intent.getAmount() : event.amount() < remaining;
        if (amountMismatch) {
            receipts.saveAndFlush(receipt);
            finish(inbox, WebhookInboxStatus.REVIEW_REQUIRED);
            return;
        }
        if (intent.getStatus() == PaymentIntentStatus.ACTIVE && !event.creditedAt().isBefore(intent.getExpiresAt())) {
            intent.setStatus(PaymentIntentStatus.EXPIRED);
            handler.expire(intent.getId());
        }
        boolean applicable = intent.getStatus() == PaymentIntentStatus.ACTIVE
                && obligation.getStatus() == PaymentObligationStatus.OPEN && remaining > 0;
        if (applicable) {
            long allocated = Math.min(event.amount(), remaining);
            receipt.setAllocatedAmount(allocated);
            receipt.setStatus(allocated == event.amount() ? PaymentReceiptStatus.APPLIED : PaymentReceiptStatus.REFUND_REQUIRED);
            obligation.setAmountSettled(Math.addExact(obligation.getAmountSettled(), allocated));
            obligation.setUpdatedAt(clock.instant());
            if (obligation.getAmountSettled().equals(obligation.getAmountDue())) {
                obligation.setStatus(PaymentObligationStatus.SETTLED);
            }
            intent.setStatus(PaymentIntentStatus.RECEIVED);
        } else {
            receipt.setStatus(PaymentReceiptStatus.REFUND_REQUIRED);
        }
        receipts.saveAndFlush(receipt);
        handler.apply(receipt.getId());
        finish(inbox, WebhookInboxStatus.PROCESSED);
    }

    @Transactional
    public void expire(Long intentId) {
        var route = routes.intent(intentId);
        var handler = routes.handler(route.subject());
        handler.lockSubject(route.subjectId());
        var obligation = locks.current(PaymentObligation.class, route.obligationId());
        var intent = locks.current(PaymentIntent.class, intentId);
        if (!intent.getObligation().getId().equals(obligation.getId())) throw new BusinessException("Đối tượng đã thay đổi.");
        if (intent.getStatus() != PaymentIntentStatus.ACTIVE || clock.instant().isBefore(intent.getExpiresAt())) return;
        intent.setStatus(PaymentIntentStatus.EXPIRED);
        obligation.setUpdatedAt(clock.instant());
        handler.expire(intentId);
    }

    private void finish(WebhookInbox inbox, WebhookInboxStatus status) {
        inbox.setStatus(status);
        inbox.setProcessedAt(clock.instant());
        inbox.setLeaseOwner(null);
        inbox.setLeaseUntil(null);
        inbox.setNextRetryAt(null);
        inbox.setLastError(null);
    }
}
