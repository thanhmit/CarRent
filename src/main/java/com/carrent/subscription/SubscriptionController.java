package com.carrent.subscription;

import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@ConditionalOnProperty(name = "carrent.subscription.enabled", havingValue = "true")
@RequestMapping("/owner/subscriptions")
public class SubscriptionController {
    private final SubscriptionService service;
    public SubscriptionController(SubscriptionService service) { this.service = service; }

    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    public String plans(@AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        service.requireOwner(actor);
        model.addAttribute("plans", SubscriptionPlan.values());
        return "subscription/plans";
    }

    @PostMapping("/orders")
    public String create(@RequestParam SubscriptionPlan plan, @AuthenticationPrincipal CustomUserPrincipal actor,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        try { return "redirect:/owner/subscriptions/orders/" + service.createOrder(plan, actor).orderId(); }
        catch (com.carrent.common.exception.BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/owner/subscriptions";
        }
    }

    @GetMapping("/orders/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        var order = service.detail(id, actor);
        model.addAttribute("order", order);
        return "subscription/detail";
    }

    @PostMapping("/orders/{id}/cancel")
    public String cancel(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor) {
        service.cancelOrder(id, actor);
        return "redirect:/owner/subscriptions/orders/" + id;
    }
}
