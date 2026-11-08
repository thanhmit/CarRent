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
        return obligation.getPurpose() == PaymentPurpose.SUBSCRIPTION
                ? allowsRentalOwner(obligation.getPayer().getId()) && obligation.getCollector().getId().equals(adminId)
                : allowsRentalOwner(obligation.getCollector().getId());
    }

    public boolean allowsRentalOwner(Long candidateId) {
        return ownerId > 0 && adminId > 0 && ownerId != adminId && Long.valueOf(ownerId).equals(candidateId);
    }

    public long adminId() { return adminId; }
}
