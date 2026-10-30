package com.carrent.config;

import com.carrent.security.CustomUserDetailsService;
import com.carrent.security.RoleBasedAuthSuccessHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CustomUserDetailsService users) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/webhooks/sepay/{channelId}")))
                .userDetailsService(users)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/sepay/{channelId}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/cars", "/cars/**").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/uploads/**", "/favicon.ico").permitAll()
                        .requestMatchers("/login", "/register", "/access-denied").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/owner/**").hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/bookings/new", "/bookings").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/bookings", "/bookings/*/cancel").hasRole("CUSTOMER")
                        .requestMatchers("/bookings/**").authenticated()
                        .anyRequest().authenticated())
                .formLogin(login -> login
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .failureUrl("/login?error")
                        .successHandler(new RoleBasedAuthSuccessHandler()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true))
                .exceptionHandling(errors -> errors.accessDeniedPage("/access-denied"));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
