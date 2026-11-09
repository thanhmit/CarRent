package com.carrent.payment;

import com.carrent.payment.provider.HttpPayOsPayoutClient;
import com.carrent.payment.provider.ProviderPaymentProperties.PayOsChannel;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PayOsConnectionCheckTest {
    private static final String BASE = "https://api-merchant.payos.vn";
    private MockRestServiceServer server;
    private HttpPayOsPayoutClient client;
    private JsonNode fixtures;

    @BeforeEach
    void prepare() throws IOException {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpPayOsPayoutClient(Map.of(
                1L, new PayOsChannel(false, "fixture-owner-client", "fixture-owner-key", "fixture-owner-checksum"),
                2L, new PayOsChannel(false, null, null, null))::get, builder.build());
        try (var input = getClass().getResourceAsStream("/payment/payos-read-check.json")) {
            fixtures = JsonMapper.builder().build().readTree(input);
        }
    }

    private void respond(String path, String name) {
        var fixture = fixtures.path(name);
        server.expect(requestTo(BASE + path)).andExpect(method(HttpMethod.GET))
                .andExpect(header("x-client-id", "fixture-owner-client"))
                .andExpect(header("x-api-key", "fixture-owner-key"))
                .andRespond(withSuccess(fixture.path("body").toString(), MediaType.APPLICATION_JSON)
                        .header("x-signature", fixture.path("signature").asText()));
    }

    @ParameterizedTest @CsvSource({"ZERO,false", "FUNDED,true"})
    void checksSignedListAndSourceBalanceUsingOnlyGetEvenWhilePayoutIsDisabled(String fixture, boolean funded) {
        respond("/v1/payouts?limit=1&offset=0", "EMPTY_LIST");
        respond("/v1/payouts-account/balance", fixture);
        assertThat(client.checkConnection(1L).funded()).isEqualTo(funded);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FRACTION", "NEGATIVE", "OVERFLOW", "WRONG_CURRENCY"})
    void neverReportsReadinessForInvalidOrUnsupportedSignedBalances(String fixture) {
        respond("/v1/payouts?limit=1&offset=0", "EMPTY_LIST");
        respond("/v1/payouts-account/balance", fixture);
        assertThatThrownBy(() -> client.checkConnection(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("payOS read-only connection check failed");
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_PAGINATION", "STRING_HAS_MORE"})
    void rejectsMalformedSignedListBeforeQueryingBalance(String fixture) {
        respond("/v1/payouts?limit=1&offset=0", fixture);
        assertThatThrownBy(() -> client.checkConnection(1L)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "wrong", "duplicate", "tampered"})
    void requiresOneValidSignatureForTheSourceBalance(String kind) {
        respond("/v1/payouts?limit=1&offset=0", "EMPTY_LIST");
        var fixture = fixtures.path("FUNDED");
        String body = fixture.path("body").toString();
        if (kind.equals("tampered")) body = body.replace("5000.00", "9999.00");
        var response = withSuccess(body, MediaType.APPLICATION_JSON);
        if (!kind.equals("missing")) response.header("x-signature",
                kind.equals("wrong") ? "0".repeat(64) : fixture.path("signature").asText());
        if (kind.equals("duplicate")) response.header("x-signature", fixture.path("signature").asText());
        server.expect(requestTo(BASE + "/v1/payouts-account/balance")).andRespond(response);
        assertThatThrownBy(() -> client.checkConnection(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("fixture-owner-key").hasMessageNotContaining("fixture-owner-checksum");
        server.verify();
    }

    @ParameterizedTest @ValueSource(longs = {2, 99})
    void missingCredentialsNeverSendARequest(long id) {
        assertThatThrownBy(() -> client.checkConnection(id)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void httpFailureDoesNotLeakTheProviderResponseOrTryToCreateAPayout() {
        server.expect(requestTo(BASE + "/v1/payouts?limit=1&offset=0"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("fixture-owner-key"));
        assertThatThrownBy(() -> client.checkConnection(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("payOS read-only connection check failed").hasNoCause();
        server.verify();
    }

    @Test
    void timeoutsAreReportedWithoutRetrying() {
        server.expect(requestTo(BASE + "/v1/payouts?limit=1&offset=0"))
                .andRespond(withException(new SocketTimeoutException("fixture timeout")));
        assertThatThrownBy(() -> client.checkConnection(1L)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
