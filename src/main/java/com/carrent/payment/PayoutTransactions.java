package com.carrent.payment;

import com.carrent.common.exception.NotFoundException;
import com.carrent.notification.NotificationService;
import com.carrent.payment.provider.PayoutCommand;
import com.carrent.payment.provider.PayoutResult;
import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.TransferOutcome;
import com.carrent.user.Role;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED)
public class PayoutTransactions {
    public record Claim(Long receiptId, Long refundId, Long attemptId, String leaseOwner,
                        Long channelId, String credentialReference, String providerPayoutId, PayoutCommand command) { }
    private final EntityManager em;
    private final PaymentLocks locks;
    private final RefundRepository refunds;
    private final PayoutAttemptRepository attempts;
    private final ProviderPaymentProperties providers;
    private final NotificationService notifications;
    private final Clock clock;

    public PayoutTransactions(EntityManager em, PaymentLocks locks, RefundRepository refunds,
            PayoutAttemptRepository attempts, ProviderPaymentProperties providers, NotificationService notifications, Clock clock) {
        this.em=em; this.locks=locks; this.refunds=refunds; this.attempts=attempts;
        this.providers=providers; this.notifications=notifications; this.clock=clock;
    }

    public Claim prepareDispatch(Long id) {
        var refund=lockRefund(id);
        if((refund.getStatus()!=RefundStatus.READY && refund.getStatus()!=RefundStatus.WAITING_FUNDS) || busy(refund)
                || refund.getNextRetryAt()!=null && clock.instant().isBefore(refund.getNextRetryAt())
                || !sourceReady(refund) || !destinationReady(refund)) return null;
        var prior=attempts.findByRefundIdForUpdate(id);
        if(prior.stream().anyMatch(a -> a.getStatus()!=PayoutAttemptStatus.NOT_SENT || !retryable(a))) return null;
        if(!prior.isEmpty() && !frozenMatches(refund,prior.getLast())) {
            review(refund,prior.getLast()); return null;
        }
        int number=prior.isEmpty() ? 1 : Math.addExact(prior.getLast().getAttemptNo(),1);
        var now=clock.instant();
        var attempt=attempts.saveAndFlush(PayoutAttempt.builder().refund(refund).attemptNo(number)
                .payoutChannel(refund.getPayoutChannel()).referenceId("refund:"+id+":attempt:"+number)
                .idempotencyKey(UUID.randomUUID().toString()).amount(refund.getAmount())
                .bankBin(refund.getBankBin()).accountNumber(refund.getAccountNumber()).accountName(refund.getAccountName())
                .nextReconcileAt(now.plusSeconds(60)).createdAt(now).updatedAt(now).build());
        return claim(refund,attempt);
    }

    public boolean beginSend(Claim claim) {
        var refund=lockRefund(claim.refundId());
        var attempt=locks.current(PayoutAttempt.class,claim.attemptId());
        if(!owns(refund,claim) || attempt.getStatus()!=PayoutAttemptStatus.PREPARED
                || !sameSnapshot(refund,attempt,claim) || !sourceReady(refund)) return false;
        // A crash after this commit is ambiguous, even if HTTP has not started yet.
        attempt.setStatus(PayoutAttemptStatus.UNKNOWN);
        attempt.setSubmittedAt(clock.instant());
        attempt.setNextReconcileAt(clock.instant().plusSeconds(30));
        attempt.setUpdatedAt(clock.instant());
        return true;
    }

    public Claim prepareReconcile(Long id) {
        var found=id==null ? null : attempts.findById(id).orElse(null);
        if(found==null) throw new NotFoundException("Không tìm thấy lệnh hoàn tiền.");
        var refund=lockRefund(found.getRefund().getId());
        var attempt=locks.current(PayoutAttempt.class,id);
        if(refund.getStatus()==RefundStatus.SUCCEEDED || refund.getStatus()==RefundStatus.REVIEW_REQUIRED || busy(refund)
                || attempt.getStatus()==PayoutAttemptStatus.SUCCEEDED || attempt.getStatus()==PayoutAttemptStatus.NOT_SENT
                || attempt.getNextReconcileAt()!=null && clock.instant().isBefore(attempt.getNextReconcileAt())
                || !sourceReady(refund)) return null;
        if(attempts.findByRefundIdForUpdate(refund.getId()).stream()
                .filter(a -> a.getStatus()!=PayoutAttemptStatus.SUCCEEDED && a.getStatus()!=PayoutAttemptStatus.NOT_SENT).count()!=1) {
            review(refund,attempt); return null;
        }
        return claim(refund,attempt);
    }

