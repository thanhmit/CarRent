package com.carrent.payment;

import com.carrent.notification.NotificationService;
import com.carrent.user.Role;
import com.carrent.user.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class PaymentMaintenanceWork {
    public enum Kind { INBOX, EXPIRY, DISPATCH, RECONCILE, OVERDUE }
    private final JdbcTemplate jdbc;
    private final PaymentLocks locks;
    private final RefundRepository refunds;
    private final NotificationService notifications;
    private final UserRepository users;
    private final Clock clock;

    public PaymentMaintenanceWork(JdbcTemplate jdbc, PaymentLocks locks, RefundRepository refunds,
            NotificationService notifications, UserRepository users, Clock clock) {
        this.jdbc = jdbc; this.locks = locks; this.refunds = refunds;
        this.notifications = notifications; this.users = users; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Long> due(Kind kind, long after, int limit) {
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String query = switch (kind) {
            case INBOX -> "SELECT id FROM webhook_inbox WHERE status IN ('RECEIVED','PROCESSING') "
                    + "AND (next_retry_at IS NULL OR next_retry_at<=?) AND (lease_until IS NULL OR lease_until<=?)";
            case EXPIRY -> "SELECT id FROM payment_intents WHERE status='ACTIVE' AND expires_at<=?";
            case DISPATCH -> "SELECT id FROM refunds WHERE status IN ('READY','WAITING_FUNDS') "
                    + "AND (next_retry_at IS NULL OR next_retry_at<=?) AND (lease_until IS NULL OR lease_until<=?)";
            case RECONCILE -> "SELECT a.id FROM payout_attempts a JOIN refunds r ON r.id=a.refund_id "
                    + "WHERE a.status IN ('PREPARED','UNKNOWN','PROCESSING') AND (a.next_reconcile_at IS NULL OR a.next_reconcile_at<=?) "
                    + "AND (r.lease_until IS NULL OR r.lease_until<=?) AND r.status NOT IN ('SUCCEEDED','REVIEW_REQUIRED')";
            case OVERDUE -> "SELECT id FROM refunds WHERE status<>'SUCCEEDED' AND overdue_notified_at IS NULL AND due_at<=?";
        };
        String id = kind == Kind.RECONCILE ? "a.id" : "id";
        query += " AND " + id + ">? ORDER BY " + id + " LIMIT ?";
        return kind == Kind.INBOX || kind == Kind.DISPATCH || kind == Kind.RECONCILE
                ? jdbc.queryForList(query, Long.class, now, now, after, limit)
                : jdbc.queryForList(query, Long.class, now, after, limit);
    }

    public void notifyOverdue(Long id) {
        var found = refunds.findById(id).orElse(null);
        if (found == null) return;
        // Keep the same Receipt -> Refund lock order as the payout worker.
        locks.current(PaymentReceipt.class, found.getReceipt().getId());
        var refund = locks.current(Refund.class, id);
        var now = clock.instant();
        if (refund.getStatus() == RefundStatus.SUCCEEDED || refund.getDueAt() == null
                || now.isBefore(refund.getDueAt()) || refund.getOverdueNotifiedAt() != null) return;
        var recipients = new LinkedHashSet<Long>();
        recipients.add(refund.getRecipient().getId());
        var intent = refund.getReceipt().getIntent();
        recipients.add(intent == null ? refund.getReceipt().getChannel().getUser().getId()
                : intent.getObligation().getCollector().getId());
        users.findByRole(Role.ADMIN).forEach(u -> recipients.add(u.getId()));
        for (Long userId : recipients) notifications.notify(userId, "REFUND_OVERDUE", "Khoản hoàn quá hạn",
                "Khoản hoàn #" + id + " đã quá thời hạn xử lý. Hệ thống tiếp tục xử lý và đối soát kết quả.", "REFUND", id);
        refund.setOverdueNotifiedAt(now);
        refund.setUpdatedAt(now);
    }
}
