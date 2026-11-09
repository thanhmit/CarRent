package com.carrent.payment.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("carrent.payment.recipient-lookup")
public record RefundLookupProperties(boolean enabled, String clientId, String apiKey) {
    public RefundLookupProperties {
        if (enabled && (!valid(clientId) || !valid(apiKey))) {
            throw new IllegalArgumentException("Enabled recipient lookup requires its own client ID and API key");
        }
    }

    private static boolean valid(String value) {
        return value != null && !value.isBlank() && value.codePoints().noneMatch(Character::isISOControl);
    }

    @Override public String toString() {
        return "RefundLookupProperties[enabled="+enabled+", credentials=<redacted>]";
    }
}
