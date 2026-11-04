package com.carrent.subscription;

import com.carrent.car.Car;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.notification.NotificationService;
import com.carrent.payment.*;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "carrent.subscription.enabled", havingValue = "true")
public class SubscriptionService {
    private final EntityManager em;
    private final SubscriptionOrderRepository orders;
    private final OwnerSubscriptionRepository subscriptions;
    private final PaymentObligationRepository obligations;
    private final PaymentIntentRepository intents;
    private final PaymentIntentTransactions transactions;
    private final RefundService refunds;
    private final NotificationService notifications;
    private final Clock clock;

    @Transactional(readOnly = true)
    public void requireOwner(CustomUserPrincipal actor) { owner(actor, false); }

    public record Overview(SubscriptionPlan plan, Instant validUntil, boolean active, long reserved,
                           long pending, long temporarilyHidden, int quota) { }

    @Transactional(readOnly = true)
    public Overview overview(CustomUserPrincipal actor) {
        var user = owner(actor, false);
        var current = em.find(OwnerSubscription.class, user.getId());
        boolean active = current != null && current.getValidUntil().isAfter(clock.instant());
        long reserved = em.createQuery("select count(c) from Car c where c.owner.id=:id and c.listingStatus=com.carrent.car.ListingStatus.ACTIVE and c.approvalStatus in (com.carrent.car.ApprovalStatus.PENDING,com.carrent.car.ApprovalStatus.APPROVED)", Long.class)
                .setParameter("id", user.getId()).getSingleResult();
        long pending = em.createQuery("select count(c) from Car c where c.owner.id=:id and c.listingStatus=com.carrent.car.ListingStatus.ACTIVE and c.approvalStatus=com.carrent.car.ApprovalStatus.PENDING", Long.class)
                .setParameter("id", user.getId()).getSingleResult();
        long hidden = active ? 0 : em.createQuery("select count(c) from Car c where c.owner.id=:id and c.listingStatus=com.carrent.car.ListingStatus.ACTIVE and c.approvalStatus=com.carrent.car.ApprovalStatus.APPROVED", Long.class)
                .setParameter("id", user.getId()).getSingleResult();
        return new Overview(current == null ? null : current.getPlanCode(), current == null ? null : current.getValidUntil(),
                active, reserved, pending, hidden, current == null ? 0 : current.getQuota());
    }

    @Transactional
    public SubscriptionOrderView createOrder(SubscriptionPlan plan, CustomUserPrincipal actor) {
        if (plan == null) throw new BusinessException("Vui lòng chọn gói hợp lệ.");
        var owner = owner(actor, true);
        var current = entitlement(owner.getId());
        if (current != null && current.getValidUntil().isAfter(clock.instant()) && current.getPlanCode() != plan) {
            throw new BusinessException("Gói còn hạn chỉ được gia hạn cùng loại.");
        }
        var pending = orders.pendingForUpdate(owner.getId());
        lockDowngradeCars(owner.getId(), current, plan.getQuota());
        for (var order : pending) {
            if (order.getExpiresAt().isAfter(clock.instant())) {
                if (order.getPlanCode() != plan) throw new BusinessException("Bạn đã có yêu cầu mua gói đang chờ thanh toán.");
                current(PaymentObligation.class, order.getObligation().getId());
                return view(order, actor);
            }
            end(order, false);
        }
        var now = clock.instant();
        var collectors = em.createQuery("select u from User u where u.role=:role and u.status=:status order by u.id", User.class)
                .setParameter("role", Role.ADMIN).setParameter("status", UserStatus.ACTIVE).getResultList();
        var configured = collectors.stream().filter(u -> em.createQuery(
                "select count(c) from PaymentChannel c where c.user.id=:id and c.purpose=:purpose and c.provider=:provider and c.enabled=true", Long.class)
                .setParameter("id", u.getId()).setParameter("purpose", PaymentChannelPurpose.COLLECT)
                .setParameter("provider", PaymentProvider.SEPAY).getSingleResult() > 0).toList();
        if (configured.size() != 1) throw new BusinessException("Chưa xác định được tài khoản thu Admin duy nhất.");
        var order = orders.saveAndFlush(SubscriptionOrder.builder().owner(owner).planCode(plan)
                .quotaSnapshot(plan.getQuota()).priceSnapshot(plan.getPrice()).durationDays(plan.getDurationDays())
                .createdAt(now).updatedAt(now).expiresAt(now.plusSeconds(900)).build());
        var obligation = obligations.saveAndFlush(PaymentObligation.builder().businessKey("SUBSCRIPTION:" + order.getId())
                .purpose(PaymentPurpose.SUBSCRIPTION).subjectType(PaymentSubjectType.SUBSCRIPTION_ORDER).subjectId(order.getId())
                .payer(owner).collector(configured.getFirst()).amountDue(order.getPriceSnapshot())
                .dueAt(order.getExpiresAt()).createdAt(now).updatedAt(now).build());
        order.setObligation(obligation);
        orders.flush();
        var payment = transactions.open(new PaymentRoutes.Route(obligation.getId(), PaymentSubject.SUBSCRIPTION, order.getId(), null), actor);
        return new SubscriptionOrderView(order.getId(), plan, order.getQuotaSnapshot(), order.getPriceSnapshot(),
                order.getDurationDays(), order.getStatus().name(), payment);
    }

