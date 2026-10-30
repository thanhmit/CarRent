package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.notification.NotificationService;
import com.carrent.user.Role;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Domain handlers decide the event/fee under their aggregate lock; this service guards the money. */
@Service
public class RefundService {
    private final EntityManager em;
    private final PaymentLocks locks;
    private final RefundRepository refunds;
    private final PaymentChannelRepository channels;
    private final NotificationService notifications;
    private final Clock clock;

    public RefundService(EntityManager em, PaymentLocks locks, RefundRepository refunds,
            PaymentChannelRepository channels, NotificationService notifications, Clock clock) {
        this.em = em; this.locks = locks; this.refunds = refunds; this.channels = channels;
        this.notifications = notifications; this.clock = clock;
    }

    @Transactional
    public Long request(String businessKey, Long receiptId, long amount, RefundReason reason) {
        var receipt = locks.current(PaymentReceipt.class, receiptId);
        if (reason == null || amount <= 0 || receipt.getIntent() == null
                || receipt.getStatus() == PaymentReceiptStatus.REVIEW_REQUIRED) throw invalid();
        var obligation = receipt.getIntent().getObligation();
        em.refresh(obligation);
        if (!key(reason, obligation.getSubjectId(), receiptId).equals(businessKey)) throw invalid();
        var existing = refunds.findByReceiptIdForUpdate(receiptId).stream()
                .map(r -> locks.current(Refund.class, r.getId())).toList();
        for (var refund : existing) {
            if (refund.getBusinessKey().equals(businessKey)) {
                if (refund.getAmount() != amount || refund.getReason() != reason) throw invalid();
                return refund.getId();
            }
        }
        boolean cancellation = cancellation(reason);
        boolean eligible = switch (reason) {
            case CUSTOMER_CANCEL, OWNER_CANCEL -> obligation.getPurpose() == PaymentPurpose.ADVANCE;
            case EXCESS_BALANCE -> obligation.getPurpose() == PaymentPurpose.BALANCE;
            case LATE_ADVANCE, SYSTEM_CONFIRMATION_DELAY -> obligation.getPurpose() == PaymentPurpose.ADVANCE;
            case LATE_SUBSCRIPTION, CANCELLED_SUBSCRIPTION, INCOMPATIBLE_SUBSCRIPTION ->
                    obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION;
            case DUPLICATE_PAYMENT -> true;
        };
        if (!eligible || !cancellation && receipt.getStatus() != PaymentReceiptStatus.REFUND_REQUIRED) throw invalid();
        long available = cancellation ? receipt.getAllocatedAmount() : receipt.getAmount() - receipt.getAllocatedAmount();
        long totalAvailable = receipt.getAmount();
        for (var refund : existing) {
            totalAvailable = Math.subtractExact(totalAvailable, refund.getAmount());
            if (cancellation(refund.getReason()) == cancellation) available = Math.subtractExact(available, refund.getAmount());
        }
        if (amount > available || amount > totalAvailable) throw invalid();
        em.refresh(receipt.getChannel());
        em.refresh(obligation.getCollector());
        em.refresh(obligation.getPayer());
        Role collectorRole = obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION ? Role.ADMIN : Role.OWNER;
        Role payerRole = obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION ? Role.OWNER : Role.CUSTOMER;
        if (!receipt.getChannel().getUser().getId().equals(obligation.getCollector().getId())
                || receipt.getChannel().getPurpose() != PaymentChannelPurpose.COLLECT
                || receipt.getChannel().getOwnerRole() != collectorRole
                || obligation.getCollector().getRole() != collectorRole || obligation.getPayer().getRole() != payerRole) throw invalid();
        var candidates = channels.findByUserIdAndPurpose(obligation.getCollector().getId(), PaymentChannelPurpose.PAYOUT)
                .stream().filter(c -> c.getProvider() == PaymentProvider.PAYOS).toList();
        if (candidates.size() != 1) throw new BusinessException("Chưa xác định được kênh hoàn tiền duy nhất của bên thu tiền.");
        var channel = candidates.getFirst();
        em.refresh(channel);
        if (channel.getProvider() != PaymentProvider.PAYOS || channel.getPurpose() != PaymentChannelPurpose.PAYOUT
                || channel.getOwnerRole() != collectorRole
                || !channel.getUser().getId().equals(obligation.getCollector().getId())) throw invalid();
        var refund = refunds.saveAndFlush(Refund.builder().businessKey(businessKey).receipt(receipt)
                .payoutChannel(channel).recipient(obligation.getPayer()).amount(amount).reason(reason)
                .createdAt(clock.instant()).updatedAt(clock.instant()).build());
        notifications.notify(refund.getRecipient().getId(), "REFUND_WAITING_RECIPIENT", "Bổ sung tài khoản nhận hoàn tiền",
                "Bạn có khoản hoàn " + amount + " VNĐ. Vui lòng kiểm tra và xác nhận tài khoản nhận hoàn tiền.", "REFUND", refund.getId());
        return refund.getId();
    }

    private static String key(RefundReason reason, Long subjectId, Long receiptId) {
        return switch (reason) {
            case CUSTOMER_CANCEL, OWNER_CANCEL -> reason.name() + ":" + subjectId + ":" + receiptId;
            case DUPLICATE_PAYMENT -> "DUPLICATE:" + receiptId;
            case EXCESS_BALANCE -> "EXCESS:" + receiptId;
            default -> reason.name() + ":" + receiptId;
        };
    }

    private static boolean cancellation(RefundReason reason) {
        return reason == RefundReason.CUSTOMER_CANCEL || reason == RefundReason.OWNER_CANCEL;
    }

    private static BusinessException invalid() { return new BusinessException("Yêu cầu hoàn tiền không hợp lệ hoặc vượt số tiền được hoàn."); }
}
