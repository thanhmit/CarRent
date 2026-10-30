package com.carrent.payment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentIntentTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() { f.reset(); }

    @Test void payerOpensServerPricedQrAndReadOnlyStatusDoesNotExtendIt() {
        var view = f.payments.openAttempt(201L, f.actor(4));
        assertThat(view.amount()).isEqualTo(600000L);
        assertThat(view.expiresAt()).isEqualTo(PaymentFlowFixture.NOW.plusSeconds(900));
        assertThat(view.qrUrl()).contains("970422-000123456", "amount=600000", "addInfo=" + view.code());
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(100));
        assertThat(f.payments.status(view.id(), f.actor(4))).isEqualTo(view);
        assertThat(f.payments.openAttempt(201L, f.actor(4))).isEqualTo(view);
        assertThat(f.count("payment_intents")).isEqualTo(1);
        assertThat(f.count("payment_receipts")).isZero();
    }

    @Test void checkoutUsesServerValuesAndPostRequiresCsrf() throws Exception {
        f.mvc.perform(post("/payment/obligations/201/attempts").with(user(f.actor(4))))
                .andExpect(status().isForbidden());
        f.mvc.perform(post("/payment/obligations/201/attempts").with(user(f.actor(4))).with(csrf())
                .param("amount", "1").param("subjectId", "999").param("bankAccount", "attacker"))
                .andExpect(status().is3xxRedirection());
        var view = f.payments.openAttempt(201L, f.actor(4));
        String html = f.mvc.perform(get("/payment/intents/" + view.id()).with(user(f.actor(4))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(view.code(), "amount=600000", "000123456", "payment-polling.js")
                .doesNotContain(PaymentFlowFixture.SECRET, "attacker");
        f.mvc.perform(get("/payment/intents/" + view.id() + "/status").with(user(f.actor(4))))
                .andExpect(status().isOk());
        assertThat(f.count("payment_intents")).isEqualTo(1);
        assertThat(f.count("payment_receipts")).isZero();
    }

    @ParameterizedTest @ValueSource(longs = {1, 2, 5, 6, 7})
    void otherUsersCannotOpenOrReadOrCancelPayersIntent(long otherId) throws Exception {
        var view = f.payments.openAttempt(201L, f.actor(4));
        assertThatThrownBy(() -> f.payments.openAttempt(201L, f.actor(otherId)))
                .isInstanceOf(com.carrent.common.exception.ForbiddenException.class);
        f.mvc.perform(get("/payment/intents/" + view.id() + "/status").with(user(f.actor(otherId))))
                .andExpect(status().isForbidden());
        f.mvc.perform(post("/payment/intents/" + view.id() + "/cancel").with(user(f.actor(otherId))).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("ACTIVE");
    }

    @Test void expiryReadHidesQrWithoutWritingOrExtendingAttempt() {
        var view = f.payments.openAttempt(201L, f.actor(4));
        f.clock.at.set(view.expiresAt());
        var expired = f.payments.status(view.id(), f.actor(4));
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.qrUrl()).isNull();
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=?", String.class, view.id()))
                .isEqualTo("ACTIVE");
        assertThatThrownBy(() -> f.payments.openAttempt(201L, f.actor(4)))
                .isInstanceOf(com.carrent.common.exception.BusinessException.class);
    }

    @Test void cancelDelegatesToDomainAndRepeatDoesNotChangeTimestamp() {
        var view = f.payments.openAttempt(201L, f.actor(4));
        f.payments.cancelAttempt(view.id(), f.actor(4));
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(10));
        f.payments.cancelAttempt(view.id(), f.actor(4));
        assertThat(f.payments.status(view.id(), f.actor(4)).status()).isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_obligations WHERE id=201", String.class))
                .isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT cancelled_at FROM payment_intents WHERE id=?", java.sql.Timestamp.class,
                view.id()).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-10-30T03:00:00"));
        assertThat(f.count("payment_receipts")).isZero();
    }

    @Test void accountDisabledAfterLoginCannotMutate() {
        var actor = f.actor(4);
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=4");
        assertThatThrownBy(() -> f.payments.openAttempt(201L, actor))
                .isInstanceOf(com.carrent.common.exception.ForbiddenException.class);
        assertThat(f.count("payment_intents")).isZero();
    }

    @Test void anotherOwnerCannotBecomeCollectorThroughCorruptObligation() {
        f.jdbc.update("UPDATE payment_obligations SET collector_user_id=3 WHERE id=201");
        f.jdbc.update("UPDATE payment_channels SET user_id=3 WHERE id=101");
        assertThatThrownBy(() -> f.payments.openAttempt(201L, f.actor(4)))
                .isInstanceOf(com.carrent.common.exception.BusinessException.class);
        assertThat(f.count("payment_intents")).isZero();
    }

    @Test void balanceCancellationClosesOnlyAttemptAndDoesNotCancelUnderlyingDebt() {
        f.jdbc.update("UPDATE payment_obligations SET purpose='BALANCE',business_key='BOOKING:1:BALANCE' WHERE id=201");
        var first = f.payments.openAttempt(201L, f.actor(4));
        f.payments.cancelAttempt(first.id(), f.actor(4));
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_obligations WHERE id=201", String.class)).isEqualTo("OPEN");
        var second = f.payments.openAttempt(201L, f.actor(4));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.code()).isNotEqualTo(first.code());
        assertThat(f.payments.status(first.id(), f.actor(4)).status()).isEqualTo("CANCELLED");
    }

    @Test void disabledChannelHidesExistingQrWithoutPretendingAttemptWasCancelled() {
        var view = f.payments.openAttempt(201L, f.actor(4));
        f.jdbc.update("UPDATE payment_channels SET enabled=0 WHERE id=101");
        var blocked = f.payments.status(view.id(), f.actor(4));
        assertThat(blocked.qrUrl()).isNull();
        assertThat(blocked.status()).isEqualTo("UNAVAILABLE");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents", String.class)).isEqualTo("ACTIVE");
    }

    @Test void twoDatabaseTransactionsCreateOnlyOneActiveIntent() throws Exception {
        var route = f.app.getBean(PaymentRoutes.class).obligation(201L);
        var service = f.app.getBean(PaymentIntentTransactions.class);
        var actor = f.actor(4);
        var start = new CountDownLatch(1);
        java.util.function.Supplier<PaymentIntentView> open = () -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return service.open(route, actor);
        };
        var first = CompletableFuture.supplyAsync(open);
        var second = CompletableFuture.supplyAsync(open);
        start.countDown();
        assertThat(first.get(20, TimeUnit.SECONDS).id()).isEqualTo(second.get(20, TimeUnit.SECONDS).id());
        assertThat(f.count("payment_intents")).isEqualTo(1);
    }

    @Test void lockingRefreshesPreviouslyManagedObligationBeforeOpening() {
        var route = f.app.getBean(PaymentRoutes.class).obligation(201L);
        var actor = f.actor(4);
        f.tx.executeWithoutResult(s -> {
            Long reader = f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            var stale = f.em.find(PaymentObligation.class, 201L);
            Long writer = CompletableFuture.supplyAsync(() -> f.tx.execute(other -> {
                Long id = f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                f.jdbc.update("UPDATE payment_obligations SET status='CANCELLED' WHERE id=201");
                return id;
            })).join();
            assertThat(writer).isNotEqualTo(reader);
            assertThat(stale.getStatus()).isEqualTo(PaymentObligationStatus.OPEN);
            assertThatThrownBy(() -> f.app.getBean(PaymentIntentTransactions.class).open(route, actor))
                    .isInstanceOf(com.carrent.common.exception.BusinessException.class);
            s.setRollbackOnly();
        });
        assertThat(f.count("payment_intents")).isZero();
    }
}
