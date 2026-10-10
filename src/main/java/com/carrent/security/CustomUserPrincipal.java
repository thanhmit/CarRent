package com.carrent.security;

import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class CustomUserPrincipal extends org.springframework.security.core.userdetails.User {
    private final Long id;
    private final String fullName;
    private final Role role;

    private CustomUserPrincipal(User user) {
        super(user.getEmail(), user.getPasswordHash(), user.getStatus() == UserStatus.ACTIVE,
                true, true, user.getStatus() != UserStatus.BANNED,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.id = user.getId();
        this.fullName = user.getFullName();
        this.role = user.getRole();
    }

    public static CustomUserPrincipal from(User user) {
        return new CustomUserPrincipal(user);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return getUsername();
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }
}
