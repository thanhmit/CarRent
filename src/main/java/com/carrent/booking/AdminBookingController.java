package com.carrent.booking;

import com.carrent.admin.AdminBookingService;
import com.carrent.admin.AdminService;
import com.carrent.admin.AdminFinanceService;
import com.carrent.security.CustomUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminBookingController {
    private final BookingService bookings;
    private final BookingTimeline timeline;
    private final AdminService admins;
    private final AdminBookingService listing;
    private final AdminFinanceService finance;

    public AdminBookingController(BookingService bookings, BookingTimeline timeline, AdminService admins,
            AdminBookingService listing, AdminFinanceService finance) {
        this.bookings = bookings; this.timeline = timeline; this.admins=admins; this.listing=listing; this.finance=finance;
    }

    @GetMapping("/admin/bookings")
    public String list(@RequestParam(required=false) BookingStatus status,
            @RequestParam(defaultValue="ALL") AdminBookingService.Attention attention,
            @RequestParam(defaultValue="0") int page, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        model.addAttribute("rows",listing.list(status,attention,page,actor));
        model.addAttribute("statusFilter",status);
        model.addAttribute("attention",attention);
        model.addAttribute("statuses",BookingStatus.values());
        model.addAttribute("statusLabels",java.util.Map.of("PENDING","Chờ xử lý","CONFIRMED","Đã xác nhận",
                "REJECTED","Đã từ chối","CANCELLED","Đã hủy","COMPLETED","Hoàn thành","EXPIRED","Đã hết hạn"));
        return "admin/bookings";
    }

    @GetMapping("/admin/bookings/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        admins.requireAdmin(actor);
        model.addAttribute("booking", bookings.getBookingForView(id, actor));
        var progress=timeline.view(id, actor);
        model.addAttribute("timeline", progress);
        if(progress!=null) model.addAttribute("finance",finance.booking(id,actor));
        return "admin/booking-detail";
    }
}
