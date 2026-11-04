package com.carrent.admin;

import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class AdminController {
    private final AdminService admins;
    public AdminController(AdminService admins) { this.admins = admins; }
    @GetMapping("/admin/dashboard")
    public String dashboard(@AuthenticationPrincipal CustomUserPrincipal actor, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!admins.available(actor)) return "admin/unavailable";
        model.addAttribute("stats", admins.getDashboardStats(actor));
        return "admin/dashboard";
    }
}
