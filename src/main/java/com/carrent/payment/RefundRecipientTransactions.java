package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.RefundDestinationVerifier.Destination;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundRecipientTransactions {
    public record Check(Long refundId, Long receiptId, Long channelId, String credentialReference) { }
    private final EntityManager em;
    private final PaymentLocks locks;
    private final PayoutAttemptRepository attempts;
    private final ProviderPaymentProperties providers;
    private final Clock clock;
    private final RefundAccountRepository accounts;

    public RefundRecipientTransactions(EntityManager em, PaymentLocks locks, PayoutAttemptRepository attempts,
            ProviderPaymentProperties providers, Clock clock, RefundAccountRepository accounts) {
        this.em = em; this.locks = locks; this.attempts = attempts; this.providers = providers; this.clock = clock;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public RefundView view(Long id, CustomUserPrincipal actor) {
        var refund = find(id);
        requireRecipient(refund, actor);
        return new RefundView(id, refund.getAmount(), refund.getReason(), refund.getStatus(), refund.getBankBin(),
                refund.getAccountNumber(), refund.getAccountName(), refund.getReadyAt(), refund.getDueAt(), editable(refund, false),
                refund.getDueAt() != null && !clock.instant().isBefore(refund.getDueAt()) && refund.getStatus() != RefundStatus.SUCCEEDED);
    }

    @Transactional(readOnly = true)
    public Check prepare(Long id, CustomUserPrincipal actor) {
        var refund = find(id);
        requireRecipient(refund, actor);
        requireEditable(refund, false);
        requireChannel(refund);
        return new Check(id, refund.getReceipt().getId(), refund.getPayoutChannel().getId(),
                refund.getPayoutChannel().getCredentialReference());
    }

    @Transactional
    public void confirm(Check check, Destination destination, boolean saveForLater, CustomUserPrincipal actor) {
        locks.current(PaymentReceipt.class, check.receiptId());
        var refund = locks.current(Refund.class, check.refundId());
        requireRecipient(refund, actor);
        requireEditable(refund, true);
        requireChannel(refund);
        if (!refund.getReceipt().getId().equals(check.receiptId())
                || !refund.getPayoutChannel().getId().equals(check.channelId())
                || !Objects.equals(refund.getPayoutChannel().getCredentialReference(), check.credentialReference())) {
            throw new BusinessException("Thông tin hoàn tiền đã thay đổi. Vui lòng kiểm tra lại.");
        }
        refund.setBankBin(destination.bankBin());
        refund.setAccountNumber(destination.accountNumber());
        refund.setAccountName(destination.accountName());
        if (refund.getReadyAt() == null) {
            refund.setReadyAt(clock.instant());
            refund.setDueAt(refund.getReadyAt().plusSeconds(3600));
        }
        refund.setStatus(RefundStatus.READY);
        refund.setUpdatedAt(clock.instant());
        if (saveForLater) accounts.save(actor.getId(), destination, clock.instant());
    }

    private Refund find(Long id) {
        var refund = id == null ? null : em.find(Refund.class, id);
        if (refund == null) throw new NotFoundException("Không tìm thấy khoản hoàn tiền.");
        em.refresh(refund);
        return refund;
    }

    private void requireRecipient(Refund refund, CustomUserPrincipal actor) {
        if (actor == null || !refund.getRecipient().getId().equals(actor.getId())) throw forbidden();
        var user = em.find(User.class, actor.getId());
        em.refresh(user);
        var obligation = refund.getReceipt().getIntent() == null ? null : refund.getReceipt().getIntent().getObligation();
        if (obligation != null) em.refresh(obligation);
        Role role = obligation != null && obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION ? Role.OWNER : Role.CUSTOMER;
        if (obligation == null || !obligation.getPayer().getId().equals(user.getId())
                || user.getStatus() != UserStatus.ACTIVE || user.getRole() != role || actor.getRole() != role) throw forbidden();
    }

    private void requireChannel(Refund refund) {
        var channel = refund.getPayoutChannel();
        em.refresh(channel);
        var receipt = refund.getReceipt();
        em.refresh(receipt.getChannel());
        var obligation = receipt.getIntent().getObligation();
        em.refresh(obligation.getCollector());
        Role role = obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION ? Role.ADMIN : Role.OWNER;
        var configured = providers.payos().get(channel.getCredentialReference());
        if (!channel.isEnabled() || channel.getProvider() != PaymentProvider.PAYOS
                || channel.getPurpose() != PaymentChannelPurpose.PAYOUT || channel.getOwnerRole() != role
                || !channel.getUser().getId().equals(obligation.getCollector().getId())
                || !receipt.getChannel().getUser().getId().equals(obligation.getCollector().getId())
                || obligation.getCollector().getRole() != role || configured == null || !configured.enabled()) {
            throw new BusinessException("Kênh hoàn tiền của bên thu tiền chưa sẵn sàng.");
        }
    }

    private boolean editable(Refund refund, boolean current) {
        return (refund.getStatus() == RefundStatus.WAITING_RECIPIENT || refund.getStatus() == RefundStatus.READY)
                && refund.getLeaseOwner() == null && (current ? attempts.findByRefundIdForUpdate(refund.getId())
                        : attempts.findByRefundIdOrderByAttemptNoAsc(refund.getId())).isEmpty();
    }

    private void requireEditable(Refund refund, boolean current) {
        if (!editable(refund, current)) throw new BusinessException("Khoản hoàn đã bắt đầu xử lý, không thể đổi tài khoản nhận.");
    }

    private static ForbiddenException forbidden() { return new ForbiddenException("Bạn không có quyền thao tác khoản hoàn tiền này."); }
}
