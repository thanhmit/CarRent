package com.carrent.subscription;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SubscriptionPlan {
    SMALL(4, 5000L), STANDARD(8, 10000L), BUSINESS(16, 15000L);

    private final int quota;
    private final long price;
    public int getDurationDays() { return 30; }
}
