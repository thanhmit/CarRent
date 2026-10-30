package com.carrent.payment;

/** Handlers lock/validate their aggregate first; financial mutations share the caller's transaction. */
public interface PaymentDomainHandler {
    PaymentSubject subject();
    void lockSubject(Long subjectId);
    void apply(Long receiptId);
    void expire(Long intentId);
    void cancel(Long intentId);
}
