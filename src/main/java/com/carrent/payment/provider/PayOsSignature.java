package com.carrent.payment.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class PayOsSignature {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PayOsSignature() {
    }

    // payOS payout signatures use sorted, URI-encoded fields, unlike payment-link signatures.
    static String sign(JsonNode data, String key) {
        if (!data.isObject()) {
            throw new IllegalArgumentException("payOS signing data must be an object");
        }
        String canonical = sorted(data).properties().stream().map(entry -> {
            var value = entry.getValue();
            String text = value.isNull() ? "" : value.isObject() || value.isArray() ? value.toString() : value.asText();
            return encode(entry.getKey()) + "=" + encode(text);
        }).collect(Collectors.joining("&"));
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            var result = JSON.createObjectNode();
            fields.forEach((key, value) -> result.set(key, sorted(value)));
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            node.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }
}
