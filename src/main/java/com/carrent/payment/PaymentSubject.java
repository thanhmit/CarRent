package com.carrent.payment;

public enum PaymentSubject {
    BOOKING_ADVANCE, BOOKING_BALANCE, SUBSCRIPTION;

    public static PaymentSubject of(PaymentObligation obligation) {
        return switch (obligation.getPurpose()) {
            case ADVANCE -> BOOKING_ADVANCE;
            case BALANCE -> BOOKING_BALANCE;
            case SUBSCRIPTION -> SUBSCRIPTION;
        };
    }
}
