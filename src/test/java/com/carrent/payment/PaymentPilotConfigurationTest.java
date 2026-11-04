package com.carrent.payment;

import com.carrent.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class PaymentPilotConfigurationTest {
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
}
