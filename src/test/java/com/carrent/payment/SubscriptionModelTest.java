package com.carrent.payment;

import com.carrent.subscription.*;
import com.carrent.user.UserRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class SubscriptionModelTest {
    @Test void catalogContainsOnlyServerDefinedThirtyDayPlans() {
        assertThat(SubscriptionPlan.values()).containsExactly(SubscriptionPlan.SMALL, SubscriptionPlan.STANDARD, SubscriptionPlan.BUSINESS);
        assertThat(SubscriptionPlan.values()).extracting(SubscriptionPlan::getQuota).containsExactly(4, 8, 16);
        assertThat(SubscriptionPlan.values()).extracting(SubscriptionPlan::getPrice).containsExactly(5000L, 10000L, 15000L);
        for (var plan : SubscriptionPlan.values()) assertThat(plan.getDurationDays()).isEqualTo(30);
        assertThatThrownBy(() -> SubscriptionPlan.valueOf("CUSTOM")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void realJpaPersistsSnapshotsAndUtcEntitlementWithoutBookingOrAutomaticGrants() throws Exception {
        var original = TimeZone.getDefault();
        try (var db = new PaymentTestDatabase()) {
            db.bootstrap(Path.of("scriptDB.sql"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            try (var app = new SpringApplicationBuilder(com.carrent.CarRentApplication.class).run("--server.port=0",
                    "--spring.datasource.url=" + db.url, "--spring.datasource.username=" + db.username,
                    "--spring.datasource.password=" + db.password, "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.main.banner-mode=off", "--carrent.payment.maintenance.enabled=false",
                    "--debug=false", "--logging.level.root=WARN")) {
                var orders = app.getBean(SubscriptionOrderRepository.class);
                var entitlements = app.getBean(OwnerSubscriptionRepository.class);
                var users = app.getBean(UserRepository.class);
                var obligations = app.getBean(PaymentObligationRepository.class);
                var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
                var now = Instant.parse("2026-11-02T03:04:05.123456Z");
                assertThat(orders.count()).isZero();
                assertThat(entitlements.count()).isZero();
                long bookings = db.number("SELECT COUNT(*) FROM bookings");
                Long id = tx.execute(status -> {
                    var owner = users.findById(2L).orElseThrow();
                    var order = orders.saveAndFlush(SubscriptionOrder.builder().owner(owner)
                            .planCode(SubscriptionPlan.SMALL).quotaSnapshot(4).priceSnapshot(5000L).durationDays(30)
                            .createdAt(now).updatedAt(now).expiresAt(now.plusSeconds(900)).build());
                    assertThat(order.getStatus()).isEqualTo(SubscriptionOrderStatus.PENDING);
                    assertThat(order.getAppliedAt()).isNull();
                    assertThat(order.getObligation()).isNull();
                    var obligation = obligations.saveAndFlush(PaymentObligation.builder()
                            .businessKey("SUBSCRIPTION:" + order.getId()).purpose(PaymentPurpose.SUBSCRIPTION)
                            .subjectType(PaymentSubjectType.SUBSCRIPTION_ORDER).subjectId(order.getId())
                            .payer(owner).collector(users.findById(1L).orElseThrow()).amountDue(5000L)
                            .dueAt(order.getExpiresAt()).createdAt(now).updatedAt(now).build());
                    order.setObligation(obligation);
                    order.setStatus(SubscriptionOrderStatus.APPLIED);
                    order.setAppliedAt(now);
                    orders.flush();
                    entitlements.saveAndFlush(OwnerSubscription.builder().ownerId(owner.getId()).owner(owner)
                            .planCode(SubscriptionPlan.SMALL).quota(4).validFrom(now).validUntil(now.plusSeconds(2592000))
                            .lastAppliedOrder(order).createdAt(now).updatedAt(now).build());
                    return order.getId();
                });
                tx.executeWithoutResult(status -> {
                    var order = orders.findById(id).orElseThrow();
                    assertThat(order.getPlanCode()).isEqualTo(SubscriptionPlan.SMALL);
                    assertThat(order.getQuotaSnapshot()).isEqualTo(4);
                    assertThat(order.getPriceSnapshot()).isEqualTo(5000L);
                    assertThat(order.getDurationDays()).isEqualTo(30);
                    assertThat(order.getAppliedAt()).isEqualTo(now);
                    assertThat(order.getExpiresAt()).isEqualTo(now.plusSeconds(900));
                    assertThat(order.getObligation().getBusinessKey()).isEqualTo("SUBSCRIPTION:" + id);
                    var entitlement = entitlements.findById(2L).orElseThrow();
                    assertThat(entitlement.getOwner().getId()).isEqualTo(2L);
                    assertThat(entitlement.getLastAppliedOrder().getId()).isEqualTo(id);
                    assertThat(entitlement.getPlanCode()).isEqualTo(SubscriptionPlan.SMALL);
                    assertThat(entitlement.getQuota()).isEqualTo(4);
                    assertThat(entitlement.getValidFrom()).isEqualTo(now);
                    assertThat(entitlement.getValidUntil()).isEqualTo(now.plusSeconds(2592000));
                });
                assertThat(db.number("SELECT COUNT(*) FROM bookings")).isEqualTo(bookings);
                try (var s = db.connection.createStatement(); var r = s.executeQuery("SELECT DATE_FORMAT(valid_from,'%Y-%m-%d %H:%i:%s.%f') FROM owner_subscriptions")) {
                    r.next(); assertThat(r.getString(1)).isEqualTo("2026-11-02 03:04:05.123456");
                }
            }
        } finally { TimeZone.setDefault(original); }
    }
}
