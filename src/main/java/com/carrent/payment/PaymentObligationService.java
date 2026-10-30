package com.carrent.payment;

import com.carrent.security.CustomUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentObligationService {
    private final PaymentRoutes routes;
    private final PaymentIntentTransactions transactions;

    public PaymentObligationService(PaymentRoutes routes, PaymentIntentTransactions transactions) {
        this.routes = routes;
        this.transactions = transactions;
    }

    public PaymentIntentView openAttempt(Long obligationId, CustomUserPrincipal actor) {
        var route = routes.obligation(obligationId);
        routes.handler(route.subject());
        return routes.execute(route, () -> transactions.open(route, actor));
    }

    public void cancelAttempt(Long intentId, CustomUserPrincipal actor) {
        var route = routes.intent(intentId);
        routes.handler(route.subject());
        routes.execute(route, () -> { transactions.cancel(route, intentId, actor); return null; });
    }

    public PaymentIntentView status(Long intentId, CustomUserPrincipal actor) {
        return transactions.status(intentId, actor);
    }
}
