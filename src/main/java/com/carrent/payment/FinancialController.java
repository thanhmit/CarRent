package com.carrent.payment;

import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class FinancialController {
    private final FinancialViews views;
    public FinancialController(FinancialViews views) { this.views = views; }

    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping("/payment/finances")
    public String list(@RequestParam(defaultValue = "paid") String view, @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        if (!views.available(view, actor)) return "payment/finance-unavailable";
        model.addAttribute("view", view);
        model.addAttribute("obligations", views.obligations(view, page, actor));
        model.addAttribute("refunds", views.refunds(view, page, actor));
        model.addAttribute("reviewReceipts", views.reviewReceipts(view, page, actor));
        model.addAttribute("pageNumber", Math.clamp(page, 0, 100000));
        return "payment/finances";
    }

    @GetMapping("/payment/finances/refunds/{id}")
    public String refund(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        if (!views.available("paid", actor)) return "payment/finance-unavailable";
        model.addAttribute("refund", views.refund(id, actor));
        return "payment/refund-summary";
    }

    @GetMapping("/payment/refunds/{id}/status")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> status(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor) {
        if (!views.available("paid", actor)) return org.springframework.http.ResponseEntity.status(503)
                .body(java.util.Map.of("message", "Chức năng tài chính chưa sẵn sàng."));
        return org.springframework.http.ResponseEntity.ok(views.status(id, actor));
    }
}
