package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import com.carrent.payment.provider.ProviderPaymentProperties;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.carrent.booking.Booking;
import jakarta.persistence.LockModeType;

@Service
public class PaymentIntentTransactions {
    private final EntityManager em;
    private final PaymentLocks locks;
    private final PaymentRoutes routes;
    private final PaymentIntentRepository intents;
    private final PaymentChannelRepository channels;
    private final PaymentReceiptRepository receipts;
    private final RefundRepository refunds;
    private final Clock clock;
    private final ProviderPaymentProperties providers;

    public PaymentIntentTransactions(EntityManager em, PaymentLocks locks, PaymentRoutes routes,
            PaymentIntentRepository intents, PaymentChannelRepository channels, PaymentReceiptRepository receipts,
            RefundRepository refunds, Clock clock, ProviderPaymentProperties providers) {
        this.em = em; this.locks = locks; this.routes = routes; this.intents = intents;
        this.channels = channels; this.receipts = receipts; this.refunds = refunds; this.clock = clock;
        this.providers = providers;
    }

    @Transactional
    public PaymentIntentView open(PaymentRoutes.Route route, CustomUserPrincipal actor) {
        var handler = routes.handler(route.subject());
        handler.lockSubject(route.subjectId());
        return openLocked(route, actor);
    }

