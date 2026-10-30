package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PaymentController {
    private final PaymentObligationService payments;
    public PaymentController(PaymentObligationService payments) { this.payments = payments; }

    @ModelAttribute
    void preventCaching(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @PostMapping("/payment/obligations/{id}/attempts")
    public String open(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor) {
        return "redirect:/payment/intents/" + payments.openAttempt(id, actor).id();
    }

    @PostMapping("/payment/intents/{id}/cancel")
    public String cancel(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor) {
        payments.cancelAttempt(id, actor);
        return "redirect:/payment/intents/" + id + "/result";
    }

    @GetMapping("/payment/intents/{id}")
    public String checkout(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        var view = payments.status(id, actor);
        model.addAttribute("payment", view);
        return "ACTIVE".equals(view.status()) ? "payment/checkout" : "payment/result";
    }

    @GetMapping("/payment/intents/{id}/result")
    public String result(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        model.addAttribute("payment", payments.status(id, actor));
        return "payment/result";
    }

    @GetMapping("/payment/intents/{id}/status")
    @ResponseBody
    public PaymentIntentView status(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor) {
        return payments.status(id, actor);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> unavailable(BusinessException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }
}
