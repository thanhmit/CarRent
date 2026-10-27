package com.carrent.concurrency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("carrent.car-queue")
public record CarQueueProperties(
        @DefaultValue("4") int workers,
        @DefaultValue("50") int maxWaitingPerCar,
        @DefaultValue("500") int maxWaitingTotal,
        @DefaultValue("10s") Duration waitTimeout,
        @DefaultValue("30s") Duration shutdownTimeout) {
    @ConstructorBinding
    public CarQueueProperties {
        if (workers <= 0 || maxWaitingPerCar <= 0 || maxWaitingTotal <= 0) {
            throw new IllegalArgumentException("Queue workers and waiting limits must be positive");
        }
        requirePositiveNanos(waitTimeout, "waitTimeout");
        requirePositiveNanos(shutdownTimeout, "shutdownTimeout");
    }

    public CarQueueProperties() {
        this(4, 50, 500, Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    private static void requirePositiveNanos(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " is too large", ex);
        }
    }
}
