package com.carrent.payment;

/** Bank/account declared and confirmed by the recipient; no account-holder lookup. */
public record RefundDestination(String bankBin, String accountNumber) {
    public RefundDestination {
        if (bankBin == null || !bankBin.matches("[0-9]{6}")
                || accountNumber == null || !accountNumber.matches("[0-9]{1,50}")) {
            throw new IllegalArgumentException("Invalid refund bank/account format");
        }
    }
}
