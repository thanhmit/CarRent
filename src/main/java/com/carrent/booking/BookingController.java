package com.carrent.booking;

import com.carrent.car.CarImageRepository;
import com.carrent.car.CarService;
import com.carrent.car.ApprovalStatus;
import com.carrent.car.ListingStatus;
import com.carrent.payment.PaymentRepository;
import com.carrent.payment.PaymentStatus;
import com.carrent.common.exception.BusinessException;
import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BookingController {
    private final BookingService bookings;
    private final CarService cars;
    private final CarImageRepository images;
    private final PaymentRepository payments;

    public BookingController(BookingService bookings, CarService cars, CarImageRepository images, PaymentRepository payments) {
        this.bookings = bookings;
        this.cars = cars;
        this.images = images;
        this.payments = payments;
    }

    @GetMapping("/bookings/new")
    public String createForm(@RequestParam(required = false) Long carId,
                             @ModelAttribute("form") BookingCreateForm form, BindingResult result,
                             @AuthenticationPrincipal CustomUserPrincipal principal,
                             HttpServletResponse response, Model model) {
        populateForm(carId, principal, model);
        form.setCarId(carId);
        if (result.hasErrors()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }
        return "booking/create";
    }

    @PostMapping("/bookings")
    public String create(@Valid @ModelAttribute("form") BookingCreateForm form, BindingResult result,
                         @AuthenticationPrincipal CustomUserPrincipal principal, Model model,
                         HttpServletResponse response, RedirectAttributes redirect) {
        if (form.getCarId() == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("errorMessage", "Vui lòng chọn xe trước khi đặt thuê.");
            return "error/400";
        }
        if (result.hasErrors()) {
            populateForm(form.getCarId(), principal, model);
            return "booking/create";
        }
        try {
            Booking booking = bookings.createBooking(form, principal);
            redirect.addFlashAttribute("successMessage", "Đặt xe thành công!");
            return "redirect:/bookings/" + booking.getId();
        } catch (BusinessException ex) {
            result.reject("booking.unavailable", ex.getMessage());
            populateForm(form.getCarId(), principal, model);
            return "booking/create";
        }
    }

    private void populateForm(Long carId, CustomUserPrincipal principal, Model model) {
        model.addAttribute("customer", bookings.getCustomerForBooking(principal));
        var car = cars.getPublicCar(carId, null);
        model.addAttribute("car", car);
        var found = images.findByCarIdOrderByIdAsc(car.getId());
        model.addAttribute("thumbnail", found.isEmpty() ? null : found.getFirst().getImageUrl());
        model.addAttribute("today", LocalDate.now());
    }

    @GetMapping("/bookings")
    public String list(@AuthenticationPrincipal CustomUserPrincipal principal,
                       @RequestParam(defaultValue = "0") long page,
                       @RequestParam(defaultValue = "10") int size, Model model) {
        int pageSize = Math.max(1, Math.min(size, 50));
        int pageNumber = (int) Math.max(0, Math.min(page, Integer.MAX_VALUE / pageSize));
        var found = bookings.findBookingsForCustomer(principal,
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id")));
        var ids = found.getContent().stream().map(booking -> booking.getCar().getId()).distinct().toList();
        Map<Long, String> thumbnails = ids.isEmpty() ? Map.of() : images.findFirstImagesByCarIds(ids).stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> (String) row[1]));
        model.addAttribute("bookings", found.getContent());
        model.addAttribute("page", found);
        model.addAttribute("thumbnails", thumbnails);
        model.addAttribute("paginationBaseUrl", "/bookings?size=" + pageSize);
        return "booking/list";
    }

    @GetMapping("/bookings/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal, Model model) {
        var booking = bookings.getBookingForView(id, principal);
        var car = booking.getCar();
        model.addAttribute("booking", booking);
        model.addAttribute("car", car);
        model.addAttribute("carPublic", car.getApprovalStatus() == ApprovalStatus.APPROVED
                && car.getListingStatus() == ListingStatus.ACTIVE);
        model.addAttribute("days", ChronoUnit.DAYS.between(booking.getStartDate(), booking.getEndDate()));
        model.addAttribute("hasPaid", payments.existsByBookingIdAndStatus(id, PaymentStatus.PAID));
        var found = images.findByCarIdOrderByIdAsc(car.getId());
        model.addAttribute("thumbnail", found.isEmpty() ? null : found.getFirst().getImageUrl());
        return "booking/detail";
    }

    @PostMapping("/bookings/{id}/cancel")
    public String cancel(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            bookings.cancelBooking(id, principal);
            redirect.addFlashAttribute("successMessage", "Đã hủy đơn thuê. Khoản đã thanh toán được hoàn tiền (giả lập).");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/bookings/" + id;
    }
}
