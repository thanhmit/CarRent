package com.carrent.payment.provider;

/** Must verify without sending a payout; unavailable/ambiguous results must fail closed. */
public interface RefundDestinationVerifier {
    record Destination(String bankBin, String accountNumber, String accountName) { }
    Destination verify(Long payoutChannelId, String bankBin, String accountNumber);
}
