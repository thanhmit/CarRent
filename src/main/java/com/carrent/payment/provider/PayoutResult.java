package com.carrent.payment.provider;

public record PayoutResult(String providerPayoutId, String referenceId, TransferOutcome outcome, String bankBin,
                           String accountNumber, long amount, NotSentReason notSentReason) {
    public enum NotSentReason { INSUFFICIENT_FUNDS, RETRYABLE, PERMANENT }

    public PayoutResult(String providerPayoutId, String referenceId, TransferOutcome outcome, String bankBin,
                        String accountNumber, long amount) {
        this(providerPayoutId,referenceId,outcome,bankBin,accountNumber,amount,null);
    }
}
