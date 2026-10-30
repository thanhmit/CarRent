package com.carrent.payment;

import com.carrent.config.PaymentProviderConfiguration;
import com.carrent.payment.provider.PayOsPayoutClient;
import com.carrent.payment.provider.ProviderPaymentProperties;
import com.carrent.payment.provider.SePayWebhookVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderPaymentPropertiesTest {
    private ApplicationContextRunner contextRunner() {
        // Configuration binding needs no database or real payout transport.
        return new ApplicationContextRunner().withUserConfiguration(PaymentProviderConfiguration.class)
                .withBean(PayOsPayoutClient.class, () -> org.mockito.Mockito.mock(PayOsPayoutClient.class));
    }

    @Test
    void refusesToEnableAChannelWithoutAWebhookSecret() {
        assertThatThrownBy(() -> new ProviderPaymentProperties.SePayChannel(true, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Enabled SePay channel requires a webhook secret");
    }

    @Test
    void applicationStartsWithAllChannelsUnconfigured() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SePayWebhookVerifier.class);
            assertThat(context.getBean(ProviderPaymentProperties.class).sepay()).isEmpty();
        });
    }

    @Test
    void bindsServerSideCollectorReferencesAndRedactsTheirSecrets() {
        contextRunner()
                .withPropertyValues(
                        "carrent.payment.providers.sepay.owner-test.enabled=true",
                        "carrent.payment.providers.sepay.owner-test.webhook-secret=fixture-owner-secret",
                        "carrent.payment.providers.sepay.admin-test.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(ProviderPaymentProperties.class);
                    assertThat(properties.sepay().get("owner-test").enabled()).isTrue();
                    assertThat(properties.sepay().get("owner-test").webhookSecret()).isEqualTo("fixture-owner-secret");
                    assertThat(properties.sepay().get("admin-test").enabled()).isFalse();
                    assertThat(properties.toString()).doesNotContain("fixture-owner-secret");
                    assertThatThrownBy(() -> properties.sepay().put("extra",
                            new ProviderPaymentProperties.SePayChannel(false, null)))
                            .isInstanceOf(UnsupportedOperationException.class);
                });
    }

    @Test
    void invalidEnabledChannelPreventsApplicationStartup() {
        contextRunner()
                .withPropertyValues("carrent.payment.providers.sepay.owner-test.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsPayOsChannelsAndDoesNotPrintTheirCredentials() {
        contextRunner()
                .withPropertyValues("carrent.payment.providers.payos.owner-payout.enabled=true",
                        "carrent.payment.providers.payos.owner-payout.client-id=fixture-client",
                        "carrent.payment.providers.payos.owner-payout.api-key=fixture-api-secret",
                        "carrent.payment.providers.payos.owner-payout.checksum-key=fixture-checksum-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(ProviderPaymentProperties.class);
                    var channel = properties.payos().get("owner-payout");
                    assertThat(channel.enabled()).isTrue();
                    assertThat(channel.apiKey()).isEqualTo("fixture-api-secret");
                    assertThat(channel.checksumKey()).isEqualTo("fixture-checksum-secret");
                    assertThat(properties.toString()).doesNotContain("fixture-api-secret", "fixture-checksum-secret");
                });
    }

    @Test
    void refusesToEnableAPayOsChannelWithoutAllCredentials() {
        contextRunner()
                .withPropertyValues("carrent.payment.providers.payos.owner-payout.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
