package com.carrent.payment.provider;

public record PayoutCommand(String referenceId, String idempotencyKey, String bankBin,
                            String accountNumber, long amount, String description) {
    public PayoutCommand {
        if (referenceId == null || referenceId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()
                || bankBin == null || !bankBin.matches("[0-9]{6}")
                || accountNumber == null || !accountNumber.matches("[0-9]{1,50}")
                || amount <= 0 || description == null || description.isBlank()) {
            throw new IllegalArgumentException("Invalid payout command");
        }
    }
}
