package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.subscription.SubscriptionPlan;
import com.carrent.subscription.SubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentPilotTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled","true");
        try { f=new PaymentFlowFixture(false,true,PayoutWorkerTest.Configuration.class); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if(f!=null)f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.reset();
    }
    @Test void ownerOutsideApprovedPilotCannotCreateAdminCheckout() {
        assertThatThrownBy(()->f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL,f.actor(3)))
                .isInstanceOf(BusinessException.class);
        assertThat(f.count("subscription_orders")).isZero();
        assertThat(f.count("payment_intents")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE purpose='SUBSCRIPTION'",Long.class)).isZero();
    }

    @Test void approvedOwnerMayBuyWithoutLinkingIncomingOrPayoutProviders() {
        f.jdbc.update("DELETE FROM payment_channels WHERE user_id=2");
        var order=f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL,f.actor(2));
        assertThat(order.intent().status()).isEqualTo("ACTIVE");
        assertThat(order.intent().qrUrl()).contains("970422-000999999");
    }

    @Test void removingPilotApprovalHidesExistingQrAndBlocksReuseButKeepsCancellation() throws Exception {
        var order=f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL,f.actor(2));
        var before=f.jdbc.queryForList("SELECT * FROM payment_intents ORDER BY id");
        long obligation=f.jdbc.queryForObject("SELECT obligation_id FROM subscription_orders WHERE id=?",Long.class,order.orderId());
        try {
            restart(0,1);
            assertThat(f.payments.status(order.intent().id(),f.actor(2)).status()).isEqualTo("UNAVAILABLE");
            assertThat(f.payments.status(order.intent().id(),f.actor(2)).qrUrl()).isNull();
            assertThat(f.app.getBean(SubscriptionService.class).detail(order.orderId(),f.actor(2)).intent().qrUrl()).isNull();
            assertThat(f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL,f.actor(2)).intent().status()).isEqualTo("UNAVAILABLE");
            assertThatThrownBy(()->f.payments.openAttempt(obligation,f.actor(2))).isInstanceOf(BusinessException.class);
            f.mvc.perform(get("/payment/intents/"+order.intent().id()).with(user(f.actor(2))))
                    .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("img.vietqr.io"))));
            assertThat(f.jdbc.queryForList("SELECT * FROM payment_intents ORDER BY id")).isEqualTo(before);
            f.app.getBean(SubscriptionService.class).cancelOrder(order.orderId(),f.actor(2));
            assertThat(f.app.getBean(SubscriptionService.class).detail(order.orderId(),f.actor(2)).status()).isEqualTo("CANCELLED");
        } finally { restart(2,1); }
    }

    @Test void invalidPilotPairCannotOpenEvenApprovedCollectorsCheckout() {
        try {
            for(long[] pair:new long[][]{{0,0},{-1,1},{2,0},{2,2},{2,3}}) {
                restart(pair[0],pair[1]);
                assertThatThrownBy(()->f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL,f.actor(2)))
                        .isInstanceOf(BusinessException.class);
                assertThat(f.count("subscription_orders")).isZero();
            }
        } finally { restart(2,1); }
    }

    @Test void alreadyReceivedAndDuplicateMoneyStillApplyAndRefundAfterPilotIsRemoved() throws Exception {
        var order=f.app.getBean(SubscriptionService.class).createOrder(SubscriptionPlan.SMALL,f.actor(2));
        try {
            restart(0,0);
            pay(order.intent(),988001);
            var applied=f.app.getBean(SubscriptionService.class).detail(order.orderId(),f.actor(2));
            assertThat(applied.status()).isEqualTo("APPLIED");
            assertThat(applied.intent().status()).isEqualTo("RECEIVED");
            var expiry=f.jdbc.queryForObject("SELECT valid_until FROM owner_subscriptions",java.sql.Timestamp.class);
            pay(order.intent(),988002);
            assertThat(f.jdbc.queryForObject("SELECT valid_until FROM owner_subscriptions",java.sql.Timestamp.class)).isEqualTo(expiry);
            assertThat(f.jdbc.queryForMap("SELECT recipient_user_id,payout_channel_id,status,amount FROM refunds"))
                    .containsEntry("recipient_user_id",2L).containsEntry("payout_channel_id",104L)
                    .containsEntry("status","WAITING_RECIPIENT").containsEntry("amount",5000L);
            assertThat(f.payments.status(order.intent().id(),f.actor(2)).status()).isEqualTo("REFUND_PENDING");
            assertThat(f.payments.status(order.intent().id(),f.actor(2)).qrUrl()).isNull();
        } finally { restart(2,1); }
    }

    @Test void rentalQrAlsoRequiresApprovedOwnerAndCannotReuseOldActiveAttempt() {
        try {
            // Fixture handlers isolate this admission check from the rental acceptance flow covered separately.
            f.restart(true);
            var intent=f.payments.openAttempt(201L,f.actor(4));
            assertThat(intent.status()).isEqualTo("ACTIVE");
            System.setProperty("fixture.payment-pilot.owner-id","3");
            f.restart(true);
            assertThat(f.payments.status(intent.id(),f.actor(4)).status()).isEqualTo("UNAVAILABLE");
            assertThat(f.payments.status(intent.id(),f.actor(4)).qrUrl()).isNull();
            assertThatThrownBy(()->f.payments.openAttempt(201L,f.actor(4))).isInstanceOf(BusinessException.class);
            assertThat(f.count("payment_intents")).isOne();
        } finally {
            restart(2,1);
        }
    }

    void restart(long owner,long admin) {
        System.setProperty("carrent.subscription.enabled","true");
        System.setProperty("fixture.payment-pilot.owner-id",Long.toString(owner));
        System.setProperty("fixture.payment-pilot.admin-id",Long.toString(admin));
        try { f.restart(false); }
        finally {
            System.clearProperty("carrent.subscription.enabled");
            System.clearProperty("fixture.payment-pilot.owner-id");
            System.clearProperty("fixture.payment-pilot.admin-id");
        }
    }

    void pay(PaymentIntentView intent,long transaction) throws Exception {
        String date=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(f.clock.instant());
        byte[] body=new String(SePayInboxTest.body(transaction,intent.code(),Long.toString(intent.amount())),StandardCharsets.UTF_8)
                .replace("000123456","000999999").replace("2026-10-30 10:00:00",date).getBytes(StandardCharsets.UTF_8);
        f.app.getBean(SePayWebhookService.class).receive(102L,f.signed(body,"another-fixture-secret"),body);
        long inbox=f.jdbc.queryForObject("SELECT id FROM webhook_inbox WHERE provider_transaction_id=?",Long.class,Long.toString(transaction));
        f.app.getBean(ReceiptProcessor.class).process(inbox);
    }
}
