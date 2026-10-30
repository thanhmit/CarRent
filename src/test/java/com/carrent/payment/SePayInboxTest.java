package com.carrent.payment;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SePayInboxTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); }

    static byte[] body(long id, String code, String amount) {
        return ("{\"id\":" + id + ",\"accountNumber\":\"000123456\",\"transactionDate\":\"2026-10-30 10:00:00\","
                + "\"code\":" + (code == null ? "null" : "\"" + code + "\"")
                + ",\"transferType\":\"in\",\"transferAmount\":" + amount + "}").getBytes(StandardCharsets.UTF_8);
    }

    static HttpHeaders signed(byte[] body) throws Exception {
        String timestamp = Long.toString(f.clock.instant().getEpochSecond());
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PaymentFlowFixture.SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        var headers = new HttpHeaders();
        headers.set("X-SePay-Timestamp", timestamp);
        headers.set("X-SePay-Signature", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)));
        return headers;
    }

    @Test void signedWebhookCommitsOriginalBytesBeforeAckAndDuplicateDoesNotOverwrite() throws Exception {
        byte[] body = body(9001, null, "600000");
        for (int i = 0; i < 2; i++) {
            var response = f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON)
                            .headers(signed(body)).content(body)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentAsString()).isEqualTo("{\"success\":true}");
            assertThat(f.count("webhook_inbox")).isEqualTo(1);
        }
        assertThat(f.jdbc.queryForObject("SELECT raw_payload FROM webhook_inbox", byte[].class)).isEqualTo(body);
        assertThat(f.jdbc.queryForObject("SELECT status FROM webhook_inbox", String.class)).isEqualTo("RECEIVED");
        assertThat(f.count("payment_receipts")).isZero();
    }

    @Test void invalidSignatureWrongChannelOrInactiveChannelNeverCreateInbox() throws Exception {
        byte[] body = body(9002, null, "600000");
        f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        f.mvc.perform(post("/webhooks/sepay/102").contentType(MediaType.APPLICATION_JSON)
                .headers(signed(body)).content(body)).andExpect(status().isUnauthorized());
        f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=101");
        f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON)
                .headers(signed(body)).content(body)).andExpect(status().isUnauthorized());
        assertThat(f.count("webhook_inbox")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.1", "9223372036854775808", "\"600000\"", "true", "null"})
    void malformedMoneyIsRejectedWithoutRounding(String amount) throws Exception {
        byte[] body = body(9002, null, amount);
        f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON)
                .headers(signed(body)).content(body)).andExpect(status().isBadRequest());
        assertThat(f.count("webhook_inbox")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"account", "out", "date", "duplicate", "trailing", "subaccount", "id"})
    void invalidPayloadNeverGetsSuccessAck(String kind) throws Exception {
        String original = new String(body(9002, null, "600000"), StandardCharsets.UTF_8);
        String changed = switch (kind) {
            case "account" -> original.replace("000123456", "000111111");
            case "out" -> original.replace("\"in\"", "\"out\"");
            case "date" -> original.replace("2026-10-30", "2026-02-30");
            case "duplicate" -> original.replace("\"id\":9002", "\"id\":9002,\"id\":9003");
            case "trailing" -> original + "{}";
            case "subaccount" -> original.replace("{", "{\"subAccount\":\"unconfigured\",");
            default -> original.replace("\"id\":9002", "\"id\":\"9002\"");
        };
        byte[] body = changed.getBytes(StandardCharsets.UTF_8);
        f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON)
                .headers(signed(body)).content(body)).andExpect(status().isBadRequest());
        assertThat(f.count("webhook_inbox")).isZero();
    }

    @Test void exactLargeVndAndIntegerDecimalRemainDurable() throws Exception {
        for (String amount : new String[]{"9007199254740993", "600000.0"}) {
            long id = amount.contains(".") ? 9002 : 9001;
            byte[] body = body(id, null, amount);
            f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON)
                    .headers(signed(body)).content(body)).andExpect(status().isOk());
            assertThat(f.jdbc.queryForObject("SELECT raw_payload FROM webhook_inbox WHERE provider_transaction_id=?",
                    byte[].class, Long.toString(id))).isEqualTo(body);
        }
        assertThat(f.count("webhook_inbox")).isEqualTo(2);
    }

    @Test void databaseFailureBeforeCommitReturnsFailureThenProviderRetrySucceeds() throws Exception {
        byte[] body = body(9001, null, "600000");
        f.jdbc.execute("""
                CREATE TRIGGER fixture_inbox_failure BEFORE INSERT ON webhook_inbox
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected write failure'
                """);
        try {
            String response = f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON)
                            .headers(signed(body)).content(body)).andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).isEqualTo("{\"success\":false}");
            assertThat(f.count("webhook_inbox")).isZero();
        } finally { f.jdbc.execute("DROP TRIGGER fixture_inbox_failure"); }
        f.mvc.perform(post("/webhooks/sepay/101").contentType(MediaType.APPLICATION_JSON)
                .headers(signed(body)).content(body)).andExpect(status().isOk());
        assertThat(f.count("webhook_inbox")).isEqualTo(1);
    }

    @Test void concurrentDeliveriesCommitOneInboxAndRetainFirstPayload() throws Exception {
        byte[] body = body(9001, null, "600000");
        var headers = signed(body);
        var service = f.app.getBean(SePayWebhookService.class);
        var start = new CountDownLatch(1);
        Runnable receive = () -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            service.receive(101L, headers, body);
        };
        var a = CompletableFuture.runAsync(receive);
        var b = CompletableFuture.runAsync(receive);
        start.countDown();
        a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        assertThat(f.count("webhook_inbox")).isEqualTo(1);
        byte[] changed = body(9001, "SOMETHINGELSE", "1");
        service.receive(101L, signed(changed), changed);
        assertThat(f.jdbc.queryForObject("SELECT raw_payload FROM webhook_inbox", byte[].class)).isEqualTo(body);
    }

    @Test void csrfExemptionDoesNotCoverAdjacentOrPaymentRoutes() throws Exception {
        byte[] body = body(9001, null, "600000");
        for (String path : new String[]{"/webhooks/sepay/101/extra", "/webhooks/other/101", "/payment/refunds/1", "/payment/intents/1/cancel"}) {
            f.mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).headers(signed(body)).content(body))
                    .andExpect(status().isForbidden());
        }
        assertThat(f.count("webhook_inbox")).isZero();
    }
}