    /** Booking acceptance owns the aggregate locks and must commit the report and intent together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentIntentView openForLockedBooking(PaymentRoutes.Route route, CustomUserPrincipal actor) {
        routes.handler(route.subject());
        var booking = em.find(Booking.class, route.subjectId());
        if (route.subject() != PaymentSubject.BOOKING_ADVANCE || booking == null
                || !booking.getCar().getId().equals(route.carId())
                || em.getLockMode(booking) != LockModeType.PESSIMISTIC_WRITE
                || em.getLockMode(booking.getCar()) != LockModeType.PESSIMISTIC_WRITE) {
            throw new IllegalStateException("Lock Car and Booking before opening an advance");
        }
        return openLocked(route, actor);
    }

    private PaymentIntentView openLocked(PaymentRoutes.Route route, CustomUserPrincipal actor) {
        var obligation = lockedObligation(route);
        requirePayer(obligation, actor);
        if (!routes.matchesParties(obligation)) throw new BusinessException("Chủ thể thanh toán không khớp đơn thuê.");
        Instant now = clock.instant();
        if (obligation.getStatus() != PaymentObligationStatus.OPEN
                || obligation.getAmountSettled() >= obligation.getAmountDue()
                || obligation.getPurpose() != PaymentPurpose.BALANCE
                    && obligation.getDueAt() != null && !now.isBefore(obligation.getDueAt())) {
            throw new BusinessException("Nghĩa vụ thanh toán đã kết thúc hoặc hết hạn.");
        }
        var active = intents.findByObligationIdAndStatus(obligation.getId(), PaymentIntentStatus.ACTIVE);
        if (active.size() > 1) throw new BusinessException("Dữ liệu thanh toán cần được kiểm tra.");
        if (!active.isEmpty()) {
            var current = locks.current(PaymentIntent.class, active.getFirst().getId());
            if (!now.isBefore(current.getExpiresAt())) {
                if (obligation.getPurpose() != PaymentPurpose.BALANCE) {
                    throw new BusinessException("Lần thanh toán đã hết hạn, đang chờ xử lý kết thúc.");
                }
                current.setStatus(PaymentIntentStatus.EXPIRED);
                routes.handler(route.subject()).expire(current.getId());
            } else {
                return view(current);
            }
        }
        var candidates = channels.findByUserIdAndPurposeAndEnabledTrue(obligation.getCollector().getId(),
                PaymentChannelPurpose.COLLECT).stream().filter(c -> c.getProvider() == PaymentProvider.SEPAY).toList();
        if (candidates.size() != 1) throw new BusinessException("Chưa xác định được kênh nhận tiền duy nhất.");
        var channel = candidates.getFirst();
        em.refresh(channel);
        var provider = providers.sepay().get(channel.getCredentialReference());
        if (!channel.isEnabled() || !channel.getUser().getId().equals(obligation.getCollector().getId())
                || provider == null || !provider.enabled()
                || channel.getOwnerRole() != obligation.getCollector().getRole()
                || channel.getBankBin() == null || !channel.getBankBin().matches("[0-9]{6}")
                || channel.getAccountNumber() == null || !channel.getAccountNumber().matches("[0-9]{1,50}")) {
            throw new BusinessException("Kênh nhận tiền chưa hợp lệ.");
        }
        Instant expiry = now.plusSeconds(900);
        if (obligation.getPurpose() != PaymentPurpose.BALANCE
                && obligation.getDueAt() != null && obligation.getDueAt().isBefore(expiry)) expiry = obligation.getDueAt();
        var intent = intents.saveAndFlush(PaymentIntent.builder().obligation(obligation).channel(channel)
                .code("CR" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT))
                .amount(obligation.getAmountDue() - obligation.getAmountSettled())
                .createdAt(now).expiresAt(expiry).build());
        return view(intent);
    }

    @Transactional
    public void cancel(PaymentRoutes.Route route, Long intentId, CustomUserPrincipal actor) {
        var handler = routes.handler(route.subject());
        handler.lockSubject(route.subjectId());
        var obligation = lockedObligation(route);
        requirePayer(obligation, actor);
        if (!routes.matchesParties(obligation)) throw new BusinessException("Chủ thể thanh toán không khớp đơn thuê.");
        var intent = locks.current(PaymentIntent.class, intentId);
        if (!intent.getObligation().getId().equals(obligation.getId())) throw new NotFoundException("Không tìm thấy giao dịch.");
        if (intent.getStatus() != PaymentIntentStatus.ACTIVE) return;
        // Explicit advance cancellation ends the unpaid booking even after the QR deadline.
        if (!clock.instant().isBefore(intent.getExpiresAt()) && route.subject() != PaymentSubject.BOOKING_ADVANCE) {
            intent.setStatus(PaymentIntentStatus.EXPIRED);
            handler.expire(intentId);
        } else {
            intent.setStatus(PaymentIntentStatus.CANCELLED);
            intent.setCancelledAt(clock.instant());
            handler.cancel(intentId);
        }
        obligation.setUpdatedAt(clock.instant());
    }

    @Transactional(readOnly = true)
    public PaymentIntentView status(Long id, CustomUserPrincipal actor) {
        var intent = em.find(PaymentIntent.class, id);
        if (intent == null) throw new NotFoundException("Không tìm thấy lần thanh toán.");
        em.refresh(intent);
        em.refresh(intent.getObligation());
        requirePayer(intent.getObligation(), actor);
        return view(intent);
    }

    private PaymentObligation lockedObligation(PaymentRoutes.Route route) {
        var obligation = locks.current(PaymentObligation.class, route.obligationId());
        if (PaymentSubject.of(obligation) != route.subject() || !obligation.getSubjectId().equals(route.subjectId())) {
            throw new BusinessException("Đối tượng thanh toán đã thay đổi.");
        }
        return obligation;
    }

    private void requirePayer(PaymentObligation obligation, CustomUserPrincipal actor) {
        if (actor == null || !obligation.getPayer().getId().equals(actor.getId())) {
            throw new ForbiddenException("Bạn không có quyền thao tác thanh toán này.");
        }
        var user = em.find(User.class, actor.getId());
        boolean currentRental = (obligation.getPurpose() == PaymentPurpose.ADVANCE || obligation.getPurpose() == PaymentPurpose.BALANCE)
                && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (currentRental) em.refresh(user, LockModeType.PESSIMISTIC_READ);
        else em.refresh(user);
        Role payerRole = obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION ? Role.OWNER : Role.CUSTOMER;
        Role collectorRole = obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION ? Role.ADMIN : Role.OWNER;
        if (currentRental) em.refresh(obligation.getCollector(), LockModeType.PESSIMISTIC_READ);
        else em.refresh(obligation.getCollector());
        if (user.getStatus() != UserStatus.ACTIVE || user.getRole() != payerRole || actor.getRole() != payerRole
                || obligation.getCollector().getRole() != collectorRole) {
            throw new ForbiddenException("Tài khoản không có quyền thanh toán này.");
        }
    }

    // Shared with scoped finance reads so lists and checkout describe the same committed money state.
    PaymentIntentView view(PaymentIntent intent) {
        String status = intent.getStatus().name();
        if (intent.getStatus() == PaymentIntentStatus.ACTIVE && !clock.instant().isBefore(intent.getExpiresAt())) {
            status = "EXPIRED";
        }
        em.refresh(intent.getChannel());
        var configured = providers.sepay().get(intent.getChannel().getCredentialReference());
        if ("ACTIVE".equals(status) && (!routes.available(PaymentSubject.of(intent.getObligation()))
                || !intent.getChannel().isEnabled() || configured == null || !configured.enabled())) {
            status = "UNAVAILABLE";
        }
        var received = receipts.findByIntentId(intent.getId());
        boolean hasRefund = false, refundPending = false, confirmationDelayed = false;
        for (var receipt : received) {
            var requested = refunds.findByReceiptId(receipt.getId());
            confirmationDelayed |= requested.stream().anyMatch(r -> r.getReason() == RefundReason.SYSTEM_CONFIRMATION_DELAY);
            boolean unallocated = receipt.getStatus() == PaymentReceiptStatus.REFUND_REQUIRED;
            if (!unallocated && requested.isEmpty()) continue;
            hasRefund = true;
            long succeeded = requested.stream().filter(r -> r.getStatus() == RefundStatus.SUCCEEDED)
                    .mapToLong(Refund::getAmount).sum();
            refundPending |= requested.stream().anyMatch(r -> r.getStatus() != RefundStatus.SUCCEEDED)
                    || unallocated && succeeded < receipt.getAmount() - receipt.getAllocatedAmount();
        }
        if (hasRefund) {
            status = refundPending ? "REFUND_PENDING" : "REFUNDED";
        } else if (received.stream()
                .anyMatch(r -> r.getStatus() == PaymentReceiptStatus.REVIEW_REQUIRED)) {
            status = "REVIEW_REQUIRED";
        }
        PaymentIntentView.Rental rental = null;
        if (intent.getObligation().getPurpose() == PaymentPurpose.ADVANCE) {
            var booking = em.find(Booking.class, intent.getObligation().getSubjectId());
            if (booking != null && "VIETQR_V1".equals(booking.getWorkflowVersion())) {
                boolean cancelled = booking.getStatus() == com.carrent.booking.BookingStatus.CANCELLED;
                boolean pending = booking.getStatus() == com.carrent.booking.BookingStatus.PENDING;
                rental = new PaymentIntentView.Rental(booking.getId(), booking.getAdvanceAmount(), booking.getBalanceAmount(),
                        cancelled, pending && booking.getAdvancePaidAt() == null && booking.getVehicleReceivedAt() == null, confirmationDelayed);
                if ("ACTIVE".equals(status) && !pending) status = cancelled ? "CANCELLED" : "UNAVAILABLE";
            }
        }
        if (intent.getObligation().getPurpose() == PaymentPurpose.BALANCE) {
            var obligation = intent.getObligation();
            var booking = em.find(Booking.class, obligation.getSubjectId());
            if (booking != null && "VIETQR_V1".equals(booking.getWorkflowVersion())) {
                em.refresh(booking);
                long remaining = Math.max(0, obligation.getAmountDue() - obligation.getAmountSettled());
                rental = new PaymentIntentView.Rental(booking.getId(), booking.getAdvanceAmount(), booking.getBalanceAmount(),
                        false, intent.getStatus() == PaymentIntentStatus.ACTIVE && remaining > 0
                                && clock.instant().isBefore(intent.getExpiresAt()), false,
                        true, obligation.getId(), remaining, obligation.getDueAt(),
                        booking.getStatus() == com.carrent.booking.BookingStatus.COMPLETED);
                // Another QR may have settled the same debt while this attempt remains ACTIVE.
                if (remaining == 0 && !hasRefund && !"REVIEW_REQUIRED".equals(status)) status = "SETTLED";
            }
        }
        String qr = "ACTIVE".equals(status) ? "https://img.vietqr.io/image/" + intent.getChannel().getBankBin()
                + "-" + intent.getChannel().getAccountNumber() + "-compact2.png?amount=" + intent.getAmount()
                + "&addInfo=" + intent.getCode() : null;
        return new PaymentIntentView(intent.getId(), intent.getCode(), intent.getAmount(), intent.getExpiresAt(), status, qr, rental);
    }
}
