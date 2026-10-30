package com.carrent.payment;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentMaintenanceScheduleTest {
    @Test void enabledSchedulerFindsNewDueWorkWithoutAnHttpRequestOrManualScan() throws Exception {
        try (var f = new PaymentFlowFixture(true, true, true)) {
            f.reset();
            assertThat(f.app.containsBean("paymentMaintenanceTick")).isTrue();
            var intent = f.payments.openAttempt(201L, f.actor(4));
            f.clock.at.set(intent.expiresAt());
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
            while (!"EXPIRED".equals(f.jdbc.queryForObject("SELECT status FROM payment_intents", String.class))) {
                assertThat(System.nanoTime()).as("Scheduled expiry before fixture timeout").isLessThan(end);
                Thread.sleep(50);
            }
            assertThat(f.jdbc.queryForObject("SELECT status FROM payment_obligations", String.class)).isEqualTo("EXPIRED");
        }
    }
}
