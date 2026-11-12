package com.carrent.payment;

import com.carrent.payment.provider.HttpPayOsPayoutClient;
import com.carrent.payment.provider.PayOsPayoutClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BankAccountRefundHttpJourneyTest {
    static final String URL = "https://api-merchant.payos.vn/v1/payouts";

    @Test void confirmedNamelessDestinationUsesSignedHttpAndReconcilesAfterRestartWithoutResending() throws Exception {
        JsonNode fixtures;
        try (var input = getClass().getResourceAsStream("/payment/payos-bank-account-journey.json")) {
            fixtures = JsonMapper.builder().build().readTree(input);
        }
        try (var f = new PaymentFlowFixture(true, true, Configuration.class)) {
            f.reset();
            var lookups = new AtomicInteger();
            f.app.getBean(PaymentFlowFixture.FixtureState.class).verifier = (channel, bank, account) -> {
                lookups.incrementAndGet();
                throw new AssertionError("Recipient confirmation must not call lookup");
            };
            var intent = f.payments.openAttempt(201L, f.actor(4));
            f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, intent.code(), "600000"));
            long receipt = f.jdbc.queryForObject("SELECT id FROM payment_receipts", Long.class);
            long id = f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:" + receipt,
                    receipt, 540000, RefundReason.CUSTOMER_CANCEL);
            assertThat(id).isEqualTo(1);
            f.mvc.perform(post("/payment/refunds/" + id + "/recipient").with(user(f.actor(4))).with(csrf())
                    .param("bankBin", "970422").param("accountNumber", "000123456")
                    .param("confirmed", "true").param("saveForLater", "true"))
                    .andExpect(status().is3xxRedirection());
            var server = f.app.getBean(MockRestServiceServer.class);
            var processing = fixtures.path("PROCESSING");
            server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                    .andExpect(header("x-client-id", "fixture-owner"))
                    .andExpect(header("x-api-key", "fixture-owner-api-key"))
                    .andExpect(header("x-signature", fixtures.path("requestSignature").asText()))
                    .andExpect(request -> assertThat(request.getHeaders().getFirst("x-idempotency-key")).isNotBlank())
                    .andExpect(content().json(fixtures.path("command").toString()))
                    .andRespond(withSuccess(processing.path("body").toString(), MediaType.APPLICATION_JSON)
                            .header("x-signature", processing.path("signature").asText()));
            f.app.getBean(PayoutWorker.class).dispatch(id);
            server.verify();
            assertThat(f.jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, id)).isEqualTo("PROCESSING");
            var snapshot = f.jdbc.queryForMap("SELECT id,reference_id,idempotency_key,account_name FROM payout_attempts WHERE refund_id=?", id);
            assertThat(snapshot).containsEntry("account_name", null).containsEntry("reference_id", "refund:1:attempt:1");
            assertThat(f.jdbc.queryForObject("SELECT account_name FROM refund_recipient_accounts WHERE user_id=4", String.class)).isNull();
            f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(120));
            f.restart(true);
            var restarted = f.app.getBean(MockRestServiceServer.class);
            var succeeded = fixtures.path("SUCCEEDED");
            restarted.expect(requestTo(URL + "/payout_bank_only_1")).andExpect(method(HttpMethod.GET))
                    .andExpect(header("x-client-id", "fixture-owner"))
                    .andRespond(withSuccess(succeeded.path("body").toString(), MediaType.APPLICATION_JSON)
                            .header("x-signature", succeeded.path("signature").asText()));
            f.app.getBean(PayoutWorker.class).reconcile(((Number) snapshot.get("id")).longValue());
            f.app.getBean(PayoutWorker.class).dispatch(id);
            restarted.verify();
            assertThat(f.count("payout_attempts")).isEqualTo(1);
            assertThat(f.jdbc.queryForMap("SELECT id,reference_id,idempotency_key,account_name FROM payout_attempts WHERE refund_id=?", id))
                    .isEqualTo(snapshot);
            assertThat(f.jdbc.queryForMap("SELECT status,amount,bank_bin,account_number,account_name FROM refunds WHERE id=?", id))
                    .containsEntry("status", "SUCCEEDED").containsEntry("amount", 540000L)
                    .containsEntry("bank_bin", "970422").containsEntry("account_number", "000123456")
                    .containsEntry("account_name", null);
            assertThat(lookups).hasValue(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean RestClient.Builder journeyHttp() { return RestClient.builder().baseUrl("https://api-merchant.payos.vn"); }
        @Bean MockRestServiceServer journeyServer(RestClient.Builder journeyHttp) {
            return MockRestServiceServer.bindTo(journeyHttp).build();
        }
        @Bean @Primary PayOsPayoutClient journeyPayouts(PayoutChannelResolver channels,
                RestClient.Builder journeyHttp, MockRestServiceServer journeyServer) {
            return new HttpPayOsPayoutClient(channels::resolve, journeyHttp.build());
        }
    }
}
