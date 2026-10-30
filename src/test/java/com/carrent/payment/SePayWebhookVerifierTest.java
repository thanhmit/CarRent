package com.carrent.payment;

import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.SePayWebhookVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class SePayWebhookVerifierTest {
    // Calculated independently with Python hmac over the fixture's exact UTF-8 bytes.
    private static final String SIGNATURE =
            "sha256=5bd3a609a7ca2eb3e653f521dd99004bda72be89c32c25e91958a08d17bfde8b";
    private static final Instant SIGNED_AT = Instant.ofEpochSecond(1793066400L);

    @Test
    void acceptsTheDocumentedHmacFormatWithTheOriginalUtf8Body() throws IOException {
        assertThat(verifier(SIGNED_AT).verify("owner-test", headers(), body())).isTrue();
    }

    @Test
    void rejectsAuthenticRequestsOutsideTheFiveMinuteWindow() throws IOException {
        assertThat(verifier(SIGNED_AT.plusSeconds(301)).verify("owner-test", headers(), body())).isFalse();
        assertThat(verifier(SIGNED_AT.minusSeconds(301)).verify("owner-test", headers(), body())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {-300, 300})
    void acceptsTheDocumentedTimeWindowBoundary(long seconds) throws IOException {
        assertThat(verifier(SIGNED_AT.plusSeconds(seconds)).verify("owner-test", headers(), body())).isTrue();
    }

    @Test
    void rejectsModifiedBodyBytesIncludingWhitespaceAndUnicode() throws IOException {
        String original = new String(body(), StandardCharsets.UTF_8);
        for (String changed : new String[] {original.stripTrailing(), original.replace("5000", "5001"),
                original.replace("chuyển", "chuyen")}) {
            assertThat(verifier(SIGNED_AT).verify("owner-test", headers(),
                    changed.getBytes(StandardCharsets.UTF_8))).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin-test", "disabled", "unknown"})
    void refusesOtherCollectorsAndInactiveChannels(String reference) throws IOException {
        assertThat(verifier(SIGNED_AT).verify(reference, headers(), body())).isFalse();
    }

    @Test
    void rejectsMissingRequestData() throws IOException {
        var verifier = verifier(SIGNED_AT);
        assertThat(verifier.verify(null, headers(), body())).isFalse();
        assertThat(verifier.verify("owner-test", null, body())).isFalse();
        assertThat(verifier.verify("owner-test", headers(), null)).isFalse();
        assertThat(verifier.verify("owner-test", new HttpHeaders(), body())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "1793066400,1793066400", "-1", "9223372036854775808"})
    void rejectsMalformedTimestamps(String timestamp) throws IOException {
        var headers = headers();
        headers.set("X-SePay-Timestamp", timestamp);
        assertThat(verifier(SIGNED_AT).verify("owner-test", headers, body())).isFalse();
    }

    @Test
    void rejectsMissingInvalidAndDuplicateSignatureHeaders() throws IOException {
        for (String value : new String[] {"", "sha256=abc", SIGNATURE.replace('5', '6')}) {
            var headers = headers();
            headers.set("X-SePay-Signature", value);
            assertThat(verifier(SIGNED_AT).verify("owner-test", headers, body())).isFalse();
        }
        var duplicate = headers();
        duplicate.add("X-SePay-Signature", SIGNATURE);
        assertThat(verifier(SIGNED_AT).verify("owner-test", duplicate, body())).isFalse();
        duplicate = headers();
        duplicate.add("X-SePay-Timestamp", "1793066400");
        assertThat(verifier(SIGNED_AT).verify("owner-test", duplicate, body())).isFalse();
    }

    private static SePayWebhookVerifier verifier(Instant now) {
        var properties = new ProviderPaymentProperties(Map.of(
                "owner-test", new ProviderPaymentProperties.SePayChannel(true, "fixture-owner-secret"),
                "admin-test", new ProviderPaymentProperties.SePayChannel(true, "fixture-admin-secret"),
                "disabled", new ProviderPaymentProperties.SePayChannel(false, "fixture-owner-secret")));
        return new SePayWebhookVerifier(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static HttpHeaders headers() {
        var headers = new HttpHeaders();
        headers.set("X-SePay-Signature", SIGNATURE);
        headers.set("X-SePay-Timestamp", "1793066400");
        return headers;
    }

    private static byte[] body() throws IOException {
        try (var stream = SePayWebhookVerifierTest.class.getResourceAsStream("/payment/sepay-incoming.json")) {
            return stream.readAllBytes();
        }
    }
}