    public void finish(Claim claim,PayoutResult result) {
        var refund=lockRefund(claim.refundId());
        var attempt=locks.current(PayoutAttempt.class,claim.attemptId());
        if(!owns(refund,claim) || attempt.getStatus()==PayoutAttemptStatus.SUCCEEDED
                || attempt.getStatus()==PayoutAttemptStatus.NOT_SENT) return;
        if(!sameSnapshot(refund,attempt,claim)) { review(refund,attempt); return; }
        boolean matches=sourceReady(refund)
                && result!=null && Objects.equals(result.referenceId(),attempt.getReferenceId())
                && Objects.equals(result.bankBin(),attempt.getBankBin())
                && Objects.equals(result.accountNumber(),attempt.getAccountNumber()) && result.amount()==attempt.getAmount()
                && validProviderId(result.providerPayoutId())
                && (attempt.getProviderPayoutId()==null || attempt.getProviderPayoutId().equals(result.providerPayoutId()));
        if(matches) attempt.setProviderPayoutId(result.providerPayoutId());
        if(matches && result.outcome()==TransferOutcome.SUCCEEDED) {
            attempt.setStatus(PayoutAttemptStatus.SUCCEEDED);
            attempt.setResolvedAt(clock.instant());
            attempt.setNextReconcileAt(null);
            attempt.setLastError(null);
            refund.setStatus(RefundStatus.SUCCEEDED);
            refund.setNextRetryAt(null);
            notifications.notify(refund.getRecipient().getId(),"REFUND_SUCCEEDED","Hoàn tiền thành công",
                    "Nhà cung cấp đã xác nhận hoàn "+refund.getAmount()+" VNĐ thành công.","REFUND",refund.getId());
        } else if(matches && result.outcome()==TransferOutcome.NOT_SENT) {
            attempt.setStatus(PayoutAttemptStatus.NOT_SENT);
            attempt.setResolvedAt(clock.instant());
            attempt.setNextReconcileAt(null);
            var reason=result.notSentReason();
            boolean funds=reason==PayoutResult.NotSentReason.INSUFFICIENT_FUNDS;
            boolean retry=funds || reason==PayoutResult.NotSentReason.RETRYABLE;
            attempt.setLastError(funds ? "INSUFFICIENT_FUNDS" : retry ? "RETRYABLE_NOT_SENT" : "NOT_SENT_REVIEW");
            refund.setStatus(funds ? RefundStatus.WAITING_FUNDS : retry ? RefundStatus.READY : RefundStatus.REVIEW_REQUIRED);
            refund.setNextRetryAt(retry ? clock.instant().plusSeconds(30) : null);
            if(funds && attempts.findByRefundIdForUpdate(refund.getId()).stream()
                    .noneMatch(a -> !a.getId().equals(attempt.getId()) && "INSUFFICIENT_FUNDS".equals(a.getLastError()))) {
                notifications.notify(refund.getPayoutChannel().getUser().getId(),"REFUND_WAITING_FUNDS","Bổ sung số dư hoàn tiền",
                        "Vui lòng bổ sung số dư kênh chi của bạn để xử lý khoản hoàn "+refund.getAmount()+" VNĐ.","REFUND",refund.getId());
            }
        } else if(matches && result.outcome()==TransferOutcome.PROCESSING) {
            attempt.setStatus(PayoutAttemptStatus.PROCESSING);
            attempt.setLastError(null);
            attempt.setNextReconcileAt(clock.instant().plusSeconds(30));
        } else {
            long delay=attempt.getLastError()==null || attempt.getNextReconcileAt()==null ? 30 : Math.min(300,Math.max(30,
                    java.time.Duration.between(attempt.getUpdatedAt(),attempt.getNextReconcileAt()).getSeconds())*2);
            attempt.setStatus(PayoutAttemptStatus.UNKNOWN);
            attempt.setLastError("PROVIDER_UNCONFIRMED");
            attempt.setNextReconcileAt(clock.instant().plusSeconds(delay));
        }
        attempt.setUpdatedAt(clock.instant());
        refund.setUpdatedAt(clock.instant());
        refund.setLeaseOwner(null); refund.setLeaseUntil(null);
    }

    private Refund lockRefund(Long id) {
        var found=id==null ? null : refunds.findById(id).orElse(null);
        if(found==null) throw new NotFoundException("Không tìm thấy khoản hoàn tiền.");
        Long receiptId=found.getReceipt().getId();
        locks.current(PaymentReceipt.class,receiptId);
        return locks.current(Refund.class,id);
    }

