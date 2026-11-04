package com.carrent.owner;

import com.carrent.admin.AdminFinanceService;
import com.carrent.admin.AdminService;
import com.carrent.car.CarRepository;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.subscription.SubscriptionPolicy;
import com.carrent.subscription.SubscriptionService;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OwnerDashboardService {
    public record Stats(String ownerName, Long publicCars, long pendingCars, long hiddenCars, long bookings,
            boolean financialAvailable, long allocatedRental, long refundsSucceeded, long refundsPending,
            long refundsPendingAmount, long refundsOverdue, long refundsOverdueAmount,
            long outstandingDebt, long overdueDebts, long overdueDebtAmount) { }
    public record Dashboard(Stats stats, SubscriptionService.Overview subscription, boolean subscriptionAvailable) { }

    private final EntityManager em;
    private final Clock clock;
    private final CarRepository cars;
    private final SubscriptionPolicy policy;
    private final ObjectProvider<SubscriptionService> subscriptions;

    public OwnerDashboardService(EntityManager em, Clock clock, CarRepository cars, SubscriptionPolicy policy,
                                 ObjectProvider<SubscriptionService> subscriptions) {
        this.em=em; this.clock=clock; this.cars=cars; this.policy=policy; this.subscriptions=subscriptions;
    }

    public Dashboard getDashboard(CustomUserPrincipal actor) {
        var user=requireOwner(actor);
        long owner=user.getId();
        boolean subscriptionAvailable=policy.enabled() && tables("owner_subscriptions", "subscription_orders");
        var subscriptionService=subscriptionAvailable ? subscriptions.getIfAvailable() : null;
        var subscription=subscriptionService == null ? null : subscriptionService.overview(actor);
        var counts=(Object[]) em.createNativeQuery("""
                SELECT (SELECT COUNT(*) FROM cars WHERE owner_id=:owner AND approval_status='PENDING'),
                (SELECT COUNT(*) FROM cars WHERE owner_id=:owner AND listing_status='INACTIVE'),
                (SELECT COUNT(*) FROM bookings b JOIN cars c ON c.id=b.car_id WHERE c.owner_id=:owner)
                """).setParameter("owner",owner).getSingleResult();
        Long publicCars=policy.enabled() && !subscriptionAvailable ? null
                : cars.count(policy.publicCars().and((root,q,cb)->cb.equal(root.get("owner").get("id"),owner)));
        boolean financialAvailable=tables("payment_channels", "payment_obligations", "payment_intents", "payment_receipts", "refunds");
        long allocated=0;
        Object[] refunds={0L,0L,0L,0L,0L};
        Object[] debts={0L,0L,0L};
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        if(financialAvailable) {
            // Aggregate receipts independently: multiple refunds must not multiply allocated rental cash.
            allocated=((Number)em.createNativeQuery("SELECT COALESCE(SUM(pr.allocated_amount),0) "+AdminFinanceService.bookingFrom(AdminFinanceService.Kind.RECEIPTS)+" WHERE car.owner_id=:owner AND b.inspection_required=1")
                    .setParameter("owner",owner).getSingleResult()).longValue();
            refunds=(Object[])em.createNativeQuery("""
                    SELECT COALESCE(SUM(CASE WHEN rf.status='SUCCEEDED' THEN rf.amount ELSE 0 END),0),
                    COALESCE(SUM(CASE WHEN rf.status<>'SUCCEEDED' THEN 1 ELSE 0 END),0),
                    COALESCE(SUM(CASE WHEN rf.status<>'SUCCEEDED' THEN rf.amount ELSE 0 END),0),
                    COALESCE(SUM(CASE WHEN rf.status<>'SUCCEEDED' AND rf.due_at<=:now THEN 1 ELSE 0 END),0),
                    COALESCE(SUM(CASE WHEN rf.status<>'SUCCEEDED' AND rf.due_at<=:now THEN rf.amount ELSE 0 END),0)
                    """+AdminFinanceService.bookingFrom(AdminFinanceService.Kind.REFUNDS)+" WHERE car.owner_id=:owner AND b.inspection_required=1")
                    .setParameter("owner",owner).setParameter("now",now).getSingleResult();
            debts=(Object[])em.createNativeQuery("""
                    SELECT COALESCE(SUM(o.amount_due-o.amount_settled),0),
                    COALESCE(SUM(CASE WHEN o.due_at<=:now THEN 1 ELSE 0 END),0),
                    COALESCE(SUM(CASE WHEN o.due_at<=:now THEN o.amount_due-o.amount_settled ELSE 0 END),0)
                    FROM payment_obligations o JOIN bookings b ON b.id=o.subject_id JOIN cars car ON car.id=b.car_id
                    WHERE
                    """+AdminService.BALANCE_DEBT_SQL+" AND o.collector_user_id=:owner AND o.amount_settled>=0").setParameter("owner",owner).setParameter("now",now).getSingleResult();
        }
        return new Dashboard(new Stats(user.getFullName(),publicCars,n(counts[0]),n(counts[1]),n(counts[2]),financialAvailable,
                allocated,n(refunds[0]),n(refunds[1]),n(refunds[2]),n(refunds[3]),n(refunds[4]),n(debts[0]),n(debts[1]),n(debts[2])),
                subscription,subscriptionService != null);
    }

    public User requireOwner(CustomUserPrincipal actor) {
        if(actor==null || actor.getRole()!=Role.OWNER) throw forbidden();
        var user=em.find(User.class,actor.getId());
        if(user!=null) em.refresh(user);
        if(user==null || user.getStatus()!=UserStatus.ACTIVE || user.getRole()!=Role.OWNER) throw forbidden();
        return user;
    }
    private boolean tables(String... names) {
        return ((Number)em.createNativeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (:names)")
                .setParameter("names",java.util.List.of(names)).getSingleResult()).longValue()==names.length;
    }
    private static long n(Object value) { return ((Number)value).longValue(); }
    private static ForbiddenException forbidden() { return new ForbiddenException("Bạn không có quyền xem tổng quan chủ xe này."); }
}
