package com.carrent.auth;

import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserRepository;
import com.carrent.user.UserStatus;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(RegisterForm form) {
        if (form.getRole() != Role.CUSTOMER && form.getRole() != Role.OWNER) {
            throw new RegistrationException("role", "Vai trò không hợp lệ");
        }
        if (form.getPassword() == null || form.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new RegistrationException("password", "Mật khẩu không được quá 72 byte UTF-8");
        }
        if (!form.getPassword().equals(form.getConfirmPassword())) {
            throw new RegistrationException("confirmPassword", "Mật khẩu xác nhận không khớp");
        }

        String email = form.getEmail().trim();
        if (users.existsByEmail(email)) {
            throw new RegistrationException("email", "Email đã được sử dụng");
        }
        User user = User.builder()
                .fullName(form.getFullName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(form.getPassword()))
                .phone(form.getPhone() == null || form.getPhone().isBlank() ? null : form.getPhone().trim())
                .role(form.getRole())
                .status(UserStatus.ACTIVE)
                .build();
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                    throw new RegistrationException("email", "Email đã được sử dụng");
                }
            }
            throw exception;
        }
    }

    public static class RegistrationException extends RuntimeException {
        private final String field;

        public RegistrationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String getField() {
            return field;
        }
    }
}
