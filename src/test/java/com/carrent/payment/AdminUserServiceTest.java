package com.carrent.payment;

import com.carrent.admin.AdminUserService;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminUserServiceTest {
    static PaymentFlowFixture f;
    AdminUserService users;

    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM owner_subscriptions");
        f.jdbc.update("DELETE FROM subscription_orders");
        f.reset();
        f.jdbc.update("DELETE FROM users WHERE id>=99100");
        f.jdbc.update("UPDATE users SET role='ADMIN',status='ACTIVE' WHERE id=1");
        f.jdbc.update("UPDATE users SET role='OWNER',status='ACTIVE' WHERE id IN (2,3)");
        f.jdbc.update("UPDATE users SET role='CUSTOMER',status='ACTIVE' WHERE id=4");
        users = f.app.getBean(AdminUserService.class);
    }

    @Test void literalSearchAndCombinedFiltersHaveStableBoundedPages() {
        for (int n=0; n<22; n++) addUser(99100+n, "Literal %_! "+n, Role.CUSTOMER, UserStatus.BANNED);
        addUser(99130, "Literal ordinary", Role.CUSTOMER, UserStatus.BANNED);
        addUser(99131, "Literal %_! owner", Role.OWNER, UserStatus.BANNED);
        addUser(99132, "Literal %_! active", Role.CUSTOMER, UserStatus.ACTIVE);
        var first = users.list(" %_! ", Role.CUSTOMER, UserStatus.BANNED, 0, f.actor(1));
        assertThat(first.getTotalElements()).isEqualTo(22);
        assertThat(first.getContent()).hasSize(20);
        assertThat(first.getContent().getFirst().id()).isEqualTo(99121);
        var second = users.list("%_!", Role.CUSTOMER, UserStatus.BANNED, 1, f.actor(1));
        assertThat(second.getContent()).extracting(AdminUserService.UserRow::id).containsExactly(99101L,99100L);
        assertThat(users.list("literal", null, null, -1, f.actor(1)).getTotalElements()).isEqualTo(25);
        assertThat(users.list("literal-%' OR 1=1 --", null, null, 0, f.actor(1))).isEmpty();
        assertThat(users.list("0901234567", null, null, 0, f.actor(1)).getTotalElements()).isEqualTo(25);
        assertThat(users.list("admin-user-99130@example.test", null, null, 0, f.actor(1)).getTotalElements()).isEqualTo(1);
        assertThatThrownBy(() -> users.list("x".repeat(151), null, null, 0, f.actor(1)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void detailCountsBelongToTheTargetAndLegacyMissingFinancialSchemaStillWorks() {
        f.historicalSmallSubscription(2);
        var owner = users.detail(2, f.actor(1));
        assertThat(owner.user().id()).isEqualTo(2);
        assertThat(owner.ownedCars()).isEqualTo(3);
        assertThat(owner.customerBookings()).isZero();
        assertThat(owner.ownerBookings()).isEqualTo(4);
        assertThat(owner.subscriptionOrders()).isEqualTo(1);
        assertThat(owner.financialAvailable()).isTrue();
        var customer = users.detail(4, f.actor(1));
        assertThat(customer.ownedCars()).isZero();
        assertThat(customer.ownerBookings()).isZero();
        assertThat(customer.customerBookings()).isEqualTo(3);
        assertThat(customer.subscriptionOrders()).isZero();
        assertThatThrownBy(() -> users.detail(9999999, f.actor(1))).isInstanceOf(NotFoundException.class);
        f.jdbc.execute("RENAME TABLE subscription_orders TO fixture_hidden_user_subscriptions");
        try {
            var legacy = users.detail(2, f.actor(1));
            assertThat(legacy.financialAvailable()).isFalse();
            assertThat(legacy.subscriptionOrders()).isZero();
            assertThat(legacy.ownedCars()).isEqualTo(3);
            assertThat(users.list(null,null,null,0,f.actor(1))).isNotEmpty();
            users.changeStatus(4, UserStatus.INACTIVE, f.actor(1));
            assertThat(users.detail(4, f.actor(1)).user().status()).isEqualTo(UserStatus.INACTIVE);
        } finally { f.jdbc.execute("RENAME TABLE fixture_hidden_user_subscriptions TO subscription_orders"); }
    }

    @Test void onlyCurrentActiveAdminMayReadOrMutateEvenWithStaleManagedActor() {
        var stale = f.actor(1);
        for (var actor : new com.carrent.security.CustomUserPrincipal[]{null, f.actor(2), f.actor(4)}) {
            assertThatThrownBy(() -> users.list(null,null,null,0,actor)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> users.detail(4,actor)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> users.changeStatus(4,UserStatus.BANNED,actor)).isInstanceOf(ForbiddenException.class);
        }
        for (String state : new String[]{"status='BANNED'", "status='INACTIVE'", "role='OWNER'"}) {
            f.tx.executeWithoutResult(s -> {
                f.em.find(User.class,1L);
                f.jdbc.update("UPDATE users SET "+state+" WHERE id=1");
                assertThatThrownBy(() -> users.changeStatus(4,UserStatus.BANNED,stale)).isInstanceOf(ForbiddenException.class);
                s.setRollbackOnly();
            });
            f.tx.executeWithoutResult(s -> {
                f.em.find(User.class,1L);
                f.jdbc.update("UPDATE users SET "+state+" WHERE id=1");
                assertThatThrownBy(() -> users.detail(4,stale)).isInstanceOf(ForbiddenException.class);
                s.setRollbackOnly();
            });
        }
        assertThat(users.detail(4,stale).user().status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test void changesOnlyStatusNeverSelfAndRollsBackWithSurroundingTransaction() {
        var actor = f.actor(1);
        var before = users.detail(4,actor).user();
        for (var state : UserStatus.values()) {
            users.changeStatus(4,state,actor);
            var after = users.detail(4,actor).user();
            assertThat(after.status()).isEqualTo(state);
            assertThat(after.email()).isEqualTo(before.email());
            assertThat(after.role()).isEqualTo(before.role());
            assertThatThrownBy(() -> users.changeStatus(1,state,actor)).isInstanceOf(ForbiddenException.class);
        }
        assertThatThrownBy(() -> users.changeStatus(4,null,actor)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> users.changeStatus(9999999,UserStatus.ACTIVE,actor)).isInstanceOf(NotFoundException.class);
        f.tx.executeWithoutResult(s -> { users.changeStatus(4,UserStatus.ACTIVE,actor); s.setRollbackOnly(); });
        assertThat(users.detail(4,actor).user().status()).isEqualTo(UserStatus.BANNED);
        assertThat(users.detail(1,actor).user().status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test void queuedChangeRejectsAdminBannedAfterPrincipalAndManagedUserWereCaptured() throws Exception {
        var actor = f.actor(1);
        var held = new java.util.concurrent.CountDownLatch(1);
        var captured = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var blocker = pool.submit(() -> f.tx.executeWithoutResult(s -> {
                var admin = f.em.find(User.class, 1L, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                held.countDown(); await(release);
                admin.setStatus(UserStatus.BANNED);
                f.em.flush();
            }));
            try {
                assertThat(held.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var queued = pool.submit(() -> {
                    try {
                        f.tx.executeWithoutResult(s -> {
                            f.em.find(User.class,1L);
                            captured.countDown();
                            users.changeStatus(4,UserStatus.BANNED,actor);
                        });
                        return true;
                    } catch (ForbiddenException expected) { return false; }
                });
                assertThat(captured.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                release.countDown();
                blocker.get(15,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(f.jdbc.queryForObject("SELECT status FROM users WHERE id=1",String.class)).isEqualTo("BANNED");
                assertThat(queued.get(15,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            } finally { release.countDown(); }
        }
        f.jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=1");
        assertThat(users.detail(4,actor).user().status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test void reciprocalAdminBansSerializeWithoutDeadlockAndOnlyOneCommits() throws Exception {
        addUser(99100,"Other Admin",Role.ADMIN,UserStatus.ACTIVE);
        var first = f.actor(1); var second = f.actor(99100);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                ready.countDown(); await(start);
                try { users.changeStatus(99100,UserStatus.BANNED,first); return true; }
                catch (ForbiddenException expected) { return false; }
            });
            var b = pool.submit(() -> {
                ready.countDown(); await(start);
                try { users.changeStatus(1,UserStatus.BANNED,second); return true; }
                catch (ForbiddenException expected) { return false; }
            });
            try {
                assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(a.get(15,java.util.concurrent.TimeUnit.SECONDS))
                        .isNotEqualTo(b.get(15,java.util.concurrent.TimeUnit.SECONDS));
            } finally { start.countDown(); }
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id IN (1,99100) AND status='BANNED'",Long.class)).isEqualTo(1);
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { if (!latch.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }

    private void addUser(long id, String name, Role role, UserStatus status) {
        f.jdbc.update("""
                INSERT INTO users(id,full_name,email,password_hash,phone,role,status)
                SELECT ?,?,?,password_hash,'0901234567',?,? FROM users WHERE id=1
                """, id, name, "admin-user-"+id+"@example.test", role.name(), status.name());
    }
}
