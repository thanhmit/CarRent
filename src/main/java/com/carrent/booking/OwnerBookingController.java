package com.carrent.booking;

import com.carrent.car.CarImageRepository;
import com.carrent.concurrency.CarCommandExecutor;
import com.carrent.common.exception.BusinessException;
import com.carrent.payment.PaymentRepository;
import com.carrent.payment.PaymentStatus;
import com.carrent.payment.PaymentObligationRepository;
import com.carrent.inspection.BookingInspectionRepository;
import com.carrent.inspection.InspectionPhase;
import com.carrent.inspection.InspectionStatus;
import com.carrent.inspection.InspectionService;
import com.carrent.inspection.InspectionEventAction;
import com.carrent.security.CustomUserPrincipal;
import jakarta.validation.Valid;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
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
    private final CarCommandExecutor commands;
    private final java.time.Clock clock;
    private final BookingInspectionRepository inspections;
    private final PaymentObligationRepository obligations;
    private final InspectionService inspectionViews;

    public OwnerBookingController(BookingService bookings, CarImageRepository images, PaymentRepository payments,
                                  CarCommandExecutor commands, java.time.Clock clock,
                                  BookingInspectionRepository inspections, PaymentObligationRepository obligations,
                                  InspectionService inspectionViews) {
        this.bookings = bookings;
        this.images = images;
        this.payments = payments;
        this.commands = commands;
        this.clock = clock;
        this.inspections = inspections;
        this.obligations = obligations;
        this.inspectionViews = inspectionViews;
    }

    @InitBinder("returnForm")
    void bindReturnForm(WebDataBinder binder) {
        binder.setAllowedFields("decision", "note");
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
        if (!model.containsAttribute("returnForm")) model.addAttribute("returnForm", new ReturnReceiptForm());
        if ("VIETQR_V1".equals(booking.getWorkflowVersion()) && booking.isInspectionRequired()) {
            var after = inspections.findByBookingIdAndPhase(id, InspectionPhase.AFTER)
                    .filter(report -> report.getStatus() != InspectionStatus.DRAFT).orElse(null);
            model.addAttribute("hasAfterReport", after != null);
            model.addAttribute("afterReportStatus", after == null ? null : after.getStatus());
            model.addAttribute("returnNotReceived", after != null && booking.getVehicleReturnedAt() == null
                    && inspectionViews.getView(id, InspectionPhase.AFTER, principal).events().stream()
                        .anyMatch(event -> event.action() == InspectionEventAction.RETURN_NOT_RECEIVED));
            model.addAttribute("balanceObligation", obligations.findByBusinessKey("BOOKING:" + id + ":BALANCE").orElse(null));
        }
        model.addAttribute("car", booking.getCar());
        model.addAttribute("days", ChronoUnit.DAYS.between(booking.getStartDate(), booking.getEndDate()));
        model.addAttribute("hasPaid", payments.existsByBookingIdAndStatus(id, PaymentStatus.PAID));
        model.addAttribute("canComplete", !"VIETQR_V1".equals(booking.getWorkflowVersion()) && booking.getStatus() == BookingStatus.CONFIRMED
                && booking.getEndDate().isBefore(LocalDate.now()));
        var today = clock.instant().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        model.addAttribute("handoverDateAllowed", !today.isBefore(booking.getStartDate()) && today.isBefore(booking.getEndDate()));
        var found = images.findByCarIdOrderByIdAsc(booking.getCar().getId());
        model.addAttribute("thumbnail", found.isEmpty() ? null : found.getFirst().getImageUrl());
        model.addAttribute("ownerName", principal.getFullName());
        return "owner/booking-detail";
    }

    @PostMapping("/owner/bookings/{id}/confirm")
    public String confirm(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            var booking = bookings.getBookingForView(id, principal);
            Long carId = booking.getCar().getId();
            commands.execute(carId, () -> {
                bookings.confirmBooking(id, principal);
                return null;
            });
            redirect.addFlashAttribute("successMessage", "VIETQR_V1".equals(booking.getWorkflowVersion())
                    ? "Đã chấp nhận yêu cầu. Vui lòng chuẩn bị biên bản trước thuê." : "Đã xác nhận đơn thuê.");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/owner/bookings/" + id;
    }

    @PostMapping("/owner/bookings/{id}/complete")
    public String complete(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal,
                           RedirectAttributes redirect) {
        try {
            Long carId = bookings.getBookingForView(id, principal).getCar().getId();
            commands.execute(carId, () -> {
                bookings.completeBooking(id, principal);
                return null;
            });
            redirect.addFlashAttribute("successMessage", "Đã hoàn tất đơn thuê.");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/owner/bookings/" + id;
    }

    @PostMapping("/owner/bookings/{id}/handover")
    public String handover(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean contractSigned,
                           @AuthenticationPrincipal CustomUserPrincipal principal, RedirectAttributes redirect) {
        try {
            Long carId = bookings.getBookingForView(id, principal).getCar().getId();
            commands.execute(carId, () -> {
                bookings.confirmHandover(id, contractSigned, principal);
                return null;
            });
            redirect.addFlashAttribute("successMessage", "Đã xác nhận hợp đồng và giao xe. Chuyến thuê đang diễn ra.");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/owner/bookings/" + id;
    }

    @PostMapping("/owner/bookings/{id}/return")
    public String confirmReturn(@PathVariable Long id, @Valid @ModelAttribute("returnForm") ReturnReceiptForm form,
                                BindingResult errors, @AuthenticationPrincipal CustomUserPrincipal principal,
                                Model model, RedirectAttributes redirect) {
        var booking = bookings.getBookingForView(id, principal);
        if (form.getDecision() == ReturnReceiptForm.Decision.RECEIVED_DISPUTED
                && (form.getNote() == null || form.getNote().isBlank())) {
            errors.rejectValue("note", "return.note.required", "Vui lòng ghi rõ nội dung bất đồng.");
        }
        if (errors.hasErrors()) return detail(id, principal, model);
        try {
            commands.execute(booking.getCar().getId(), () -> {
                bookings.confirmReturn(id, form, principal);
                return null;
            });
            redirect.addFlashAttribute("successMessage", form.getDecision() == ReturnReceiptForm.Decision.NOT_RECEIVED
                    ? "Đã ghi nhận chủ xe chưa nhận lại xe thực tế. Chưa phát sinh nghĩa vụ 70%."
                    : "Đã ghi nhận nhận lại xe thực tế và nghĩa vụ thanh toán 70% với hạn 1 giờ.");
        } catch (BusinessException error) {
            errors.reject("return.unavailable", error.getMessage());
            return detail(id, principal, model);
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
            Long carId = bookings.getBookingForView(id, principal).getCar().getId();
            commands.execute(carId, () -> {
                bookings.rejectBooking(id, principal, trimmedReason);
                return null;
            });
            redirect.addFlashAttribute("successMessage", "Đã từ chối đơn thuê.");
        } catch (BusinessException ex) {
            redirect.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/owner/bookings/" + id;
    }
}
