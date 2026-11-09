package com.carrent.payment;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProfilePasswordTest {
    static PaymentFlowFixture f;
    static final long SELF = 95601;
    String oldPassword, newPassword;
    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(false, false); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        oldPassword = UUID.randomUUID().toString();
        newPassword = UUID.randomUUID().toString();
        f.jdbc.update("DELETE FROM users WHERE id=?", SELF);
        f.jdbc.update("""
                INSERT INTO users(id,full_name,email,password_hash,role,status)
                VALUES(?,'Đổi mật khẩu','password-profile@example.test',?,'CUSTOMER','ACTIVE')
                """, SELF, f.app.getBean(PasswordEncoder.class).encode(oldPassword));
    }

    @Test void passwordChangeRequiresCurrentPasswordAndOnlyUpdatesOwnHash() throws Exception {
        var before = f.jdbc.queryForMap("SELECT full_name,email,phone,role,status,created_at FROM users WHERE id=?", SELF);
        f.mvc.perform(post("/profile/change-password").with(user(f.actor(SELF))).with(csrf())
                .param("currentPassword", oldPassword).param("newPassword", newPassword).param("confirmPassword", newPassword)
                .param("userId", "4").param("role", "ADMIN").param("fullName", "Tampered"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/profile"));
        String hash = f.jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, SELF);
        var encoder = f.app.getBean(PasswordEncoder.class);
        assertThat(encoder.matches(newPassword, hash)).isTrue();
        assertThat(encoder.matches(oldPassword, hash)).isFalse();
        assertThat(f.jdbc.queryForMap("SELECT full_name,email,phone,role,status,created_at FROM users WHERE id=?", SELF)).isEqualTo(before);
    }

    @Test void validChangeThenRealLogoutLoginUsesNewPasswordAndGetLogoutDoesNotLogOut() throws Exception {
        var login = f.mvc.perform(post("/login").with(csrf()).param("username", "password-profile@example.test")
                .param("password", oldPassword)).andExpect(redirectedUrl("/cars")).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        f.mvc.perform(post("/profile/change-password").session(session).with(csrf())
                .param("currentPassword", oldPassword).param("newPassword", newPassword).param("confirmPassword", newPassword))
                .andExpect(redirectedUrl("/profile"));
        var before = f.db.legacySnapshot();
        f.mvc.perform(get("/logout").session(session)).andExpect(status().isNotFound());
        f.mvc.perform(get("/profile").session(session)).andExpect(status().isOk());
        assertThat(f.db.legacySnapshot()).isEqualTo(before);
        f.mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
        f.mvc.perform(post("/login").with(csrf()).param("username", "password-profile@example.test").param("password", oldPassword))
                .andExpect(redirectedUrl("/login?error"));
        f.mvc.perform(post("/login").with(csrf()).param("username", "password-profile@example.test").param("password", newPassword))
                .andExpect(redirectedUrl("/cars"));
    }

    @Test void invalidPasswordsKeepHashAndNeverRemainInModelOrRenderedInputs() throws Exception {
        var before = f.db.legacySnapshot();
        String tooLong = "é".repeat(37);
        for (String[] values : new String[][]{
                {"wrong-current", newPassword, newPassword}, {oldPassword, newPassword, "mismatch"},
                {oldPassword, "12345", "12345"}, {oldPassword, "", ""},
                {oldPassword, tooLong, tooLong}, {"é".repeat(37), newPassword, newPassword}}) {
            var result = f.mvc.perform(post("/profile/change-password").with(user(f.actor(SELF))).with(csrf())
                    .param("currentPassword", values[0]).param("newPassword", values[1]).param("confirmPassword", values[2]))
                    .andExpect(status().isOk()).andExpect(model().hasErrors()).andReturn();
            assertThat(f.db.legacySnapshot()).isEqualTo(before);
            var form = result.getModelAndView().getModel().get("form");
            for (String property : new String[]{"currentPassword", "newPassword", "confirmPassword"})
                assertThat(new org.springframework.beans.BeanWrapperImpl(form).getPropertyValue(property)).isNull();
            var errors = (org.springframework.validation.BindingResult) result.getModelAndView().getModel()
                    .get(org.springframework.validation.BindingResult.MODEL_KEY_PREFIX + "form");
            for (var error : errors.getFieldErrors()) assertThat(error.getRejectedValue()).isNull();
            String html = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(html.contains(oldPassword) || html.contains(newPassword) || html.contains(tooLong)).isFalse();
        }
    }

    @Test void exactUtf8BoundaryAndMinimumLengthAreAcceptedForAllRoles() throws Exception {
        for (String role : new String[]{"ADMIN", "OWNER", "CUSTOMER"}) {
            f.jdbc.update("UPDATE users SET role=? WHERE id=?", role, SELF);
            String next = role.equals("ADMIN") ? "é".repeat(36) : role.equals("OWNER") ? "123456" : "🙂".repeat(18);
            f.mvc.perform(get("/profile/change-password").with(user(f.actor(SELF)))).andExpect(status().isOk());
            f.mvc.perform(post("/profile/change-password").with(user(f.actor(SELF))).with(csrf())
                    .param("currentPassword", oldPassword).param("newPassword", next).param("confirmPassword", next))
                    .andExpect(status().is3xxRedirection());
            oldPassword = next;
        }
    }

    @Test void csrfAnonymousStaleRoleAndInactiveUserCannotChangePassword() throws Exception {
        var actor = f.actor(SELF);
        var before = f.db.legacySnapshot();
        f.mvc.perform(get("/profile/change-password")).andExpect(status().is3xxRedirection());
        f.mvc.perform(passwordPost().with(csrf())).andExpect(status().is3xxRedirection());
        f.mvc.perform(passwordPost().with(user(actor))).andExpect(status().isForbidden());
        f.mvc.perform(passwordPost().with(user(actor)).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        assertThat(f.db.legacySnapshot()).isEqualTo(before);
        for (String state : new String[]{"INACTIVE", "BANNED"}) {
            f.jdbc.update("UPDATE users SET status=? WHERE id=?", state, SELF);
            f.mvc.perform(get("/profile/change-password").with(user(actor))).andExpect(status().isForbidden());
            f.mvc.perform(passwordPost().with(user(actor)).with(csrf())).andExpect(status().isForbidden());
        }
        f.jdbc.update("UPDATE users SET status='ACTIVE',role='OWNER' WHERE id=?", SELF);
        f.mvc.perform(passwordPost().with(user(actor)).with(csrf())).andExpect(status().isForbidden());
    }

    @Test void concurrentChangesAcceptOldPasswordOnlyOnceAndProfileUpdateDoesNotRestoreOldHash() throws Exception {
        var actor = f.actor(SELF);
        var start = new java.util.concurrent.CountDownLatch(1);
        var requests = new java.util.ArrayList<java.util.concurrent.FutureTask<Integer>>();
        for (int i = 0; i < 2; i++) {
            var request = new java.util.concurrent.FutureTask<Integer>(() -> {
                start.await();
                return f.mvc.perform(passwordPost().with(user(actor)).with(csrf())).andReturn().getResponse().getStatus();
            });
            requests.add(request); Thread.ofVirtual().start(request);
        }
        start.countDown();
        assertThat(java.util.List.of(requests.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS),
                requests.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(302, 200);
        f.mvc.perform(post("/profile/edit").with(user(actor)).with(csrf()).param("fullName", "Tên sau đổi mật khẩu"))
                .andExpect(status().is3xxRedirection());
        String hash = f.jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, SELF);
        assertThat(f.app.getBean(PasswordEncoder.class).matches(newPassword, hash)).isTrue();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder passwordPost() {
        return post("/profile/change-password").param("currentPassword", oldPassword)
                .param("newPassword", newPassword).param("confirmPassword", newPassword);
    }
}
