package com.carrent.payment.provider;

import com.carrent.common.exception.BusinessException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class HttpVietQrRefundDestinationVerifier implements RefundDestinationVerifier {
    private final RefundLookupProperties properties;
    private final RestClient http;
    private final JsonMapper json=JsonMapper.builder().build();

    public HttpVietQrRefundDestinationVerifier(RefundLookupProperties properties) {
        this(properties,defaultHttp());
    }
    public HttpVietQrRefundDestinationVerifier(RefundLookupProperties properties,RestClient http) {
        this.properties=properties;this.http=http;
    }

    @Override public Destination verify(Long payoutChannelId,String bankBin,String accountNumber) {
        if (!properties.enabled() || payoutChannelId==null || payoutChannelId<=0
                || bankBin==null || !bankBin.matches("[0-9]{6}")
                || accountNumber==null || !accountNumber.matches("[0-9]{6,19}")) throw unavailable();
        try {
            var response=http.post().uri("https://api.vietqr.io/v2/lookup")
                    .header("x-client-id",properties.clientId()).header("x-api-key",properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("bin",Integer.parseInt(bankBin),"accountNumber",accountNumber))
                    .retrieve().toEntity(String.class);
            if (response.getStatusCode().value()!=200) throw unavailable();
            var envelope=json.readTree(response.getBody());
            if (envelope==null || !envelope.path("code").isString()
                    || !"00".equals(envelope.path("code").asText()) || !envelope.path("data").isObject()) throw unavailable();
            var name=envelope.path("data").path("accountName");
            if (!name.isString() || name.asText().isBlank() || name.asText().length()>150
                    || name.asText().codePoints().anyMatch(Character::isISOControl)) throw unavailable();
            // The lookup response has no destination echo; bind it to this exact request.
            return new Destination(bankBin,accountNumber,name.asText());
        } catch (RestClientException | JacksonException | IllegalArgumentException ex) {
            throw unavailable();
        }
    }

    private static RestClient defaultHttp() {
        var transport=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(transport);factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }

    private static BusinessException unavailable() {
        return new BusinessException("Chưa xác minh được tài khoản nhận hoàn tiền.");
    }
}
