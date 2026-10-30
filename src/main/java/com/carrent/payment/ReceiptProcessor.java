package com.carrent.payment;

import com.carrent.concurrency.CarQueueRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class ReceiptProcessor {
    private final InboxWorkService work;
    private final PaymentRoutes routes;
    private final ReceiptApplicationService application;

    public ReceiptProcessor(InboxWorkService work, PaymentRoutes routes, ReceiptApplicationService application) {
        this.work = work; this.routes = routes; this.application = application;
    }

    public void process(Long inboxId) {
        var claim = work.claim(inboxId);
        if (claim == null) return;
        try {
            if (claim.intentId() == null) {
                application.apply(inboxId, claim.leaseOwner());
                return;
            }
            var route = routes.intent(claim.intentId());
            if (!routes.available(route.subject())) {
                work.retry(claim, "DOMAIN_UNAVAILABLE");
                return;
            }
            routes.execute(route, () -> { application.apply(inboxId, claim.leaseOwner()); return null; });
        } catch (CarQueueRejectedException ex) {
            work.retry(claim, "QUEUE_UNAVAILABLE");
        } catch (RuntimeException ex) {
            work.retry(claim, "APPLY_FAILED");
        }
    }

    public void expire(Long intentId) {
        var route = routes.intent(intentId);
        routes.handler(route.subject());
        routes.execute(route, () -> { application.expire(intentId); return null; });
    }
}
