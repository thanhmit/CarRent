package com.carrent.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit demo scope; channel credentials alone do not approve a participant for checkout. */
@Component
public class PaymentPilot {
    private final long ownerId;
    private final long adminId;

    public PaymentPilot(@Value("${carrent.payment.pilot.owner-id:0}") long ownerId,
                        @Value("${carrent.payment.pilot.admin-id:0}") long adminId) {
        this.ownerId = ownerId;
        this.adminId = adminId;
    }

    public boolean allows(PaymentObligation obligation) {
        if (ownerId <= 0 || adminId <= 0 || ownerId == adminId) return false;
        return obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION
                ? obligation.getPayer().getId().equals(ownerId) && obligation.getCollector().getId().equals(adminId)
                : obligation.getCollector().getId().equals(ownerId);
    }
}
