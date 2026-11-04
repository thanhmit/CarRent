package com.carrent.subscription;

import com.carrent.payment.PaymentDomainHandler;
import com.carrent.payment.PaymentSubject;
import com.carrent.payment.PaymentObligation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "carrent.subscription.enabled", havingValue = "true")
public class SubscriptionPaymentHandler implements PaymentDomainHandler {
    private final ObjectProvider<SubscriptionService> subscriptions;
    @Override public PaymentSubject subject() { return PaymentSubject.SUBSCRIPTION; }
    @Override public void lockSubject(Long id) { subscriptions.getObject().lockSubject(id); }
    @Override public void apply(Long id) { throw new com.carrent.common.exception.BusinessException("Luồng thanh toán này chưa được kích hoạt."); }
    @Override public void expire(Long id) { subscriptions.getObject().expire(id); }
    @Override public void cancel(Long id) { subscriptions.getObject().cancel(id); }
    @Override public boolean matchesParties(PaymentObligation obligation) {
        return subscriptions.getObject().matchesObligation(obligation);
    }
}
