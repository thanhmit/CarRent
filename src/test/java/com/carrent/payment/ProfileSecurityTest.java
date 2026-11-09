package com.carrent.payment;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfileSecurityTest {
    static PaymentFlowFixture f;
    static final long SELF = 95501, OTHER = 95502;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(false, false); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM users WHERE id IN (?,?)", SELF, OTHER);
        for (long id : new long[]{SELF, OTHER}) f.jdbc.update("""
                INSERT INTO users(id,full_name,email,password_hash,phone,role,status)
                SELECT ?,?,?,password_hash,'0901234567','CUSTOMER','ACTIVE' FROM users WHERE id=4
                """, id, id == SELF ? "Hồ sơ riêng" : "Người khác", "profile-" + id + "@example.test");
    }

    @Test void profileReadsCurrentAccountAndExcludesPasswordHash() throws Exception {
        String html = f.mvc.perform(get("/profile").with(user(f.actor(SELF))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Hồ sơ riêng", "profile-95501@example.test", "0901234567", "/profile/edit")
                .doesNotContain("profile-95502@example.test", "password_hash", "passwordHash", "$2a$");
    }

    @Test void editChangesOnlyOwnNameAndPhoneDespiteProtectedFieldsAndAnotherUserId() throws Exception {
        var protectedFields = f.jdbc.queryForMap("SELECT email,role,status,password_hash,created_at FROM users WHERE id=?", SELF);
        var other = f.jdbc.queryForMap("SELECT * FROM users WHERE id=?", OTHER);
        f.mvc.perform(post("/profile/edit").with(user(f.actor(SELF))).with(csrf())
                .param("fullName", "  Tên đã sửa  ").param("phone", " 0912345678 ")
                .param("id", "95502").param("userId", "95502").param("email", "attacker@example.test")
                .param("role", "ADMIN").param("status", "BANNED").param("passwordHash", "tampered"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/profile"));
        String html = f.mvc.perform(get("/profile").with(user(f.actor(SELF))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Tên đã sửa", "0912345678");
        assertThat(f.jdbc.queryForMap("SELECT email,role,status,password_hash,created_at FROM users WHERE id=?", SELF)).isEqualTo(protectedFields);
        assertThat(f.jdbc.queryForMap("SELECT * FROM users WHERE id=?", OTHER)).isEqualTo(other);
    }

    @Test void invalidInputsAreEscapedAndNeverMutateWhileBlankPhoneIsAllowed() throws Exception {
        var before = f.jdbc.queryForMap("SELECT * FROM users WHERE id=?", SELF);
        for (String name : new String[]{"", "   ", "a".repeat(101)}) {
            f.mvc.perform(post("/profile/edit").with(user(f.actor(SELF))).with(csrf())
                    .param("fullName", name).param("phone", "0901234567"))
                    .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "fullName"));
            assertThat(f.jdbc.queryForMap("SELECT * FROM users WHERE id=?", SELF)).isEqualTo(before);
        }
        String html = f.mvc.perform(post("/profile/edit").with(user(f.actor(SELF))).with(csrf())
                        .param("fullName", "<script>window.p55xss=1</script>").param("phone", "1".repeat(21)))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "phone"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("&lt;script&gt;", "window.p55xss=1").doesNotContain("<script>window.p55xss");
        assertThat(f.jdbc.queryForMap("SELECT * FROM users WHERE id=?", SELF)).isEqualTo(before);
        f.mvc.perform(post("/profile/edit").with(user(f.actor(SELF))).with(csrf())
                .param("fullName", "a".repeat(100)).param("phone", " ")).andExpect(status().is3xxRedirection());
        assertThat(f.jdbc.queryForObject("SELECT phone FROM users WHERE id=?", String.class, SELF)).isNull();
    }

    @Test void everyRoleCanUseOwnProfileButAnonymousAndInvalidCsrfCannotMutate() throws Exception {
        for (String role : new String[]{"ADMIN", "OWNER", "CUSTOMER"}) {
            f.jdbc.update("UPDATE users SET role=? WHERE id=?", role, SELF);
            for (String url : new String[]{"/profile", "/profile/edit"})
                f.mvc.perform(get(url).with(user(f.actor(SELF)))).andExpect(status().isOk());
            f.mvc.perform(post("/profile/edit").with(user(f.actor(SELF))).with(csrf())
                    .param("fullName", role).param("phone", "1".repeat(20))).andExpect(status().is3xxRedirection());
        }
        var before = f.db.legacySnapshot();
        f.mvc.perform(get("/profile")).andExpect(status().is3xxRedirection());
        f.mvc.perform(post("/profile/edit").with(csrf()).param("fullName", "Anonymous"))
                .andExpect(status().is3xxRedirection());
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                post("/profile/edit").with(user(f.actor(SELF))),
                post("/profile/edit").with(user(f.actor(SELF))).with(csrf().useInvalidToken())})
            f.mvc.perform(request.param("fullName", "Forbidden")).andExpect(status().isForbidden());
        f.mvc.perform(get("/profile/95502").with(user(f.actor(SELF)))).andExpect(status().isNotFound());
        assertThat(f.db.legacySnapshot()).isEqualTo(before);
    }

    @Test void staleStatusAndRoleAreRejectedEvenWhenFormIsInvalid() throws Exception {
        var actor = f.actor(SELF);
        for (String state : new String[]{"INACTIVE", "BANNED"}) {
            f.jdbc.update("UPDATE users SET status=? WHERE id=?", state, SELF);
            for (String path : new String[]{"/profile", "/profile/edit"})
                f.mvc.perform(get(path).with(user(actor))).andExpect(status().isForbidden());
            var before = f.db.legacySnapshot();
            f.mvc.perform(post("/profile/edit").with(user(actor)).with(csrf()).param("fullName", ""))
                    .andExpect(status().isForbidden());
            assertThat(f.db.legacySnapshot()).isEqualTo(before);
        }
        f.jdbc.update("UPDATE users SET status='ACTIVE',role='OWNER' WHERE id=?", SELF);
        f.mvc.perform(get("/profile").with(user(actor))).andExpect(status().isForbidden());
        f.mvc.perform(post("/profile/edit").with(user(actor)).with(csrf()).param("fullName", "Changed"))
                .andExpect(status().isForbidden());
    }

    @Test void profileEditWaitingForUserLockRechecksCommittedBan() throws Exception {
        var actor = f.actor(SELF);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocker = java.util.concurrent.CompletableFuture.runAsync(() -> f.tx.executeWithoutResult(tx -> {
            var user = f.em.find(com.carrent.user.User.class, SELF, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            locked.countDown();
            try { if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Lock timeout"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            user.setStatus(com.carrent.user.UserStatus.BANNED);
        }));
        assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var request = new java.util.concurrent.FutureTask<org.springframework.test.web.servlet.MvcResult>(() ->
                f.mvc.perform(post("/profile/edit").with(user(actor)).with(csrf())
                        .param("fullName", "Must not persist")).andReturn());
        Thread.ofVirtual().start(request);
        try {
            org.awaitility.Awaitility.await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).until(() ->
                    f.jdbc.queryForObject("""
                            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID
                            WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='users'
                            """, Long.class, f.db.name) > 0);
        } finally { release.countDown(); }
        blocker.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(request.get(10, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
        assertThat(f.jdbc.queryForObject("SELECT full_name FROM users WHERE id=?", String.class, SELF)).isEqualTo("Hồ sơ riêng");
    }
}
