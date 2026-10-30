package com.carrent.payment;

import com.carrent.common.exception.BusinessException;
import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RefundController {
    private final RefundRecipientService recipients;
    public RefundController(RefundRecipientService recipients) { this.recipients = recipients; }

    @ModelAttribute
    void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @InitBinder("recipientForm")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("bankBin", "accountNumber", "saveForLater", "confirmedAccountName");
    }

    @GetMapping("/payment/refunds/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        var refund = recipients.view(id, actor);
        model.addAttribute("refund", refund);
        model.addAttribute("recipientForm", recipients.form(id, actor));
        return "payment/refund";
    }

    @PostMapping("/payment/refunds/{id}/recipient/preview")
    public String preview(@PathVariable Long id, @Valid @ModelAttribute("recipientForm") RefundRecipientForm form,
            BindingResult errors, @AuthenticationPrincipal CustomUserPrincipal actor, Model model, HttpServletResponse response) {
        model.addAttribute("refund", recipients.view(id, actor));
        if (errors.hasErrors()) { response.setStatus(400); return "payment/refund"; }
        try {
            var destination = recipients.preview(id, form, actor);
            form.setConfirmedAccountName(destination.accountName());
            model.addAttribute("destination", destination);
        } catch (BusinessException ex) {
            response.setStatus(409); model.addAttribute("errorMessage", ex.getMessage());
        }
        return "payment/refund";
    }

    @PostMapping("/payment/refunds/{id}/recipient")
    public String submit(@PathVariable Long id, @Valid @ModelAttribute("recipientForm") RefundRecipientForm form,
            BindingResult errors, @RequestParam(defaultValue = "false") boolean confirmed,
            @AuthenticationPrincipal CustomUserPrincipal actor, Model model, HttpServletResponse response,
            RedirectAttributes redirect) {
        model.addAttribute("refund", recipients.view(id, actor));
        if (errors.hasErrors()) { response.setStatus(400); return "payment/refund"; }
        try {
            if (!confirmed) throw new BusinessException("Vui lòng xác nhận đã kiểm tra đúng tài khoản nhận.");
            recipients.submit(id, form, actor);
        } catch (BusinessException ex) {
            response.setStatus(409); model.addAttribute("errorMessage", ex.getMessage());
            return "payment/refund";
        }
        redirect.addFlashAttribute("successMessage", "Đã lưu tài khoản nhận. Khoản hoàn đang chờ xử lý; chưa xác nhận đã chuyển tiền.");
        return "redirect:/payment/refunds/" + id;
    }
}
