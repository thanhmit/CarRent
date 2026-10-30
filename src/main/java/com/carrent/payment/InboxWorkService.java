package com.carrent.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboxWorkService {
    public record Claim(Long inboxId, String leaseOwner, Long intentId) { }
    private final PaymentLocks locks;
    private final PaymentIntentRepository intents;
    private final Clock clock;

    public InboxWorkService(PaymentLocks locks, PaymentIntentRepository intents, Clock clock) {
        this.locks = locks; this.intents = intents; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(Long id) {
        var inbox = locks.current(WebhookInbox.class, id);
        Instant now = clock.instant();
        if (inbox.getStatus() == WebhookInboxStatus.PROCESSED || inbox.getStatus() == WebhookInboxStatus.REVIEW_REQUIRED
                || inbox.getNextRetryAt() != null && now.isBefore(inbox.getNextRetryAt())
                || inbox.getLeaseUntil() != null && now.isBefore(inbox.getLeaseUntil())) return null;
        var event = SePayIncomingPayment.parse(inbox.getRawPayload());
        Long intentId = event.code() == null ? null : intents.findByCode(event.code())
                .filter(i -> i.getChannel().getId().equals(inbox.getChannel().getId()))
                .map(PaymentIntent::getId).orElse(null);
        String token = UUID.randomUUID().toString();
        inbox.setStatus(WebhookInboxStatus.PROCESSING);
        inbox.setLeaseOwner(token);
        inbox.setLeaseUntil(now.plusSeconds(60));
        inbox.setNextRetryAt(null);
        return new Claim(id, token, intentId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(Claim claim, String reason) {
        var inbox = locks.current(WebhookInbox.class, claim.inboxId());
        if (!Objects.equals(claim.leaseOwner(), inbox.getLeaseOwner())
                || inbox.getStatus() != WebhookInboxStatus.PROCESSING) return;
        inbox.setStatus(WebhookInboxStatus.RECEIVED);
        inbox.setLeaseOwner(null);
        inbox.setLeaseUntil(null);
        inbox.setRetryCount(Math.min(inbox.getRetryCount(), 1_000_000) + 1);
        inbox.setNextRetryAt(clock.instant().plusSeconds(Math.min(300, 30L << Math.min(4, inbox.getRetryCount() - 1))));
        inbox.setLastError(switch (reason) {
            case "DOMAIN_UNAVAILABLE", "QUEUE_UNAVAILABLE" -> reason;
            default -> "APPLY_FAILED";
        });
    }
}
