package com.carrent.admin;

import com.carrent.admin.AdminFinanceService.*;
import com.carrent.payment.PaymentSubjectType;
import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/admin/finance")
public class AdminFinanceController {
    private final AdminService admins;
    private final AdminFinanceService finances;
    public AdminFinanceController(AdminService admins, AdminFinanceService finances) { this.admins=admins; this.finances=finances; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    public String list(@RequestParam(defaultValue="OBLIGATIONS") Kind kind,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="false") boolean overdue,
            @RequestParam(required=false) PaymentSubjectType subjectType, @RequestParam(required=false) Long subjectId,
            @RequestParam(required=false) Long collectorId, @RequestParam(defaultValue="LATEST") Order sort,
            @RequestParam(defaultValue="0") int page, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        if (!admins.available(actor)) return "admin/unavailable";
        var filter=new Filter(kind,status,overdue,subjectType,subjectId,collectorId,sort,page);
        model.addAttribute("filter",filter);
        model.addAttribute("rows",finances.list(filter,actor));
        model.addAttribute("statuses",finances.statuses(kind));
        return "admin/finance";
    }

    @GetMapping("/{kind}/{id}")
    public String detail(@PathVariable Kind kind, @PathVariable long id,
            @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        if (!admins.available(actor)) return "admin/unavailable";
        model.addAttribute("kind",kind);
        model.addAttribute("row",finances.detail(kind,id,actor));
        return "admin/finance-detail";
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badFilter(Model model) {
        model.addAttribute("errorMessage","Điều kiện lọc bạn gửi không hợp lệ. Vui lòng kiểm tra lại.");
        return "error/400";
    }
}
