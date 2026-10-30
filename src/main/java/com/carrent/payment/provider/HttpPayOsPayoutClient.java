package com.carrent.payment.provider;

import com.carrent.payment.provider.ProviderPaymentProperties.PayOsChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.LongFunction;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class HttpPayOsPayoutClient implements PayOsPayoutClient {
    private final LongFunction<PayOsChannel> channels;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpPayOsPayoutClient(LongFunction<PayOsChannel> channels) {
        this(channels, defaultHttp());
    }

    private static RestClient defaultHttp() {
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl("https://api-merchant.payos.vn").requestFactory(factory).build();
    }

    // P4.3/P4.8 resolve the DB channel and its server credential reference here, before network I/O.
    public HttpPayOsPayoutClient(LongFunction<PayOsChannel> channels, RestClient http) {
        this.channels = channels;
        this.http = http;
    }

    @Override
    public PayoutResult create(Long payoutChannelId, PayoutCommand command) {
        var channel = channel(payoutChannelId);
        var fallback = new PayoutResult(null, command.referenceId(), TransferOutcome.UNKNOWN,
                command.bankBin(), command.accountNumber(), command.amount());
        var body = json.valueToTree(Map.of("referenceId", command.referenceId(), "amount", command.amount(),
                "description", command.description(), "toBin", command.bankBin(),
                "toAccountNumber", command.accountNumber()));
        try {
            var response = http.post().uri("/v1/payouts").headers(headers -> {
                authenticate(headers, channel);
                headers.set("x-idempotency-key", command.idempotencyKey());
                headers.set("x-signature", PayOsSignature.sign(body, channel.checksumKey()));
            }).body(body).retrieve().toEntity(String.class);
            var data = verifiedData(response, channel);
            var result = result(data);
            if (!command.referenceId().equals(data.path("referenceId").asText())
                    || !command.bankBin().equals(result.bankBin())
                    || !command.accountNumber().equals(result.accountNumber())
                    || command.amount() != result.amount()) {
                return fallback;
            }
            return result;
        } catch (RestClientException | JacksonException | IllegalArgumentException ex) {
            return fallback;
        }
    }

    @Override
    public PayoutResult get(Long payoutChannelId, String providerPayoutId) {
        if (providerPayoutId == null || providerPayoutId.isBlank()) {
            throw new IllegalArgumentException("Missing provider payout ID");
        }
        var channel = channel(payoutChannelId);
        var fallback = new PayoutResult(providerPayoutId, null, TransferOutcome.UNKNOWN, null, null, 0);
        try {
            var response = http.get().uri("/v1/payouts/{id}", providerPayoutId)
                    .headers(headers -> authenticate(headers, channel)).retrieve().toEntity(String.class);
            var result = result(verifiedData(response, channel));
            return providerPayoutId.equals(result.providerPayoutId()) ? result : fallback;
        } catch (RestClientException | JacksonException | IllegalArgumentException ex) {
            return fallback;
        }
    }

    private PayOsChannel channel(Long id) {
        var channel = id == null ? null : channels.apply(id);
        if (channel == null || !channel.enabled()) {
            throw new IllegalStateException("payOS payout channel is not enabled");
        }
        return channel;
    }

    @Override
    public PayoutResult findByReference(Long payoutChannelId, String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("Missing payout reference");
        }
        var channel = channel(payoutChannelId);
        var fallback = new PayoutResult(null, referenceId, TransferOutcome.UNKNOWN, null, null, 0);
        try {
            var response = http.get().uri(uri -> uri.path("/v1/payouts")
                            .queryParam("referenceId", "{referenceId}").queryParam("limit", 2)
                            .queryParam("offset", 0).build(referenceId))
                    .headers(headers -> authenticate(headers, channel)).retrieve().toEntity(String.class);
            var data = verifiedData(response, channel);
            var payouts = data.path("payouts");
            if (!payouts.isArray() || payouts.size() != 1
                    || data.path("pagination").path("hasMore").asBoolean()
                    || !referenceId.equals(payouts.get(0).path("referenceId").asText())) {
                return fallback;
            }
            return result(payouts.get(0));
        } catch (RestClientException | JacksonException | IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static void authenticate(HttpHeaders headers, PayOsChannel channel) {
        headers.set("x-client-id", channel.clientId());
        headers.set("x-api-key", channel.apiKey());
    }

    private JsonNode verifiedData(ResponseEntity<String> response, PayOsChannel channel) {
        var envelope = json.readTree(response.getBody());
        var data = envelope.path("data");
        var signatures = response.getHeaders().get("x-signature");
        if (!"00".equals(envelope.path("code").asText()) || !data.isObject()
                || signatures == null || signatures.size() != 1
                || !signatures.getFirst().matches("[0-9a-f]{64}")
                || !MessageDigest.isEqual(PayOsSignature.sign(data, channel.checksumKey()).getBytes(StandardCharsets.US_ASCII),
                        signatures.getFirst().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Unverified payOS response");
        }
        return data;
    }

    private static PayoutResult result(JsonNode data) {
        var transactions = data.path("transactions");
        if (!transactions.isArray() || transactions.size() != 1 || data.path("id").asText().isBlank()) {
            throw new IllegalArgumentException("Expected one payOS payout transaction");
        }
        var transfer = transactions.get(0);
        var amount = transfer.path("amount");
        var reference = data.path("referenceId");
        if (!reference.isString() || reference.asText().isBlank()
                || !transfer.path("referenceId").isString()
                || !reference.asText().equals(transfer.path("referenceId").asText())
                || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.asLong() <= 0
                || transfer.path("toBin").asText().isBlank() || transfer.path("toAccountNumber").asText().isBlank()) {
            throw new IllegalArgumentException("Invalid payOS payout transaction");
        }
        var outcome = switch (transfer.path("state").asText()) {
            case "SUCCEEDED" -> TransferOutcome.SUCCEEDED;
            case "RECEIVED", "PROCESSING", "ON_HOLD" -> TransferOutcome.PROCESSING;
            default -> TransferOutcome.UNKNOWN; // FAILED/CANCELLED/REVERSED do not prove safe redispatch.
        };
        return new PayoutResult(data.path("id").asText(), data.path("referenceId").asText(), outcome, transfer.path("toBin").asText(),
                transfer.path("toAccountNumber").asText(), amount.asLong());
    }
}
