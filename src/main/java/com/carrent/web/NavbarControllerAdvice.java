package com.carrent.web;

import com.carrent.notification.NotificationService;
import com.carrent.security.CustomUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class NavbarControllerAdvice {
    private final NotificationService notifications;

    public NavbarControllerAdvice(NotificationService notifications) {
        this.notifications = notifications;
    }

    @ModelAttribute("unreadCount")
    public long unreadCount(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserPrincipal principal
                ? notifications.countUnread(principal.getId()) : 0;
    }
}
