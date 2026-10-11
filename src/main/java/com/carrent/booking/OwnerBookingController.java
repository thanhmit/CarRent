package com.carrent.booking;

import com.carrent.car.CarImageRepository;
import com.carrent.common.exception.BusinessException;
import com.carrent.payment.PaymentRepository;
import com.carrent.payment.PaymentStatus;
import com.carrent.security.CustomUserPrincipal;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
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
public class OwnerBookingController {
    private final BookingService bookings;
    private final CarImageRepository images;
    private final PaymentRepository payments;

    public OwnerBookingController(BookingService bookings, CarImageRepository images, PaymentRepository payments) {
        this.bookings = bookings;
        this.images = images;
        this.payments = payments;
    }

    @GetMapping("/owner/bookings")
    public String list(@AuthenticationPrincipal CustomUserPrincipal principal,
                       @RequestParam(defaultValue = "0") long page,
                       @RequestParam(defaultValue = "10") int size, Model model) {
        int pageSize = Math.max(1, Math.min(size, 50));
        int pageNumber = (int) Math.max(0, Math.min(page, Integer.MAX_VALUE / pageSize));
        var found = bookings.findBookingsForOwner(principal,
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id")));
        var ids = found.getContent().stream().map(booking -> booking.getCar().getId()).distinct().toList();
        Map<Long, String> thumbnails = ids.isEmpty() ? Map.of() : images.findFirstImagesByCarIds(ids).stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> (String) row[1]));
        model.addAttribute("bookings", found.getContent());
        model.addAttribute("page", found);
        model.addAttribute("thumbnails", thumbnails);
        model.addAttribute("paginationBaseUrl", "/owner/bookings?size=" + pageSize);
        model.addAttribute("ownerName", principal.getFullName());
        return "owner/bookings";
    }

    @GetMapping("/owner/bookings/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal, Model model) {
        var booking = bookings.getBookingForView(id, principal);
        model.addAttribute("booking", booking);
        model.addAttribute("car", booking.getCar());
        model.addAttribute("days", ChronoUnit.DAYS.between(booking.getStartDate(), booking.getEndDate()));
        model.addAttribute("hasPaid", payments.existsByBookingIdAndStatus(id, PaymentStatus.PAID));
        model.addAttribute("canComplete", booking.getStatus() == BookingStatus.CONFIRMED
                && booking.getEndDate().isBefore(LocalDate.now()));
        var found = images.findByCarIdOrderByIdAsc(booking.getCar().getId());
        model.addAttribute("thumbnail", found.isEmpty() ? null : found.getFirst().getImageUrl());
        model.addAttribute("ownerName", principal.getFullName());
        return "owner/booking-detail";
    }

    @PostMapping("/owner/bookings/{id}/confirm")
    public String confirm(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            bookings.confirmBooking(id, principal);
            redirect.addFlashAttribute("successMessage", "Đã xác nhận đơn thuê.");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/owner/bookings/" + id;
    }

    @PostMapping("/owner/bookings/{id}/complete")
    public String complete(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal,
                           RedirectAttributes redirect) {
        try {
            bookings.completeBooking(id, principal);
            redirect.addFlashAttribute("successMessage", "Đã hoàn tất đơn thuê.");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/owner/bookings/" + id;
    }

    @PostMapping("/owner/bookings/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String reason,
                         @AuthenticationPrincipal CustomUserPrincipal principal, RedirectAttributes redirect) {
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isBlank() || trimmedReason.length() > 500) {
            redirect.addFlashAttribute("errorMessage", "Vui lòng nhập lý do từ chối từ 1 đến 500 ký tự.");
            return "redirect:/owner/bookings/" + id;
        }
        try {
            bookings.rejectBooking(id, principal, trimmedReason);
            redirect.addFlashAttribute("successMessage", "Đã từ chối đơn thuê.");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/owner/bookings/" + id;
    }
}
