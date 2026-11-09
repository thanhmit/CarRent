package com.carrent.user;

import com.carrent.common.exception.ForbiddenException;
import com.carrent.security.CustomUserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {
    private final EntityManager em;
    private final Validator validator;
    private final PasswordEncoder passwords;

    public UserService(EntityManager em, Validator validator, PasswordEncoder passwords) {
        this.em = em;
        this.validator = validator;
        this.passwords = passwords;
    }

    public Profile profile(CustomUserPrincipal actor) {
        User user = current(actor, false);
        return new Profile(user.getFullName(), user.getEmail(), user.getPhone(), user.getRole(), user.getCreatedAt());
    }

    @Transactional
    public void updateProfile(CustomUserPrincipal actor, ProfileForm form) {
        User user = current(actor, true);
        validate(form);
        user.setFullName(form.getFullName());
        user.setPhone(form.getPhone());
    }

    @Transactional
    public void changePassword(CustomUserPrincipal actor, ChangePasswordForm form) {
        User user = current(actor, true);
        validate(form);
        if (form.getNewPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new FormException("newPassword", "Mật khẩu mới không được quá 72 byte UTF-8");
        }
        if (!form.getNewPassword().equals(form.getConfirmPassword())) {
            throw new FormException("confirmPassword", "Mật khẩu xác nhận không khớp");
        }
        // Verify the locked DB hash, never the hash cached in the session principal.
        if (form.getCurrentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(form.getCurrentPassword(), user.getPasswordHash())) {
            throw new FormException("currentPassword", "Mật khẩu hiện tại không đúng");
        }
        user.setPasswordHash(passwords.encode(form.getNewPassword()));
    }

    private void validate(Object form) {
        for (var error : validator.validate(form)) {
            throw new FormException(error.getPropertyPath().toString(), error.getMessage());
        }
    }

    private User current(CustomUserPrincipal actor, boolean lock) {
        User user = null;
        if (actor != null) {
            // Re-read after waiting for the same row lock used by Admin status changes.
            if (lock) em.detach(em.getReference(User.class, actor.getId()));
            user = lock ? em.find(User.class, actor.getId(), LockModeType.PESSIMISTIC_WRITE)
                    : em.find(User.class, actor.getId());
        }
        if (user == null || user.getStatus() != UserStatus.ACTIVE || user.getRole() != actor.getRole()) {
            throw new ForbiddenException("Tài khoản không còn quyền truy cập. Vui lòng đăng nhập lại.");
        }
        return user;
    }

    public record Profile(String fullName, String email, String phone, Role role, LocalDateTime createdAt) {}

    public static class FormException extends RuntimeException {
        private final String field;

        public FormException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String getField() { return field; }
    }
}
