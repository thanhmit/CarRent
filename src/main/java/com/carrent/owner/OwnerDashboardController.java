package com.carrent.owner;

import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OwnerDashboardController {
    private final OwnerDashboardService dashboards;
    public OwnerDashboardController(OwnerDashboardService dashboards) { this.dashboards=dashboards; }
    @GetMapping("/owner/dashboard")
    public String dashboard(@AuthenticationPrincipal CustomUserPrincipal actor, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var dashboard=dashboards.getDashboard(actor);
        model.addAttribute("stats",dashboard.stats());
        model.addAttribute("subscription",dashboard.subscription());
        model.addAttribute("subscriptionAvailable",dashboard.subscriptionAvailable());
        return "owner/dashboard";
    }
}
