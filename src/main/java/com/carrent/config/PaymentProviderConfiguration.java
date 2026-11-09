package com.carrent.config;

import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.SePayWebhookVerifier;
import com.carrent.payment.provider.RefundDestinationVerifier;
import com.carrent.payment.provider.RefundLookupProperties;
import com.carrent.payment.provider.HttpVietQrRefundDestinationVerifier;
import com.carrent.common.exception.BusinessException;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProviderPaymentProperties.class, RefundLookupProperties.class})
public class PaymentProviderConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(com.carrent.payment.provider.PayOsPayoutClient.class)
    public com.carrent.payment.provider.PayOsPayoutClient payOsPayoutClient(com.carrent.payment.PayoutChannelResolver resolver) {
        return new com.carrent.payment.provider.HttpPayOsPayoutClient(resolver::resolve);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(RefundDestinationVerifier.class)
    public RefundDestinationVerifier refundDestinationVerifier(RefundLookupProperties lookup) {
        if (lookup.enabled()) return new HttpVietQrRefundDestinationVerifier(lookup);
        return (channelId, bankBin, accountNumber) -> {
            throw new BusinessException("Chưa thể xác minh tài khoản nhận hoàn tiền.");
        };
    }

    @Bean
    public Clock paymentClock() { return Clock.systemUTC(); }

    @Bean
    public SePayWebhookVerifier sePayWebhookVerifier(ProviderPaymentProperties properties, Clock clock) {
        return new SePayWebhookVerifier(properties, clock);
    }
}
