package com.carrent.security;

import com.carrent.user.User;
import com.carrent.user.UserRepository;
import com.carrent.user.UserStatus;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public CustomUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = users.findByEmail(identifier)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new DisabledException("Account is disabled");
        }
        return CustomUserPrincipal.from(user);
    }
}
