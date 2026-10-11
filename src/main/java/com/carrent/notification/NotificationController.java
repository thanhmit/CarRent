package com.carrent.notification;

import com.carrent.security.CustomUserPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/notifications")
    public String list(@AuthenticationPrincipal CustomUserPrincipal principal,
                       @RequestParam(defaultValue = "0") long page,
                       @RequestParam(defaultValue = "10") int size, Model model) {
        int pageSize = Math.max(1, Math.min(size, 50));
        int pageNumber = (int) Math.max(0, Math.min(page, Integer.MAX_VALUE / pageSize));
        var found = notifications.getNotifications(principal.getId(),
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id")));
        model.addAttribute("notifications", found.getContent());
        model.addAttribute("page", found);
        model.addAttribute("paginationBaseUrl", "/notifications?size=" + pageSize);
        return "notification/list";
    }

    @PostMapping("/notifications/{id}/read")
    public String read(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal,
                       RedirectAttributes redirect) {
        notifications.markRead(id, principal.getId());
        redirect.addFlashAttribute("successMessage", "Đã đánh dấu thông báo là đã đọc.");
        return "redirect:/notifications";
    }

    @PostMapping("/notifications/read-all")
    public String readAll(@AuthenticationPrincipal CustomUserPrincipal principal, RedirectAttributes redirect) {
        notifications.markAllRead(principal.getId());
        redirect.addFlashAttribute("successMessage", "Đã đánh dấu tất cả thông báo là đã đọc.");
        return "redirect:/notifications";
    }
}
