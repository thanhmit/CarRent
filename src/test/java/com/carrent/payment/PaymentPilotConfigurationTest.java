package com.carrent.payment;

import com.carrent.user.User;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

class PaymentPilotConfigurationTest {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @Import(PaymentPilot.class)
    static class PilotConfiguration {}

    @Test void localProfileUsesSelectedParticipantsWithoutEnablingProvidersOrJobs() throws Exception {
        Path dotenv = directory.resolve(".env");
        Files.writeString(dotenv, "CARRENT_SEPAY_OWNER_ID=2\nCARRENT_SEPAY_ADMIN_ID=1\n");
        try (var context = new SpringApplicationBuilder(PilotConfiguration.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=local", "--spring.config.import=optional:" + dotenv.toUri() + "[.properties]",
                        "--logging.level.root=ERROR", "--spring.main.banner-mode=off")) {
            var pilot = context.getBean(PaymentPilot.class);
            for (var purpose : PaymentPurpose.values()) {
                var obligation = PaymentObligation.builder().purpose(purpose)
                        .payer(User.builder().id(purpose == PaymentPurpose.SUBSCRIPTION ? 2L : 4L).build())
                        .collector(User.builder().id(purpose == PaymentPurpose.SUBSCRIPTION ? 1L : 2L).build()).build();
                assertThat(pilot.allows(obligation)).as(purpose.name()).isTrue();
            }
            assertThat(pilot.allowsRentalOwner(3L)).isFalse();
            var env = context.getEnvironment();
            for (String key : new String[]{"carrent.payment.providers.sepay.owner-live.enabled",
                    "carrent.payment.providers.sepay.admin-live.enabled", "carrent.payment.maintenance.enabled",
                    "carrent.payment.payout.enabled", "carrent.rental.deadlines.enabled"}) {
                assertThat(env.getProperty(key, Boolean.class, false)).as(key).isFalse();
            }
        }
    }

    @Test void absentApprovalConfigurationDeniesAllCheckoutPurposes() {
        new ApplicationContextRunner().withUserConfiguration(PaymentPilot.class).run(context -> {
            assertThat(context).hasNotFailed();
            var pilot=context.getBean(PaymentPilot.class);
            for(var purpose:PaymentPurpose.values()) {
                var obligation=PaymentObligation.builder().purpose(purpose)
                        .payer(User.builder().id(purpose==PaymentPurpose.SUBSCRIPTION?2L:4L).build())
                        .collector(User.builder().id(purpose==PaymentPurpose.SUBSCRIPTION?1L:2L).build()).build();
                assertThat(pilot.allows(obligation)).isFalse();
            }
        });
    }

    @Test void localProfileWithoutParticipantSelectionKeepsCheckoutClosed() throws Exception {
        Path dotenv = directory.resolve(".env");
        Files.writeString(dotenv, "");
        try (var context = new SpringApplicationBuilder(PilotConfiguration.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=local", "--spring.config.import=optional:" + dotenv.toUri() + "[.properties]",
                        "--logging.level.root=ERROR", "--spring.main.banner-mode=off")) {
            var pilot = context.getBean(PaymentPilot.class);
            for (var purpose : PaymentPurpose.values()) {
                var obligation = PaymentObligation.builder().purpose(purpose)
                        .payer(User.builder().id(purpose == PaymentPurpose.SUBSCRIPTION ? 2L : 4L).build())
                        .collector(User.builder().id(purpose == PaymentPurpose.SUBSCRIPTION ? 1L : 2L).build()).build();
                assertThat(pilot.allows(obligation)).as(purpose.name()).isFalse();
            }
        }
    }
}
