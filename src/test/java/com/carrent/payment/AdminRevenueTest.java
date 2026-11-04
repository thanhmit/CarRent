package com.carrent.payment;

import com.carrent.admin.AdminService;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.user.User;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminRevenueTest {
    static PaymentFlowFixture f;
    AdminService admin;

    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.reset();
        f.jdbc.update("DELETE FROM users WHERE id=99001");
        f.jdbc.update("UPDATE users SET role='ADMIN',status='ACTIVE' WHERE id=1");
        f.jdbc.update("""
                UPDATE bookings SET workflow_version='LEGACY',inspection_required=0,
                vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_by=NULL,
                contract_confirmed_at=NULL,advance_amount=NULL,balance_amount=NULL WHERE id=1
                """);
        admin = f.app.getBean(AdminService.class);
    }

    @Test void appliedSubscriptionRevenueExcludesRentalAndDuplicateCashEvenWithMultipleRefunds() throws Exception {
        f.historicalSmallSubscription(2);
        long obligation = f.jdbc.queryForObject("SELECT obligation_id FROM subscription_orders", Long.class);
        var at = LocalDateTime.ofInstant(f.clock.instant(), ZoneOffset.UTC);
        f.jdbc.update("""
                INSERT INTO payment_intents(id,obligation_id,code,channel_id,amount,status,created_at,expires_at)
                VALUES(301,?,'CRADMINSUB',102,5000,'RECEIVED',?,?)
                """, obligation, at, at.plusMinutes(15));
        receipt(401, 301L, 102, 5000, 5000, "APPLIED");
        receipt(402, 301L, 102, 5000, 0, "REFUND_REQUIRED");
        refund(501, 402, 104, 2000, "SUCCEEDED");
        refund(502, 402, 104, 3000, "WAITING_RECIPIENT");
        var rental = f.payments.openAttempt(201L, f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001, rental.code(), "600000"));

        var stats = admin.getDashboardStats(f.actor(1));
        assertThat(stats.subscriptionRevenue()).isEqualTo(5000);
        assertThat(stats.monthlySubscriptionRevenue()).isEqualTo(5000);
        assertThat(stats.grossCashReceived()).isEqualTo(10000);
        assertThat(stats.allocatedCash()).isEqualTo(5000);
        assertThat(stats.refundsSucceeded()).isEqualTo(2000);
        assertThat(stats.refundsPending()).isEqualTo(1);
    }

    @Test void monthlyRevenueUsesVietnamCalendarAndIncludesStartButExcludesNextMonth() {
        f.clock.at.set(Instant.parse("2026-10-31T17:00:00Z")); // November starts at 17:00 UTC.
        subscription(701, 1, 1000, "2026-10-31T16:59:59.999999");
        subscription(702, 1, 2000, "2026-10-31T17:00:00");
        subscription(703, 1, 3000, "2026-11-30T16:59:59.999999");
        subscription(704, 1, 4000, "2026-11-30T17:00:00");
        var stats = admin.getDashboardStats(f.actor(1));
        assertThat(stats.subscriptionRevenue()).isEqualTo(10000);
        assertThat(stats.monthlySubscriptionRevenue()).isEqualTo(5000);
        f.clock.at.set(Instant.parse("2027-01-15T00:00:00Z"));
        assertThat(admin.getDashboardStats(f.actor(1)).monthlySubscriptionRevenue()).isZero();
    }

    @Test void eachAdminSeesOnlyTheirWalletAmountsWhileOperationalCountsAreSystemwide() {
        f.jdbc.update("""
                INSERT INTO users(id,full_name,email,password_hash,role,status)
                SELECT 99001,'Other Admin','admin-revenue@example.test',password_hash,'ADMIN','ACTIVE' FROM users WHERE id=1
                """);
        f.jdbc.update("""
                INSERT INTO payment_channels(id,user_id,owner_role,provider,purpose,credential_reference,
                bank_bin,account_number,enabled,created_at,updated_at)
                SELECT 105,99001,'ADMIN',provider,purpose,'other-admin-collect',bank_bin,account_number,enabled,created_at,updated_at
                FROM payment_channels WHERE id=102
                """);
        f.jdbc.update("""
                INSERT INTO payment_channels(id,user_id,owner_role,provider,purpose,credential_reference,enabled,created_at,updated_at)
                SELECT 106,99001,'ADMIN',provider,purpose,'other-admin-payout',enabled,created_at,updated_at
                FROM payment_channels WHERE id=104
                """);
        subscription(701, 1, 5000, "2026-10-30T03:00:00");
        subscription(702, 99001, 7000, "2026-10-30T03:00:00");
        subscription(703, 2, 9000, "2026-10-30T03:00:00"); // An Owner collector is never Admin revenue.
        receipt(401, null, 102, 5000, 1000, "REVIEW_REQUIRED");
        receipt(402, null, 105, 7000, 2000, "REVIEW_REQUIRED");
        receipt(403, null, 101, 600000, 300000, "APPLIED");
        refund(501, 401, 104, 1000, "SUCCEEDED");
        refund(502, 402, 106, 2000, "SUCCEEDED");
        refund(503, 403, 103, 3000, "WAITING_FUNDS");

        var first = admin.getDashboardStats(f.actor(1));
        var second = admin.getDashboardStats(f.actor(99001));
        assertThat(first.subscriptionRevenue()).isEqualTo(5000);
        assertThat(second.subscriptionRevenue()).isEqualTo(7000);
        assertThat(first.grossCashReceived()).isEqualTo(5000);
        assertThat(second.grossCashReceived()).isEqualTo(7000);
        assertThat(first.allocatedCash()).isEqualTo(1000);
        assertThat(second.allocatedCash()).isEqualTo(2000);
        assertThat(first.refundsSucceeded()).isEqualTo(1000);
        assertThat(second.refundsSucceeded()).isEqualTo(2000);
        assertThat(first.unmatchedReceipts()).isEqualTo(2);
        assertThat(second.unmatchedReceipts()).isEqualTo(2);
        assertThat(first.refundsPending()).isEqualTo(1);
        assertThat(second.refundsPending()).isEqualTo(1);
        assertThat(first.users()).isEqualTo(f.count("users"));
        assertThat(first.cars()).isEqualTo(f.count("cars"));
        assertThat(first.bookings()).isEqualTo(f.count("bookings"));
    }

    @Test void onlyCurrentActiveAdminCanReadEvenWhenThePrincipalOrManagedUserIsStale() {
        for (var actor : new com.carrent.security.CustomUserPrincipal[]{null, f.actor(2), f.actor(4)}) {
            assertThatThrownBy(() -> admin.getDashboardStats(actor)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> admin.available(actor)).isInstanceOf(ForbiddenException.class);
        }
        var stale = f.actor(1);
        for (String status : new String[]{"INACTIVE", "BANNED"}) {
            f.jdbc.update("UPDATE users SET status=? WHERE id=1", status);
            assertThatThrownBy(() -> admin.getDashboardStats(stale)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> admin.available(stale)).isInstanceOf(ForbiddenException.class);
        }
        f.jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=1");
        f.tx.executeWithoutResult(s -> {
            f.em.find(User.class, 1L);
            f.jdbc.update("UPDATE users SET role='OWNER' WHERE id=1");
            assertThatThrownBy(() -> admin.requireAdmin(stale)).isInstanceOf(ForbiddenException.class);
            s.setRollbackOnly();
        });
        assertThat(admin.requireAdmin(stale).getId()).isEqualTo(1);
    }

    @Test void refundAndBalanceDeadlinesAreInclusiveAndCommittedSuccessIsNeverOverdue() {
        receipt(401, null, 102, 5000, 0, "REFUND_REQUIRED");
        refund(501, 401, 104, 1000, "WAITING_RECIPIENT");
        refund(502, 401, 104, 2000, "PROCESSING");
        refund(503, 401, 104, 2000, "SUCCEEDED");
        var now = LocalDateTime.ofInstant(f.clock.instant(), ZoneOffset.UTC);
        f.jdbc.update("UPDATE refunds SET ready_at=?,due_at=? WHERE id IN (502,503)", now.minusHours(1), now);
        f.jdbc.update("""
                UPDATE bookings SET status='CONFIRMED',workflow_version='VIETQR_V1',inspection_required=1,
                vehicle_received_at=?,vehicle_returned_at=?,contract_confirmed_by=2,contract_confirmed_at=?,
                advance_amount=540000,balance_amount=1260000 WHERE id=1
                """, now.minusDays(1), now.minusHours(1), now.minusDays(1));
        f.jdbc.update("""
                INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,
                collector_user_id,amount_due,amount_settled,due_at,status,created_at,updated_at)
                VALUES(202,'BOOKING:1:BALANCE','BALANCE','BOOKING',1,4,2,1260000,0,?,'OPEN',?,?)
                """, now, now.minusHours(1), now.minusHours(1));

        f.clock.at.set(f.clock.instant().minusNanos(1000));
        assertThat(admin.getDashboardStats(f.actor(1)).refundsOverdue()).isZero();
        assertThat(admin.getDashboardStats(f.actor(1)).balanceDebtsOverdue()).isZero();
        f.clock.at.set(f.clock.instant().plusNanos(1000));
        var stats = admin.getDashboardStats(f.actor(1));
        assertThat(stats.refundsPending()).isEqualTo(2);
        assertThat(stats.refundsOverdue()).isEqualTo(1);
        assertThat(stats.balanceDebtsOverdue()).isEqualTo(1);
        for (String invalid : new String[]{"amount_due=1200000", "collector_user_id=1", "due_at=DATE_ADD(due_at,INTERVAL 1 SECOND)",
                "amount_settled=1260000,status='SETTLED'"}) {
            f.tx.executeWithoutResult(s -> {
                f.jdbc.update("UPDATE payment_obligations SET " + invalid + " WHERE id=202");
                assertThat(admin.getDashboardStats(f.actor(1)).balanceDebtsOverdue()).isZero();
                s.setRollbackOnly();
            });
        }
        f.jdbc.update("UPDATE bookings SET balance_overdue_notified_at=? WHERE id=1", now);
        assertThat(admin.getDashboardStats(f.actor(1)).balanceDebtsOverdue()).isEqualTo(1);
    }

    @Test void legacySchemaCanBeDetectedWithoutExecutingQueriesAgainstMissingTables() {
        assertThat(admin.available(f.actor(1))).isTrue();
        f.jdbc.execute("RENAME TABLE subscription_orders TO fixture_hidden_subscriptions");
        try { assertThat(admin.available(f.actor(1))).isFalse(); }
        finally { f.jdbc.execute("RENAME TABLE fixture_hidden_subscriptions TO subscription_orders"); }
    }

    private void subscription(long id, long collector, long price, String applied) {
        var at = LocalDateTime.parse(applied);
        f.jdbc.update("""
                INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,
                collector_user_id,amount_due,amount_settled,status,created_at,updated_at)
                VALUES(?,?,'SUBSCRIPTION','SUBSCRIPTION_ORDER',?,3,?,?,?,'SETTLED',?,?)
                """, id + 1000, "SUBSCRIPTION:" + id, id, collector, price, price, at, at);
        f.jdbc.update("""
                INSERT INTO subscription_orders(id,owner_id,plan_code,quota_snapshot,price_snapshot,duration_days,
                obligation_id,status,created_at,updated_at,expires_at,applied_at)
                VALUES(?,3,'SMALL',4,?,30,?,'APPLIED',?,?,?,?)
                """, id, price, id + 1000, at, at, at.plusMinutes(15), at);
    }

    private void receipt(long id, Long intent, long channel, long amount, long allocated, String status) {
        var at = LocalDateTime.ofInstant(f.clock.instant(), ZoneOffset.UTC);
        f.jdbc.update("""
                INSERT INTO webhook_inbox(id,channel_id,provider_transaction_id,raw_payload,received_at,status)
                VALUES(?,?,?,X'01',?,'PROCESSED')
                """, id, channel, Long.toString(id), at);
        f.jdbc.update("""
                INSERT INTO payment_receipts(id,channel_id,provider_transaction_id,inbox_id,intent_id,
                amount,allocated_amount,credited_at,created_at,status) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, id, channel, Long.toString(id), id, intent, amount, allocated, at, at, status);
    }

    private void refund(long id, long receipt, long channel, long amount, String status) {
        var at = LocalDateTime.ofInstant(f.clock.instant(), ZoneOffset.UTC);
        f.jdbc.update("""
                INSERT INTO refunds(id,business_key,receipt_id,payout_channel_id,recipient_user_id,amount,reason,
                status,created_at,updated_at) VALUES(?,?,?,?,2,?,'DUPLICATE_PAYMENT',?,?,?)
                """, id, "ADMIN_FIXTURE:" + id, receipt, channel, amount, status, at, at);
    }
}
