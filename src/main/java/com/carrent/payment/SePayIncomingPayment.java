package com.carrent.payment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

record SePayIncomingPayment(String transactionId, String accountNumber, String code, long amount, Instant creditedAt) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final DateTimeFormatter BANK_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    static SePayIncomingPayment parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > 65536) throw invalid();
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject() || !root.path("id").isIntegralNumber()) throw invalid();
            String id = root.path("id").bigIntegerValue().toString();
            if (!id.matches("[1-9][0-9]{0,99}")) throw invalid();
            String account = text(root, "accountNumber");
            if (!account.matches("[0-9]{1,50}") || !"in".equals(text(root, "transferType"))) throw invalid();
            if (root.hasNonNull("subAccount") && !text(root, "subAccount").isEmpty()) throw invalid();
            if (!root.path("transferAmount").isNumber()) throw invalid();
            long amount = root.path("transferAmount").decimalValue().longValueExact();
            if (amount <= 0) throw invalid();
            Instant credited = LocalDateTime.parse(text(root, "transactionDate"), BANK_TIME)
                    .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
            String code = root.hasNonNull("code") ? text(root, "code") : null;
            if (code != null && (code.isBlank() || code.length() > 50)) code = null;
            return new SePayIncomingPayment(id, account, code, amount, credited);
        } catch (RuntimeException ex) {
            // Never propagate parser messages containing raw payload/account information.
            throw invalid();
        }
    }

    private static String text(JsonNode root, String key) {
        if (!root.path(key).isString()) throw invalid();
        return root.path(key).asText();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid SePay payload"); }
}