    @Transactional(readOnly = true)
    public SubscriptionOrderView detail(Long id, CustomUserPrincipal actor) {
        owner(actor, false);
        var order = find(id);
        if (!order.getOwner().getId().equals(actor.getId())) throw forbidden();
        return view(order, actor);
    }

    @Transactional
    public void cancelOrder(Long id, CustomUserPrincipal actor) {
        var owner = owner(actor, true);
        var initial = find(id);
        if (!initial.getOwner().getId().equals(owner.getId())) throw forbidden();
        lockSubject(id);
        var order = find(id);
        if (order.getStatus() != SubscriptionOrderStatus.PENDING) return;
        var intent = intent(order);
        transactions.cancel(new PaymentRoutes.Route(order.getObligation().getId(), PaymentSubject.SUBSCRIPTION, id, null), intent.getId(), actor);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSubject(Long id) {
        var ownerId = find(id).getOwner().getId();
        current(User.class, ownerId);
        var entitlement = entitlement(ownerId);
        var order = current(SubscriptionOrder.class, id);
        if (!order.getOwner().getId().equals(ownerId)) throw new BusinessException("Chủ thể yêu cầu mua gói đã thay đổi.");
        lockDowngradeCars(ownerId, entitlement, order.getQuotaSnapshot());
    }

    private void lockDowngradeCars(Long ownerId, OwnerSubscription entitlement, int quota) {
        // Expiry can cross before apply; every possible downgrade takes Car locks before financial locks.
        if (entitlement != null && quota < entitlement.getQuota()) {
            em.createQuery("select c from Car c where c.owner.id=:id order by c.id", Car.class)
                    .setParameter("id", ownerId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(Long intentId) { end(forIntent(intentId), false); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Long intentId) { end(forIntent(intentId), true); }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean matchesObligation(PaymentObligation obligation) {
        var order = em.find(SubscriptionOrder.class, obligation.getSubjectId());
        if (order == null) return false;
        try { validate(order, obligation); return true; }
        catch (BusinessException invalid) { return false; }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyPayment(Long receiptId) {
        var receipt = em.find(PaymentReceipt.class, receiptId);
        if (receipt == null || receipt.getIntent() == null) throw new BusinessException("Thiếu giao dịch mua gói.");
        var intent = receipt.getIntent();
        var obligation = intent.getObligation();
        var order = find(obligation.getSubjectId());
        requireLocks(order, obligation, intent);
        validate(order, obligation);
        if (!Objects.equals(order.getPriceSnapshot(), receipt.getAmount())
                || !Objects.equals(intent.getAmount(), order.getPriceSnapshot())
                || !intent.getChannel().getId().equals(receipt.getChannel().getId())
                || !intent.getChannel().getUser().getId().equals(obligation.getCollector().getId())) {
            throw new BusinessException("Giao dịch mua gói cần đối soát.");
        }
        if (order.getAppliedAt() != null) {
            if (receipt.getAllocatedAmount() > 0 && receipt.getStatus() == PaymentReceiptStatus.APPLIED) return;
            refund(order, receipt, RefundReason.DUPLICATE_PAYMENT);
            return;
        }
        // lockSubject already loaded the current entitlement under the Owner mutex.
        var current = em.find(OwnerSubscription.class, order.getOwner().getId());
        RefundReason reason = order.getStatus() == SubscriptionOrderStatus.CANCELLED || obligation.getStatus() == PaymentObligationStatus.CANCELLED ? RefundReason.CANCELLED_SUBSCRIPTION
                : !receipt.getCreditedAt().isBefore(order.getExpiresAt()) ? RefundReason.LATE_SUBSCRIPTION
                : current != null && current.getValidUntil().isAfter(clock.instant()) && current.getPlanCode() != order.getPlanCode()
                    ? RefundReason.INCOMPATIBLE_SUBSCRIPTION : null;
        if (reason != null) { refund(order, receipt, reason); return; }
        if (order.getStatus() != SubscriptionOrderStatus.PENDING && order.getStatus() != SubscriptionOrderStatus.EXPIRED
                && order.getStatus() != SubscriptionOrderStatus.REFUND_PENDING) {
            throw new BusinessException("Yêu cầu mua gói không còn được áp dụng.");
        }
        // A timely bank credit remains eligible after the expiry worker has committed.
        receipt.setAllocatedAmount(receipt.getAmount());
        receipt.setStatus(PaymentReceiptStatus.APPLIED);
        obligation.setAmountSettled(receipt.getAmount());
        obligation.setStatus(PaymentObligationStatus.SETTLED);
        obligation.setUpdatedAt(clock.instant());
        intent.setStatus(PaymentIntentStatus.RECEIVED);
        order.setStatus(SubscriptionOrderStatus.APPLIED);
        order.setAppliedAt(clock.instant());
        order.setUpdatedAt(clock.instant());
        var now = clock.instant();
        var base = current != null && current.getValidUntil().isAfter(now) ? current.getValidUntil() : now;
        if (current != null && !current.getValidUntil().isAfter(now) && order.getQuotaSnapshot() < current.getQuota()) {
            // These rows were locked in ID order by lockSubject; update only intent, even with an older RR snapshot.
            em.createNativeQuery("UPDATE cars SET listing_status='INACTIVE' WHERE owner_id=:id")
                    .setParameter("id", order.getOwner().getId()).executeUpdate();
            // Re-read the already locked fleet with current-read semantics to refresh every managed Car.
            em.createQuery("select c from Car c where c.owner.id=:id", Car.class)
                    .setParameter("id", order.getOwner().getId()).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList().forEach(em::refresh);
        }
        if (current == null) current = OwnerSubscription.builder().ownerId(order.getOwner().getId()).createdAt(now).build();
        current.setPlanCode(order.getPlanCode()); current.setQuota(order.getQuotaSnapshot());
        if (current.getValidFrom() == null || !current.getValidUntil().isAfter(now)) current.setValidFrom(now);
        current.setValidUntil(base.plus(Duration.ofDays(order.getDurationDays())));
        current.setLastAppliedOrder(order); current.setUpdatedAt(now);
        subscriptions.saveAndFlush(current);
        notifications.notify(order.getOwner().getId(), "SUBSCRIPTION_APPLIED", "Đã kích hoạt gói Owner",
                "Gói " + order.getPlanCode() + " đã được áp dụng thêm " + order.getDurationDays() + " ngày.", "SUBSCRIPTION_ORDER", order.getId());
    }

    private void refund(SubscriptionOrder order, PaymentReceipt receipt, RefundReason reason) {
        var obligation = receipt.getIntent().getObligation();
        if (receipt.getAllocatedAmount() > 0) {
            obligation.setAmountSettled(Math.subtractExact(obligation.getAmountSettled(), receipt.getAllocatedAmount()));
            receipt.setAllocatedAmount(0L);
        }
        receipt.setStatus(PaymentReceiptStatus.REFUND_REQUIRED);
        if (order.getAppliedAt() == null) {
            order.setStatus(SubscriptionOrderStatus.REFUND_PENDING);
            order.setUpdatedAt(clock.instant());
            if (obligation.getStatus() != PaymentObligationStatus.CANCELLED) obligation.setStatus(PaymentObligationStatus.EXPIRED);
        }
        em.flush();
        refunds.request((reason == RefundReason.DUPLICATE_PAYMENT ? "DUPLICATE" : reason.name()) + ":" + receipt.getId(),
                receipt.getId(), receipt.getAmount(), reason);
    }

    private void end(SubscriptionOrder order, boolean cancel) {
        if (order.getStatus() != SubscriptionOrderStatus.PENDING) return;
        var obligation = current(PaymentObligation.class, order.getObligation().getId());
        var intent = current(PaymentIntent.class, intent(order).getId());
        order.setStatus(cancel ? SubscriptionOrderStatus.CANCELLED : SubscriptionOrderStatus.EXPIRED);
        order.setUpdatedAt(clock.instant());
        obligation.setStatus(cancel ? PaymentObligationStatus.CANCELLED : PaymentObligationStatus.EXPIRED);
        obligation.setUpdatedAt(clock.instant());
        if (intent.getStatus() == PaymentIntentStatus.ACTIVE) {
            intent.setStatus(cancel ? PaymentIntentStatus.CANCELLED : PaymentIntentStatus.EXPIRED);
            if (cancel) intent.setCancelledAt(clock.instant());
        }
    }

    private SubscriptionOrder forIntent(Long id) {
        var intent = em.find(PaymentIntent.class, id);
        if (intent == null || intent.getObligation().getSubjectType() != PaymentSubjectType.SUBSCRIPTION_ORDER) throw new NotFoundException("Không tìm thấy yêu cầu mua gói.");
        var order = find(intent.getObligation().getSubjectId());
        requireLocks(order, intent.getObligation(), intent);
        validate(order, intent.getObligation());
        return order;
    }

    private void requireLocks(SubscriptionOrder order, PaymentObligation obligation, PaymentIntent intent) {
        for (Object row : List.of(order, order.getOwner(), obligation, intent)) {
            var mode = em.getLockMode(row);
            if (mode != LockModeType.PESSIMISTIC_WRITE && mode != LockModeType.PESSIMISTIC_FORCE_INCREMENT) {
                throw new IllegalStateException("Lock Owner, order and payment before subscription mutation");
            }
        }
    }

    private void validate(SubscriptionOrder order, PaymentObligation obligation) {
        if (order.getObligation() == null || !order.getObligation().getId().equals(obligation.getId())
                || obligation.getPurpose() != PaymentPurpose.SUBSCRIPTION || obligation.getSubjectType() != PaymentSubjectType.SUBSCRIPTION_ORDER
                || !obligation.getSubjectId().equals(order.getId()) || !("SUBSCRIPTION:" + order.getId()).equals(obligation.getBusinessKey())
                || !obligation.getPayer().getId().equals(order.getOwner().getId()) || !obligation.getAmountDue().equals(order.getPriceSnapshot())
                || obligation.getCollector().getRole() != Role.ADMIN || obligation.getPayer().getRole() != Role.OWNER
                || !Objects.equals(obligation.getDueAt(), order.getExpiresAt())) throw new BusinessException("Nghĩa vụ thanh toán không khớp yêu cầu mua gói.");
    }

    private SubscriptionOrderView view(SubscriptionOrder order, CustomUserPrincipal actor) {
        var status = order.getStatus() == SubscriptionOrderStatus.PENDING && !clock.instant().isBefore(order.getExpiresAt())
                ? "EXPIRED" : order.getStatus().name();
        return new SubscriptionOrderView(order.getId(), order.getPlanCode(), order.getQuotaSnapshot(), order.getPriceSnapshot(),
                order.getDurationDays(), status, transactions.status(intent(order).getId(), actor));
    }

    private PaymentIntent intent(SubscriptionOrder order) {
        var query = em.createQuery("select i from PaymentIntent i where i.obligation.id=:id order by i.id desc", PaymentIntent.class)
                .setParameter("id", order.getObligation().getId()).setMaxResults(1);
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return query.getResultStream().findFirst().orElseThrow(() -> new BusinessException("Yêu cầu mua gói chưa có lần thanh toán."));
    }

    private User owner(CustomUserPrincipal actor, boolean lock) {
        if (actor == null || actor.getRole() != Role.OWNER) throw forbidden();
        var user = lock ? current(User.class, actor.getId()) : em.find(User.class, actor.getId());
        if (user == null) throw forbidden();
        if (!lock) em.refresh(user);
        if (user.getRole() != Role.OWNER || user.getStatus() != UserStatus.ACTIVE) throw forbidden();
        return user;
    }

    private OwnerSubscription entitlement(Long ownerId) {
        var existing = em.getReference(OwnerSubscription.class, ownerId);
        var mode = em.getLockMode(existing);
        if (mode == LockModeType.PESSIMISTIC_WRITE || mode == LockModeType.PESSIMISTIC_FORCE_INCREMENT) return existing;
        // Evict an unlocked managed snapshot before the locking current read, including a previously absent row.
        em.detach(existing);
        return em.find(OwnerSubscription.class, ownerId, LockModeType.PESSIMISTIC_WRITE);
    }

    private SubscriptionOrder find(Long id) {
        var order = id == null ? null : em.find(SubscriptionOrder.class, id);
        if (order == null) throw new NotFoundException("Không tìm thấy yêu cầu mua gói.");
        return order;
    }

    private <T> T current(Class<T> type, Long id) {
        var existing = em.getReference(type, id);
        if (em.getLockMode(existing) == LockModeType.PESSIMISTIC_WRITE
                || em.getLockMode(existing) == LockModeType.PESSIMISTIC_FORCE_INCREMENT) return existing;
        em.detach(existing);
        var row = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) throw new NotFoundException("Không tìm thấy dữ liệu mua gói.");
        return row;
    }

    private ForbiddenException forbidden() { return new ForbiddenException("Bạn không có quyền thao tác yêu cầu mua gói này."); }
}
