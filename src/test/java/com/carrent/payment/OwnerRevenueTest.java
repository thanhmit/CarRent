package com.carrent.payment;

import com.carrent.car.Car;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.owner.OwnerDashboardService;
import com.carrent.user.User;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Page;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OwnerRevenueTest {
    static PaymentFlowFixture f;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.subscription.enabled","true");
        try { f = new PaymentFlowFixture(false); }
        finally { System.clearProperty("carrent.subscription.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.reset();
        f.jdbc.update("DELETE FROM cars WHERE id BETWEEN 99101 AND 99110");
        f.jdbc.update("UPDATE users SET role='OWNER',status='ACTIVE' WHERE id IN (2,3)");
        f.jdbc.update("""
                UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1,
                vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_by=NULL,
                contract_confirmed_at=NULL,advance_amount=600000,balance_amount=1260000 WHERE id=1
                """);
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY',inspection_required=0,advance_amount=NULL,balance_amount=NULL WHERE id=3");
    }

    @Test void ownerHasReadOnlyDashboard() throws Exception {
        f.mvc.perform(get("/owner/dashboard").with(user(f.actor(2))))
                .andExpect(status().isOk()).andExpect(view().name("owner/dashboard"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void scopedAllocatedCashSurvivesPartialRefundAndMultipleRefundRows() {
        receipt(401,101,150000,150000);
        refund(501,401,103,4,20000,"SUCCEEDED");
        refund(502,401,103,4,30000,"WAITING_FUNDS");
        refund(503,401,103,4,10000,"PROCESSING");
        f.jdbc.update("""
                INSERT INTO payout_attempts(refund_id,attempt_no,payout_channel_id,reference_id,idempotency_key,
                amount,bank_bin,account_number,status,created_at,updated_at)
                VALUES(503,1,103,'owner-unknown-reference','owner-unknown-attempt',10000,'970422','12345','UNKNOWN',?,?)
                """,now(),now());
        f.historicalSmallSubscription(2);
        f.jdbc.update("UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1,advance_amount=720000,balance_amount=1680000 WHERE id=3");
        f.jdbc.update("""
                INSERT INTO payment_channels(id,user_id,owner_role,provider,purpose,credential_reference,
                bank_bin,account_number,enabled,created_at,updated_at)
                SELECT 105,3,'OWNER',provider,purpose,'other-owner-collect',bank_bin,account_number,enabled,created_at,updated_at
                FROM payment_channels WHERE id=101
                """);
        f.jdbc.update("""
                INSERT INTO payment_channels(id,user_id,owner_role,provider,purpose,credential_reference,enabled,created_at,updated_at)
                SELECT 106,3,'OWNER',provider,purpose,'other-owner-payout',enabled,created_at,updated_at
                FROM payment_channels WHERE id=103
                """);
        f.jdbc.update("""
                INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,
                collector_user_id,amount_due,amount_settled,status,created_at,updated_at)
                VALUES(203,'BOOKING:3:ADVANCE','ADVANCE','BOOKING',3,5,3,720000,700000,'OPEN',?,?)
                """,now(),now());
        f.jdbc.update("""
                INSERT INTO payment_intents(id,obligation_id,code,channel_id,amount,status,created_at,expires_at)
                VALUES(302,203,'CROWNBRECEIPT',105,720000,'RECEIVED',?,?)
                """,now(),now().plusMinutes(15));
        receipt(402,105,700000,700000);
        refund(504,402,106,5,50000,"SUCCEEDED");
        refund(505,402,106,5,60000,"WAITING_RECIPIENT");
        var a=stats(2);
        assertThat(a.allocatedRental()).isEqualTo(150000);
        assertThat(a.refundsSucceeded()).isEqualTo(20000);
        assertThat(a.refundsPending()).isEqualTo(2);
        assertThat(a.refundsPendingAmount()).isEqualTo(40000);
        var b=stats(3);
        assertThat(b.allocatedRental()).isEqualTo(700000);
        assertThat(b.refundsSucceeded()).isEqualTo(50000);
        assertThat(b.refundsPending()).isOne();
        assertThat(b.refundsPendingAmount()).isEqualTo(60000);
    }

    @Test void receiptMustMatchBookingObligationAndOwnerCollectionChannel() {
        receipt(401,101,150000,150000);
        assertThat(stats(2).allocatedRental()).isEqualTo(150000);
        for(String invalid:List.of("collector_user_id=3","payer_user_id=5","amount_due=600001",
                "business_key='BOOKING:999:ADVANCE',subject_id=999",
                "subject_type='SUBSCRIPTION_ORDER',purpose='SUBSCRIPTION',business_key='SUBSCRIPTION:1'")) {
            f.tx.executeWithoutResult(s->{
                f.jdbc.update("UPDATE payment_obligations SET "+invalid+" WHERE id=201");
                assertThat(stats(2).allocatedRental()).isZero();
                s.setRollbackOnly();
            });
        }
        for(String invalid:List.of("user_id=3","owner_role='ADMIN'","provider='PAYOS',purpose='PAYOUT'")) {
            f.tx.executeWithoutResult(s->{
                f.jdbc.update("UPDATE payment_channels SET "+invalid+" WHERE id=101");
                assertThat(stats(2).allocatedRental()).isZero();
                s.setRollbackOnly();
            });
        }
        f.jdbc.update("UPDATE payment_channels SET user_id=3 WHERE id=101");
        assertThat(stats(2).allocatedRental()).isZero();
    }

    @Test void refundMustUseOwnPayoutAndCollectionChannelsAndBookingCustomer() {
        receipt(401,101,150000,150000);
        refund(501,401,103,4,20000,"SUCCEEDED");
        assertThat(stats(2).refundsSucceeded()).isEqualTo(20000);
        for(String invalid:List.of("payout_channel_id=104","recipient_user_id=5")) {
            f.tx.executeWithoutResult(s->{
                f.jdbc.update("UPDATE refunds SET "+invalid+" WHERE id=501");
                assertThat(stats(2).refundsSucceeded()).isZero();
                s.setRollbackOnly();
            });
        }
        f.jdbc.update("UPDATE payment_channels SET user_id=3 WHERE id=101");
        assertThat(stats(2).refundsSucceeded()).isZero();
    }

    @Test void validBalanceDebtUsesReturnPlusOneHourAndInclusiveClockNotQrExpiry() {
        f.jdbc.update("""
                UPDATE bookings SET status='CONFIRMED',vehicle_received_at=?,vehicle_returned_at=?,
                contract_confirmed_by=2,contract_confirmed_at=? WHERE id=1
                """,now().minusDays(1),now().minusHours(1),now().minusDays(1));
        f.jdbc.update("""
                INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,
                collector_user_id,amount_due,amount_settled,due_at,status,created_at,updated_at)
                VALUES(202,'BOOKING:1:BALANCE','BALANCE','BOOKING',1,4,2,1260000,260000,?,'OPEN',?,?)
                """,now(),now(),now());
        f.jdbc.update("""
                INSERT INTO payment_intents(id,obligation_id,code,channel_id,amount,status,created_at,expires_at)
                VALUES(302,202,'CROWNDEBT',101,1000000,'EXPIRED',?,?)
                """,now().minusDays(1),now().minusHours(3));
        assertThat(stats(2).outstandingDebt()).isEqualTo(1000000);
        assertThat(stats(2).overdueDebts()).isOne();
        assertThat(stats(2).overdueDebtAmount()).isEqualTo(1000000);
        assertThat(stats(3).outstandingDebt()).isZero();
        f.clock.at.set(f.clock.instant().minusNanos(1000));
        assertThat(stats(2).overdueDebts()).isZero();
        assertThat(stats(2).outstandingDebt()).isEqualTo(1000000);
        f.clock.at.set(f.clock.instant().plusNanos(1000));
        for(String invalid:List.of("collector_user_id=3","payer_user_id=5","amount_due=1200000",
                "due_at=DATE_ADD(due_at,INTERVAL 1 SECOND)","status='SETTLED',amount_settled=1260000")) {
            f.tx.executeWithoutResult(s->{
                f.jdbc.update("UPDATE payment_obligations SET "+invalid+" WHERE id=202");
                assertThat(stats(2).outstandingDebt()).isZero();
                assertThat(stats(2).overdueDebts()).isZero();
                s.setRollbackOnly();
            });
        }
    }

    @Test void refundsBecomeOverdueAtDeadlineButSucceededNeverDoes() {
        receipt(401,101,150000,150000);
        refund(501,401,103,4,20000,"SUCCEEDED");
        refund(502,401,103,4,30000,"PROCESSING");
        f.jdbc.update("UPDATE refunds SET ready_at=?,due_at=? WHERE id IN (501,502)",now().minusHours(1),now());
        assertThat(stats(2).refundsOverdue()).isOne();
        assertThat(stats(2).refundsOverdueAmount()).isEqualTo(30000);
        f.clock.at.set(f.clock.instant().minusNanos(1000));
        assertThat(stats(2).refundsOverdue()).isZero();
    }

    @Test void ownerIsReadFromCurrentDatabaseEvenWhenPrincipalOrManagedUserIsStale() throws Exception {
        var dashboard=f.app.getBean(OwnerDashboardService.class);
        for(var actor:new com.carrent.security.CustomUserPrincipal[]{null,f.actor(1),f.actor(4)})
            assertThatThrownBy(()->dashboard.getDashboard(actor)).isInstanceOf(ForbiddenException.class);
        var stale=f.actor(2);
        for(String status:List.of("INACTIVE","BANNED")) {
            f.jdbc.update("UPDATE users SET status=? WHERE id=2",status);
            assertThatThrownBy(()->dashboard.getDashboard(stale)).isInstanceOf(ForbiddenException.class);
            f.mvc.perform(get("/owner/dashboard").with(user(stale))).andExpect(status().isForbidden());
        }
        f.jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=2");
        f.tx.executeWithoutResult(s->{
            f.em.find(User.class,2L);
            f.jdbc.update("UPDATE users SET role='CUSTOMER' WHERE id=2");
            assertThatThrownBy(()->dashboard.requireOwner(stale)).isInstanceOf(ForbiddenException.class);
            s.setRollbackOnly();
        });
        f.mvc.perform(get("/owner/dashboard")).andExpect(status().is3xxRedirection());
        for(long id:new long[]{1,4})
            f.mvc.perform(get("/owner/dashboard").with(user(f.actor(id)))).andExpect(status().isForbidden());
    }

    @Test void queryOwnerIdCannotChangeScopeAndOwnerNameIsEscaped() throws Exception {
        receipt(401,101,150000,150000);
        f.jdbc.update("UPDATE users SET full_name=? WHERE id=2","<script>alert('owner')</script>");
        try {
            var result=f.mvc.perform(get("/owner/dashboard?ownerId=3").with(user(f.actor(2))))
                    .andExpect(status().isOk()).andReturn();
            var stats=(OwnerDashboardService.Stats)result.getModelAndView().getModel().get("stats");
            assertThat(stats.allocatedRental()).isEqualTo(150000);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .contains("&lt;script&gt;").doesNotContain("<script>alert('owner')</script>");
        } finally { f.jdbc.update("UPDATE users SET full_name='Owner A' WHERE id=2"); }
    }

    @Test void missingNewTablesRendersUnavailableAndKeepsBaseCounts() throws Exception {
        f.jdbc.execute("RENAME TABLE owner_subscriptions TO fixture_hidden_owner_subscriptions, payment_receipts TO fixture_hidden_owner_receipts");
        try {
            var result=f.mvc.perform(get("/owner/dashboard").with(user(f.actor(2)))).andExpect(status().isOk()).andReturn();
            String html=result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).contains("id=\"subscription-unavailable\"","id=\"financial-unavailable\"");
            assertThat(stats(2).financialAvailable()).isFalse();
            assertThat(stats(2).publicCars()).isNull();
            assertThat(stats(2).bookings()).isGreaterThan(0);
        } finally { f.jdbc.execute("RENAME TABLE fixture_hidden_owner_subscriptions TO owner_subscriptions, fixture_hidden_owner_receipts TO payment_receipts"); }
    }

    @Test void flagOffDashboardWorksWithoutSubscriptionSchema() throws Exception {
        f.restart(false);
        f.jdbc.execute("RENAME TABLE owner_subscriptions TO fixture_hidden_owner_subscriptions");
        try {
            var result=f.mvc.perform(get("/owner/dashboard").with(user(f.actor(2)))).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("id=\"subscription-unavailable\"");
            assertThat(stats(2).publicCars()).isEqualTo(2);
        } finally {
            f.jdbc.execute("RENAME TABLE fixture_hidden_owner_subscriptions TO owner_subscriptions");
            System.setProperty("carrent.subscription.enabled","true");
            try { f.restart(false); } finally { System.clearProperty("carrent.subscription.enabled"); }
        }
    }

    @Test void homeCapsNewestEligibleCarsAtEightBeforeExpiryAndSharesSearchDetailPolicy() throws Exception {
        f.historicalSmallSubscription(2);
        f.jdbc.update("UPDATE owner_subscriptions SET quota=16,plan_code='BUSINESS' WHERE owner_id=2");
        for(long id=99101;id<=99110;id++) f.jdbc.update("""
                INSERT INTO cars(id,owner_id,category_id,brand,model,manufacture_year,license_plate,seat_count,
                transmission,fuel_type,price_per_day,province,approval_status,listing_status,created_at,updated_at)
                SELECT ?,2,category_id,'Featured',?,manufacture_year,?,seat_count,transmission,fuel_type,
                price_per_day,province,'APPROVED','ACTIVE',?,? FROM cars WHERE id=1
                """,id,"Car"+id,"TEST-"+id,LocalDateTime.of(2035,1,1,0,0).plusSeconds(Math.min(id,99109)-99100),now());
        var home=f.mvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked") var featured=(List<Car>)home.getModelAndView().getModel().get("cars");
        assertThat(featured).extracting(Car::getId).containsExactly(99110L,99109L,99108L,99107L,99106L,99105L,99104L,99103L);
        var search=f.mvc.perform(get("/cars")).andExpect(status().isOk()).andReturn();
        assertThat(((Page<?>)search.getModelAndView().getModel().get("page")).getTotalElements()).isEqualTo(12);
        f.mvc.perform(get("/cars/99110")).andExpect(status().isOk());
        var overview=f.app.getBean(OwnerDashboardService.class).getDashboard(f.actor(2));
        assertThat(overview.subscriptionAvailable()).isTrue();
        assertThat(overview.subscription().quota()).isEqualTo(16);
        assertThat(overview.stats().publicCars()).isEqualTo(12);
        f.clock.at.set(PaymentFlowFixture.NOW.plusSeconds(30L*86400));
        var expired=f.mvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        assertThat((List<?>)expired.getModelAndView().getModel().get("cars")).isEmpty();
        f.mvc.perform(get("/cars/99110")).andExpect(status().isNotFound());
        assertThat(stats(2).publicCars()).isZero();
        for(long id:new long[]{1,2}) f.mvc.perform(get("/cars/99110").with(user(f.actor(id))))
                .andExpect(status().isOk()).andExpect(model().attribute("publicListing",false));
    }

    private OwnerDashboardService.Stats stats(long owner) { return f.app.getBean(OwnerDashboardService.class).getDashboard(f.actor(owner)).stats(); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(f.clock.instant(),ZoneOffset.UTC); }
    private void receipt(long id,long channel,long amount,long allocated) {
        if(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents WHERE id=301",Long.class)==0)
            f.jdbc.update("""
                    INSERT INTO payment_intents(id,obligation_id,code,channel_id,amount,status,created_at,expires_at)
                    VALUES(301,201,'CROWNRECEIPT',101,600000,'RECEIVED',?,?)
                    """,now(),now().plusMinutes(15));
        f.jdbc.update("""
                INSERT INTO webhook_inbox(id,channel_id,provider_transaction_id,raw_payload,received_at,status)
                VALUES(?,?,?,X'01',?,'PROCESSED')
                """,id,channel,Long.toString(id),now());
        f.jdbc.update("""
                INSERT INTO payment_receipts(id,channel_id,provider_transaction_id,inbox_id,intent_id,
                amount,allocated_amount,credited_at,created_at,status) VALUES(?,?,?,?,?,?,?, ?,?,'APPLIED')
                """,id,channel,Long.toString(id),id,
                f.jdbc.queryForObject("SELECT id FROM payment_intents WHERE channel_id=? ORDER BY id DESC LIMIT 1",Long.class,channel),
                amount,allocated,now(),now());
    }
    private void refund(long id,long receipt,long channel,long recipient,long amount,String status) {
        f.jdbc.update("""
                INSERT INTO refunds(id,business_key,receipt_id,payout_channel_id,recipient_user_id,amount,reason,
                status,created_at,updated_at) VALUES(?,?,?,?,?,?,'DUPLICATE_PAYMENT',?,?,?)
                """,id,"OWNER_FIXTURE:"+id,receipt,channel,recipient,amount,status,now(),now());
    }
}