    private boolean sourceReady(Refund refund) {
        var receipt=refund.getReceipt(); em.refresh(receipt);
        if(receipt.getIntent()==null || receipt.getStatus()==PaymentReceiptStatus.REVIEW_REQUIRED) return false;
        var obligation=receipt.getIntent().getObligation(); em.refresh(obligation);
        var channel=refund.getPayoutChannel(); em.refresh(channel);
        em.refresh(receipt.getChannel()); em.refresh(obligation.getCollector()); em.refresh(obligation.getPayer());
        Role role=obligation.getPurpose()==PaymentPurpose.SUBSCRIPTION ? Role.ADMIN : Role.OWNER;
        Role payerRole=obligation.getPurpose()==PaymentPurpose.SUBSCRIPTION ? Role.OWNER : Role.CUSTOMER;
        var configured=providers.payos().get(channel.getCredentialReference());
        return channel.isEnabled() && channel.getProvider()==PaymentProvider.PAYOS
                && channel.getPurpose()==PaymentChannelPurpose.PAYOUT && channel.getOwnerRole()==role
                && channel.getUser().getId().equals(obligation.getCollector().getId())
                && receipt.getChannel().getUser().getId().equals(obligation.getCollector().getId())
                && receipt.getChannel().getPurpose()==PaymentChannelPurpose.COLLECT
                && obligation.getCollector().getRole()==role && obligation.getPayer().getRole()==payerRole
                && refund.getRecipient().getId().equals(obligation.getPayer().getId())
                && refund.getAmount()>0 && refund.getAmount()<=receipt.getAmount()
                && configured!=null && configured.enabled();
    }

    private boolean destinationReady(Refund refund) {
        return refund.getReadyAt()!=null && refund.getDueAt()!=null
                && refund.getDueAt().equals(refund.getReadyAt().plusSeconds(3600))
                && refund.getBankBin()!=null && refund.getBankBin().matches("[0-9]{6}")
                && refund.getAccountNumber()!=null && refund.getAccountNumber().matches("[0-9]{1,50}")
                && refund.getAccountName()!=null && !refund.getAccountName().isBlank();
    }

    private Claim claim(Refund refund,PayoutAttempt attempt) {
        if(!destinationReady(refund) || !frozenMatches(refund,attempt)) { review(refund,attempt); return null; }
        refund.setStatus(RefundStatus.PROCESSING);
        refund.setLeaseOwner(UUID.randomUUID().toString()); refund.setLeaseUntil(clock.instant().plusSeconds(60));
        refund.setNextRetryAt(null); refund.setUpdatedAt(clock.instant());
        return new Claim(refund.getReceipt().getId(),refund.getId(),attempt.getId(),refund.getLeaseOwner(),
                attempt.getPayoutChannel().getId(),attempt.getPayoutChannel().getCredentialReference(),attempt.getProviderPayoutId(),
                new PayoutCommand(attempt.getReferenceId(),attempt.getIdempotencyKey(),attempt.getBankBin(),
                        attempt.getAccountNumber(),attempt.getAmount(),"Hoan tien "+refund.getId()));
    }

    private boolean busy(Refund refund) { return refund.getLeaseUntil()!=null && clock.instant().isBefore(refund.getLeaseUntil()); }
    private boolean owns(Refund refund,Claim claim) {
        return Objects.equals(refund.getLeaseOwner(),claim.leaseOwner()) && busy(refund)
                && refund.getStatus()==RefundStatus.PROCESSING && refund.getReceipt().getId().equals(claim.receiptId());
    }
    private boolean sameSnapshot(Refund refund,PayoutAttempt attempt,Claim claim) {
        em.refresh(refund.getPayoutChannel());
        return frozenMatches(refund,attempt) && attempt.getId().equals(claim.attemptId())
                && attempt.getPayoutChannel().getId().equals(claim.channelId())
                && Objects.equals(refund.getPayoutChannel().getCredentialReference(),claim.credentialReference())
                && Objects.equals(attempt.getProviderPayoutId(),claim.providerPayoutId())
                && attempt.getReferenceId().equals(claim.command().referenceId())
                && attempt.getIdempotencyKey().equals(claim.command().idempotencyKey())
                && attempt.getAmount()==claim.command().amount()
                && attempt.getBankBin().equals(claim.command().bankBin())
                && attempt.getAccountNumber().equals(claim.command().accountNumber());
    }
    private boolean frozenMatches(Refund refund,PayoutAttempt attempt) {
        return attempt.getRefund().getId().equals(refund.getId()) && attempt.getAmount().equals(refund.getAmount())
                && Objects.equals(attempt.getBankBin(),refund.getBankBin())
                && Objects.equals(attempt.getAccountNumber(),refund.getAccountNumber())
                && Objects.equals(attempt.getAccountName(),refund.getAccountName())
                && attempt.getPayoutChannel().getId().equals(refund.getPayoutChannel().getId());
    }
    private void review(Refund refund,PayoutAttempt attempt) {
        refund.setStatus(RefundStatus.REVIEW_REQUIRED); refund.setNextRetryAt(null);
        refund.setLeaseOwner(null); refund.setLeaseUntil(null); refund.setUpdatedAt(clock.instant());
        attempt.setLastError("SNAPSHOT_MISMATCH"); attempt.setNextReconcileAt(null); attempt.setUpdatedAt(clock.instant());
    }
    private static boolean validProviderId(String id) {
        return id!=null && !id.isBlank() && id.length()<=100 && id.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean retryable(PayoutAttempt attempt) {
        return "INSUFFICIENT_FUNDS".equals(attempt.getLastError()) || "RETRYABLE_NOT_SENT".equals(attempt.getLastError());
    }
}
