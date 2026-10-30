package com.carrent.payment;

import com.carrent.payment.provider.HttpPayOsPayoutClient;
import com.carrent.payment.provider.PayOsPayoutClient;
import com.carrent.payment.provider.PayoutCommand;
import com.carrent.payment.provider.PayoutResult;
import com.carrent.payment.provider.ProviderPaymentProperties.PayOsChannel;
import com.carrent.payment.provider.TransferOutcome;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PayOsPayoutClientTest {
    private static final String BASE_URL = "https://api-merchant.payos.vn/v1/payouts";
    private static final PayoutCommand COMMAND = new PayoutCommand("refund-1-attempt-1", "stable-attempt-key",
            "970422", "0000000123", 5000, "Hoàn tiền & cọc / 100%");
    private MockRestServiceServer server;
    private PayOsPayoutClient client;
    private JsonNode fixtures;

    @BeforeEach
    void setUp() throws IOException {
        var builder = RestClient.builder().baseUrl("https://api-merchant.payos.vn");
        server = MockRestServiceServer.bindTo(builder).build();
        var channels = Map.of(
                1L, new PayOsChannel(true, "fixture-owner-client", "fixture-owner-key", "fixture-owner-checksum"),
                2L, new PayOsChannel(true, "fixture-admin-client", "fixture-admin-key", "fixture-admin-checksum"),
                3L, new PayOsChannel(false, null, null, null));
        client = new HttpPayOsPayoutClient(channels::get, builder.build());
        try (var input = getClass().getResourceAsStream("/payment/payos-payouts.json")) {
            fixtures = JsonMapper.builder().build().readTree(input);
        }
    }

    @Test
    void createsASingleTransferWithTheCorrectCollectorCredentialsAndStableIdempotencyKey() {
        var request = server.expect(requestTo(BASE_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-client-id", "fixture-owner-client"))
                .andExpect(header("x-api-key", "fixture-owner-key"))
                .andExpect(header("x-idempotency-key", "stable-attempt-key"))
                // Independent Python fixture, using the official SDK's URI-encoded canonical format.
                .andExpect(header("x-signature", "d9a1781d54d6aa21fea79eeda831999ea172fecc715636843b31442ef29f260c"))
                .andExpect(content().json("""
                        {"referenceId":"refund-1-attempt-1","amount":5000,"description":"Hoàn tiền & cọc / 100%",
                         "toBin":"970422","toAccountNumber":"0000000123"}
                        """));
        respond(request, "SUCCEEDED");
        assertThat(client.create(1L, COMMAND)).isEqualTo(new PayoutResult("payout_fixture_1",
                "refund-1-attempt-1", TransferOutcome.SUCCEEDED, "970422", "0000000123", 5000));
        server.verify();
    }

    private void respond(ResponseActions request, String fixtureName) {
        var fixture = fixtures.path(fixtureName);
        request.andRespond(withSuccess(fixture.path("body").toString(), MediaType.APPLICATION_JSON)
                .header("x-signature", fixture.path("signature").asText()));
    }

    @Test
    void findsTheExistingPayoutByReferenceAfterTheCreateResponseWasLost() {
        respond(server.expect(requestTo(BASE_URL + "?referenceId=refund-1-attempt-1&limit=2&offset=0"))
                .andExpect(method(HttpMethod.GET)), "FIND_ONE");
        assertThat(client.findByReference(1L, "refund-1-attempt-1")).isEqualTo(new PayoutResult(
                "payout_fixture_1", "refund-1-attempt-1", TransferOutcome.SUCCEEDED, "970422", "0000000123", 5000));
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED,SUCCEEDED", "RECEIVED,PROCESSING", "PROCESSING,PROCESSING", "ON_HOLD,PROCESSING",
            "APPROVAL_ONLY,PROCESSING", "FAILED,UNKNOWN", "CANCELLED,UNKNOWN", "REVERSED,UNKNOWN", "FUTURE_STATE,UNKNOWN"})
    void usesTheSignedTransactionStateInsteadOfTheApprovalState(String fixture, TransferOutcome outcome) {
        respond(server.expect(requestTo(BASE_URL + "/payout_fixture_1"))
                .andExpect(method(HttpMethod.GET)), fixture);
        assertThat(client.get(1L, "payout_fixture_1").outcome()).isEqualTo(outcome);
        server.verify();
    }

    @Test
    void exposesTheSignedReferenceWhenReconcilingAnExistingPayout() {
        respond(server.expect(requestTo(BASE_URL + "/payout_fixture_1"))
                .andExpect(method(HttpMethod.GET)), "SUCCEEDED");
        assertThat(client.get(1L, "payout_fixture_1").referenceId()).isEqualTo("refund-1-attempt-1");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY_REFERENCE", "MISSING_REFERENCE", "NULL_REFERENCE", "NUMERIC_REFERENCE"})
    void refusesToConfirmAReconciledPayoutWithoutAValidReference(String fixture) {
        respond(server.expect(requestTo(BASE_URL + "/payout_fixture_1")), fixture);
        assertThat(client.get(1L, "payout_fixture_1").outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRONG_AMOUNT", "WRONG_BANK", "WRONG_ACCOUNT", "WRONG_REFERENCE", "MULTIPLE", "EMPTY"})
    void refusesToConfirmASignedResponseForADifferentTransfer(String fixture) {
        respond(server.expect(requestTo(BASE_URL)), fixture);
        assertThat(client.create(1L, COMMAND).outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }

    @Test
    void refusesASignedGetResponseForAnotherPayoutId() {
        respond(server.expect(requestTo(BASE_URL + "/payout_fixture_1")), "WRONG_ID");
        assertThat(client.get(1L, "payout_fixture_1").outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong", "tampered", "duplicate"})
    void neverTrustsMissingInvalidOrTamperedResponseSignatures(String kind) {
        String body = fixtures.path("SUCCEEDED").path("body").toString();
        if (kind.equals("tampered")) {
            body = body.replace("5000", "5001");
        }
        var response = withSuccess(body, MediaType.APPLICATION_JSON);
        if (!kind.equals("missing")) {
            response.header("x-signature", kind.equals("wrong") ? "0".repeat(64)
                    : fixtures.path("SUCCEEDED").path("signature").asText());
        }
        if (kind.equals("duplicate")) {
            response.header("x-signature", "0".repeat(64));
        }
        server.expect(requestTo(BASE_URL)).andRespond(response);
        assertThat(client.create(1L, COMMAND).outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }

    @Test
    void keepsOwnerAndAdminCredentialsSeparate() {
        respond(server.expect(requestTo(BASE_URL))
                .andExpect(header("x-client-id", "fixture-admin-client"))
                .andExpect(header("x-api-key", "fixture-admin-key")), "SUCCEEDED");
        assertThat(client.create(2L, COMMAND).outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500})
    void treatsHttpErrorsAsUnknownWithoutRetryingOrAssumingNoTransfer(int status) {
        server.expect(requestTo(BASE_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)));
        assertThat(client.create(1L, COMMAND).outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }

    @Test
    void leavesATimedOutCreateUnknownAndDoesNotResendIt() {
        server.expect(requestTo(BASE_URL)).andRespond(withException(new SocketTimeoutException("fixture timeout")));
        assertThat(client.create(1L, COMMAND).outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }

    @Test
    void keepsTheCallerProvidedIdempotencyKeyWhenTheSameAttemptIsSubmittedAgain() {
        respond(server.expect(ExpectedCount.twice(), requestTo(BASE_URL))
                .andExpect(header("x-idempotency-key", "stable-attempt-key")), "PROCESSING");
        assertThat(client.create(1L, COMMAND).outcome()).isEqualTo(TransferOutcome.PROCESSING);
        assertThat(client.create(1L, COMMAND).outcome()).isEqualTo(TransferOutcome.PROCESSING);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIND_NONE", "FIND_MULTIPLE"})
    void doesNotTreatAnEmptyOrAmbiguousReferenceLookupAsSafeToSendAgain(String fixture) {
        respond(server.expect(requestTo(BASE_URL + "?referenceId=refund-1-attempt-1&limit=2&offset=0")), fixture);
        assertThat(client.findByReference(1L, "refund-1-attempt-1").outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(longs = {3, 99})
    void refusesDisabledOrUnknownChannelsBeforeNetworkIO(long channelId) {
        assertThatThrownBy(() -> client.create(channelId, COMMAND)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void refusesNonPositiveAmountsBeforeNetworkIO(long amount) {
        assertThatThrownBy(() -> client.create(1L, new PayoutCommand("ref", "key", "970422", "123",
                amount, "Refund"))).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "not-json", "{}", "{\"code\":\"01\",\"data\":{}}"})
    void treatsMalformedAndUnsuccessfulBodiesAsUnknown(String body) {
        server.expect(requestTo(BASE_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(client.create(1L, COMMAND).outcome()).isEqualTo(TransferOutcome.UNKNOWN);
        server.verify();
    }
}
