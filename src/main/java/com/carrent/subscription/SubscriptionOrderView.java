package com.carrent.subscription;

import com.carrent.payment.PaymentIntentView;

public record SubscriptionOrderView(Long orderId, SubscriptionPlan planCode, int quotaSnapshot,
        long priceSnapshot, int durationDays, String status, PaymentIntentView intent) { }
