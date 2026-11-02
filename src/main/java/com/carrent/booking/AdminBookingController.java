package com.carrent.booking;

import com.carrent.security.CustomUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class AdminBookingController {
    private final BookingService bookings;
    private final BookingTimeline timeline;

    public AdminBookingController(BookingService bookings, BookingTimeline timeline) {
        this.bookings = bookings; this.timeline = timeline;
    }

    @GetMapping("/admin/bookings/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        model.addAttribute("booking", bookings.getBookingForView(id, actor));
        model.addAttribute("timeline", timeline.view(id, actor));
        return "admin/booking-detail";
    }
}
