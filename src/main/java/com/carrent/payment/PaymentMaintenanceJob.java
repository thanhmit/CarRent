package com.carrent.payment;

import java.util.EnumMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.carrent.payment.PaymentMaintenanceWork.Kind;

@Component
public class PaymentMaintenanceJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentMaintenanceJob.class);
    private final PaymentMaintenanceWork work;
    private final ReceiptProcessor receipts;
    private final PayoutWorker payouts;
    private final Executor domainExecutor;
    private final Executor payoutExecutor;
    private final EnumMap<Kind, Long> cursors = new EnumMap<>(Kind.class);
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    public PaymentMaintenanceJob(PaymentMaintenanceWork work, ReceiptProcessor receipts, PayoutWorker payouts,
            @Qualifier("paymentDomainExecutor") Executor domainExecutor,
            @Qualifier("paymentPayoutExecutor") Executor payoutExecutor) {
        this.work = work; this.receipts = receipts; this.payouts = payouts;
        this.domainExecutor = domainExecutor; this.payoutExecutor = payoutExecutor;
    }

    public synchronized void scan() {
        for (Kind kind : Kind.values()) {
            try {
                // Keyset rotation lets later rows progress even when an early row lacks a domain handler.
                var ids = work.due(kind, cursors.getOrDefault(kind, 0L), 50);
                cursors.put(kind, ids.isEmpty() ? 0L : ids.getLast());
                for (Long id : ids) submit(kind, id);
            } catch (RuntimeException ex) {
                log.warn("Payment discovery deferred: kind={}", kind);
            }
        }
    }

    private void submit(Kind kind, Long id) {
        String key = kind + ":" + id;
        if (!queued.add(key)) return;
        try {
            var executor = kind == Kind.DISPATCH || kind == Kind.RECONCILE ? payoutExecutor : domainExecutor;
            executor.execute(() -> {
                try {
                    switch (kind) {
                        case INBOX -> receipts.process(id);
                        case EXPIRY -> receipts.expire(id);
                        case DISPATCH -> payouts.dispatch(id);
                        case RECONCILE -> payouts.reconcile(id);
                        case OVERDUE -> work.notifyOverdue(id);
                    }
                } catch (RuntimeException ex) {
                    log.warn("Payment work deferred: kind={}, id={}", kind, id);
                } finally { queued.remove(key); }
            });
        } catch (RejectedExecutionException ex) {
            queued.remove(key);
            log.warn("Payment queue full: kind={}", kind);
        }
    }
}
