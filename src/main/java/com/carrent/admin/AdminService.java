package com.carrent.admin;

import com.carrent.common.exception.ForbiddenException;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminService {
    public static final String BALANCE_DEBT_SQL = """
            o.purpose='BALANCE' AND o.subject_type='BOOKING' AND o.status='OPEN'
            AND o.amount_settled<o.amount_due
            AND EXISTS (SELECT 1 FROM bookings b JOIN cars car ON car.id=b.car_id
                WHERE b.id=o.subject_id AND o.business_key=CONCAT('BOOKING:',b.id,':BALANCE')
                AND o.payer_user_id=b.customer_id AND o.collector_user_id=car.owner_id
                AND o.amount_due=b.balance_amount AND b.workflow_version='VIETQR_V1'
                AND b.inspection_required=1 AND b.status='CONFIRMED'
                AND b.vehicle_received_at IS NOT NULL AND b.vehicle_returned_at IS NOT NULL
                AND o.due_at=DATE_ADD(b.vehicle_returned_at,INTERVAL 1 HOUR))
            """;
    static final String BALANCE_OVERDUE_SQL = BALANCE_DEBT_SQL + " AND o.due_at<=:now";
    /** Counts are systemwide; revenue and cash amounts belong to the current Admin's wallet.
     * refundsSucceeded is money actually refunded, while refundsPending/Overdue are counts. */
    public record DashboardStats(long users, long cars, long bookings, long subscriptionRevenue,
            long monthlySubscriptionRevenue, long unmatchedReceipts, long refundsPending,
            long refundsOverdue, long balanceDebtsOverdue, long grossCashReceived,
            long allocatedCash, long refundsSucceeded) { }

    private final EntityManager em;
    private final Clock clock;

    public AdminService(EntityManager em, Clock clock) { this.em = em; this.clock = clock; }

    public User requireAdmin(CustomUserPrincipal actor) {
        if (actor == null || actor.getRole() != Role.ADMIN) throw forbidden();
        var user = em.find(User.class, actor.getId());
        if (user != null) em.refresh(user);
        if (user == null || user.getStatus() != UserStatus.ACTIVE || user.getRole() != Role.ADMIN) throw forbidden();
        return user;
    }

    public boolean available(CustomUserPrincipal actor) {
        requireAdmin(actor);
        return ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                AND table_name IN ('payment_channels','payment_obligations','payment_intents',
                'webhook_inbox','payment_receipts','refunds','payout_attempts','subscription_orders')
                """).getSingleResult()).longValue() == 8;
    }

    public DashboardStats getDashboardStats(CustomUserPrincipal actor) {
        long user = requireAdmin(actor).getId();
        var now = clock.instant();
        var month = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().withDayOfMonth(1);
        var start = LocalDateTime.ofInstant(month.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneOffset.UTC);
        var end = LocalDateTime.ofInstant(month.plusMonths(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneOffset.UTC);
        var counts = (Object[]) em.createNativeQuery("""
                SELECT (SELECT COUNT(*) FROM users), (SELECT COUNT(*) FROM cars),
                (SELECT COUNT(*) FROM bookings),
                (SELECT COUNT(*) FROM payment_receipts WHERE status='REVIEW_REQUIRED')
                """).getSingleResult();
        // Orders are aggregated independently of receipts/refunds to avoid multiplying their price.
        var revenue = (Object[]) em.createNativeQuery("""
                SELECT COALESCE(SUM(s.price_snapshot),0),
                COALESCE(SUM(CASE WHEN s.applied_at>=:start AND s.applied_at<:end THEN s.price_snapshot ELSE 0 END),0)
                FROM subscription_orders s JOIN payment_obligations o ON o.id=s.obligation_id
                JOIN users u ON u.id=o.collector_user_id
                WHERE s.applied_at IS NOT NULL AND o.collector_user_id=:user AND u.role='ADMIN'
                AND o.purpose='SUBSCRIPTION' AND o.subject_type='SUBSCRIPTION_ORDER' AND o.subject_id=s.id
                """).setParameter("start", start).setParameter("end", end).setParameter("user", user).getSingleResult();
        var cash = (Object[]) em.createNativeQuery("""
                SELECT COALESCE(SUM(r.amount),0), COALESCE(SUM(r.allocated_amount),0)
                FROM payment_receipts r JOIN payment_channels c ON c.id=r.channel_id
                WHERE c.user_id=:user AND c.owner_role='ADMIN' AND c.provider='SEPAY' AND c.purpose='COLLECT'
                """).setParameter("user", user).getSingleResult();
        var refunds = (Object[]) em.createNativeQuery("""
                SELECT COALESCE(SUM(CASE WHEN r.status<>'SUCCEEDED' THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN r.status<>'SUCCEEDED' AND r.due_at<=:now THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN r.status='SUCCEEDED' AND p.user_id=:user AND p.owner_role='ADMIN'
                    AND p.provider='PAYOS' AND p.purpose='PAYOUT' AND c.user_id=:user
                    AND c.owner_role='ADMIN' AND c.provider='SEPAY' AND c.purpose='COLLECT'
                    THEN r.amount ELSE 0 END),0)
                FROM refunds r JOIN payment_channels p ON p.id=r.payout_channel_id
                JOIN payment_receipts receipt ON receipt.id=r.receipt_id
                JOIN payment_channels c ON c.id=receipt.channel_id
                """).setParameter("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).setParameter("user", user).getSingleResult();
        long debts = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM payment_obligations o WHERE " + BALANCE_OVERDUE_SQL)
                .setParameter("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).getSingleResult()).longValue();
        return new DashboardStats(number(counts[0]), number(counts[1]), number(counts[2]),
                number(revenue[0]), number(revenue[1]), number(counts[3]), number(refunds[0]),
                number(refunds[1]), debts, number(cash[0]), number(cash[1]), number(refunds[2]));
    }

    private static long number(Object value) { return ((Number) value).longValue(); }
    private static ForbiddenException forbidden() { return new ForbiddenException("Bạn không có quyền xem dữ liệu quản trị này."); }
}
