package com.carrent.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

public class RoleBasedAuthSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        requestCache.removeRequest(request, response);
        clearAuthenticationAttributes(request);
        CustomUserPrincipal principal = (CustomUserPrincipal) authentication.getPrincipal();
        String target = switch (principal.getRole()) {
            case ADMIN -> "/admin/dashboard";
            case OWNER -> "/owner/dashboard";
            case CUSTOMER -> "/cars";
        };
        getRedirectStrategy().sendRedirect(request, response, target);
    }
}
