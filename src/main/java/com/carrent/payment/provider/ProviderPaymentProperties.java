package com.carrent.payment.provider;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("carrent.payment.providers")
public record ProviderPaymentProperties(Map<String, SePayChannel> sepay, Map<String, PayOsChannel> payos) {
    @ConstructorBinding
    public ProviderPaymentProperties {
        sepay = sepay == null ? Map.of() : Map.copyOf(sepay);
        payos = payos == null ? Map.of() : Map.copyOf(payos);
    }

    public ProviderPaymentProperties(Map<String, SePayChannel> sepay) {
        this(sepay, Map.of());
    }

    public record PayOsChannel(boolean enabled, String clientId, String apiKey, String checksumKey) {
        public PayOsChannel {
            if (enabled && (clientId == null || clientId.isBlank() || apiKey == null || apiKey.isBlank()
                    || checksumKey == null || checksumKey.isBlank())) {
                throw new IllegalArgumentException("Enabled payOS channel requires client ID, API key and checksum key");
            }
        }

        @Override
        public String toString() {
            return "PayOsChannel[enabled=" + enabled + ", credentials=<redacted>]";
        }
    }

    public record SePayChannel(boolean enabled, String webhookSecret) {
        public SePayChannel {
            if (enabled && (webhookSecret == null || webhookSecret.isBlank())) {
                throw new IllegalArgumentException("Enabled SePay channel requires a webhook secret");
            }
        }

        @Override
        public String toString() {
            return "SePayChannel[enabled=" + enabled + ", webhookSecret=<redacted>]";
        }
    }
}
