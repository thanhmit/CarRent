package com.carrent.payment;

import com.carrent.booking.RentalDeadlineJob;
import com.carrent.config.PaymentMaintenanceConfiguration;
import com.carrent.config.RentalDeadlineConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentSetupSchedulerIsolationTest {
    @ParameterizedTest @ValueSource(strings = {"payos-check", "payos-setup", "sepay-setup", "sepay-activate", "refund-lookup-check"})
    void diagnosticProfilesExcludeSchedulersEvenWhenAnExternalPropertyEnablesThem(String profile) {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues("carrent.payment.maintenance.enabled=true", "carrent.rental.deadlines.enabled=true")
                .withUserConfiguration(PaymentMaintenanceConfiguration.class, RentalDeadlineConfiguration.class)
                .withBean(PaymentMaintenanceJob.class, () -> mock(PaymentMaintenanceJob.class))
                .withBean(RentalDeadlineJob.class, () -> mock(RentalDeadlineJob.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean("paymentMaintenanceTick");
                    assertThat(context).doesNotHaveBean("rentalDeadlineTick");
                    assertThat(context).doesNotHaveBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor");
                });
    }
}
