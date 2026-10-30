package com.carrent.payment.provider;

public interface PayOsPayoutClient {
    PayoutResult create(Long payoutChannelId, PayoutCommand command);
    PayoutResult get(Long payoutChannelId, String providerPayoutId);
    PayoutResult findByReference(Long payoutChannelId, String referenceId);
}
