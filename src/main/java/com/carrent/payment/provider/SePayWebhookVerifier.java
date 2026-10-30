package com.carrent.payment.provider;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;

public final class SePayWebhookVerifier {
    private final ProviderPaymentProperties properties;
    private final Clock clock;

    public SePayWebhookVerifier(ProviderPaymentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean verify(String credentialReference, HttpHeaders headers, byte[] rawBody) {
        if (credentialReference == null) {
            return false;
        }
        var channel = properties.sepay().get(credentialReference);
        if (channel == null || !channel.enabled() || channel.webhookSecret() == null
                || channel.webhookSecret().isBlank() || headers == null || rawBody == null) {
            return false;
        }
        String timestamp = headers.getFirst("X-SePay-Timestamp");
        String signature = headers.getFirst("X-SePay-Signature");
        if (timestamp == null || !timestamp.matches("[0-9]{1,19}") || signature == null
                || !signature.matches("sha256=[0-9a-f]{64}")
                || headers.get("X-SePay-Timestamp").size() != 1
                || headers.get("X-SePay-Signature").size() != 1) {
            return false;
        }
        try {
            long signedAt = Long.parseLong(timestamp);
            long now = clock.instant().getEpochSecond();
            if (signedAt < now - 300 || signedAt > now + 300) {
                return false;
            }
        } catch (NumberFormatException ex) {
            return false;
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(channel.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }
}
