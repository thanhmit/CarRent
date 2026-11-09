package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.payment.provider.HttpVietQrRefundDestinationVerifier;
import com.carrent.payment.provider.RefundLookupProperties;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class VietQrRefundLookupTest {
    static final String URL="https://api.vietqr.io/v2/lookup";
    static final RefundLookupProperties CONFIG=new RefundLookupProperties(true,"lookup-fixture-client","lookup-fixture-private-key");
    MockRestServiceServer server;
    HttpVietQrRefundDestinationVerifier verifier;

    @BeforeEach void setup() {
        var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        verifier=new HttpVietQrRefundDestinationVerifier(CONFIG,builder.build());
    }
    @AfterEach void verifyHttp() { server.verify(); }

    @Test void looksUpWithoutPayoutAndBindsTheNameToTheRequestedDigits() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-client-id","lookup-fixture-client"))
                .andExpect(header("x-api-key","lookup-fixture-private-key"))
                .andExpect(content().json("{\"bin\":970422,\"accountNumber\":\"000123456\"}"))
                .andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"accountName\":\"NGUYEN TEST\"}}",MediaType.APPLICATION_JSON));
        var destination=verifier.verify(103L,"970422","000123456");
        assertThat(destination.bankBin()).isEqualTo("970422");
        assertThat(destination.accountNumber()).isEqualTo("000123456");
        assertThat(destination.accountName()).isEqualTo("NGUYEN TEST");
    }

    @ParameterizedTest @ValueSource(strings={
            "{\"code\":\"42\",\"desc\":\"private provider error\",\"data\":{\"accountName\":\"NGUYEN TEST\"}}",
            "{\"code\":\"41\"}","{\"code\":\"45\"}","{\"code\":0,\"data\":{\"accountName\":\"NGUYEN TEST\"}}",
            "{\"code\":\"00\",\"desc\":\"NGUYEN TEST\"}","{\"code\":\"00\",\"data\":[]}",
            "{\"code\":\"00\",\"data\":{\"accountName\":null}}","{\"code\":\"00\",\"data\":{\"accountName\":42}}",
            "{\"code\":\"00\",\"data\":{\"accountName\":\" \"}}","{\"code\":\"00\",\"data\":{\"accountName\":\"A\\nB\"}}",
            "null","[]","not-json"})
    void invalidOrUnsuccessfulBodiesNeverVerifyAName(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->verifier.verify(103L,"970422","000123456"))
                .isInstanceOf(BusinessException.class).hasNoCause()
                .hasMessageNotContaining("private provider error").hasMessageNotContaining("000123456")
                .hasMessageNotContaining("lookup-fixture-private-key");
    }

    @Test void rejectsNamesTooLongForTheFrozenRefundDestination() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"accountName\":\""+"A".repeat(151)+"\"}}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->verifier.verify(103L,"970422","000123456")).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest @ValueSource(ints={201,204,301,302,400,401,403,429,500,503})
    void rejectsNon200ResponsesWithoutLeakingProviderBodies(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .header("Location","https://untrusted.invalid/lookup")
                .body("{\"code\":\"00\",\"data\":{\"accountName\":\"PRIVATE PROVIDER NAME\"}}"));
        assertThatThrownBy(()->verifier.verify(103L,"970422","000123456"))
                .isInstanceOf(BusinessException.class).hasNoCause().hasMessageNotContaining("PRIVATE PROVIDER NAME");
    }

    @Test void timeoutDoesNotRetryOrLeakTransportDetails() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("private transport details")));
        assertThatThrownBy(()->verifier.verify(103L,"970422","000123456"))
                .isInstanceOf(BusinessException.class).hasNoCause().hasMessageNotContaining("private transport details");
    }

    @ParameterizedTest @ValueSource(strings={"","12345","12345678901234567890","name-alias","1234 56","１２３４５６"})
    void unsupportedAccountNumbersNeverReachTheProvider(String account) {
        assertThatThrownBy(()->verifier.verify(103L,"970422",account)).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest @ValueSource(strings={"","97042","9704220","ABCDEF"})
    void invalidBankBinsNeverReachTheProvider(String bank) {
        assertThatThrownBy(()->verifier.verify(103L,bank,"000123456")).isInstanceOf(BusinessException.class);
    }

    @Test void disabledLookupOrMissingSourceNeverReachesTheProvider() {
        var disabled=new HttpVietQrRefundDestinationVerifier(new RefundLookupProperties(false,null,null),RestClient.builder().build());
        assertThatThrownBy(()->disabled.verify(103L,"970422","000123456")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->verifier.verify(null,"970422","000123456")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->verifier.verify(0L,"970422","000123456")).isInstanceOf(BusinessException.class);
    }

    @Test void configurationNeverPrintsTheLookupCredentials() {
        assertThat(CONFIG.toString()).doesNotContain("lookup-fixture-client","lookup-fixture-private-key");
        assertThatThrownBy(()->new RefundLookupProperties(true,"lookup-fixture-client",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RefundLookupProperties(true,"bad\nclient","key")).isInstanceOf(IllegalArgumentException.class);
    }
}
