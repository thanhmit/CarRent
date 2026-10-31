package com.carrent.inspection;

import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.concurrency.CarCommandExecutor;
import com.carrent.security.CustomUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;

@Controller @RequiredArgsConstructor
@RequestMapping("/bookings/{bookingId}/inspections/{phase}")
public class InspectionController {
    private final InspectionService inspections;
    private final CarCommandExecutor commands;

    @InitBinder("form")
    void bindForm(WebDataBinder binder) {
        binder.setAllowedFields("conditionNote", "visibleDamage", "odometerKm", "energyPercent", "accessoriesNote");
    }

    @GetMapping({"", "/draft"})
    public String view(@PathVariable Long bookingId, @PathVariable String phase,
                       @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        var view = inspections.getView(bookingId, phase(phase), actor);
        model.addAttribute("form", InspectionForm.from(view));
        return render(view, model);
    }

    @PostMapping("/draft")
    public String save(@PathVariable Long bookingId, @PathVariable String phase,
                       @Valid @ModelAttribute("form") InspectionForm form, BindingResult errors,
                       @AuthenticationPrincipal CustomUserPrincipal actor, Model model, RedirectAttributes redirect) {
        var kind = phase(phase);
        var view = inspections.getView(bookingId, kind, actor);
        if (errors.hasErrors()) return render(view, model);
        try {
            commands.execute(view.carId(), () -> { inspections.saveDraft(bookingId, kind, form, actor); return null; });
        } catch (BusinessException error) {
            model.addAttribute("errorMessage", error.getMessage());
            return render(inspections.getView(bookingId, kind, actor), model);
        }
        redirect.addFlashAttribute("successMessage", "Đã lưu nháp biên bản.");
        return "redirect:" + base(bookingId, kind) + "/draft";
    }

    @PostMapping("/images")
    public String upload(@PathVariable Long bookingId, @PathVariable String phase,
                         @RequestParam(value="images", required=false) List<MultipartFile> files,
                         @AuthenticationPrincipal CustomUserPrincipal actor, RedirectAttributes redirect) {
        var kind = phase(phase);
        var view = inspections.getView(bookingId, kind, actor);
        try {
            commands.execute(view.carId(), () -> { inspections.addImages(bookingId, kind, files, actor); return null; });
            redirect.addFlashAttribute("successMessage", "Đã thêm ảnh vào nháp biên bản.");
        } catch (BusinessException error) { redirect.addFlashAttribute("errorMessage", error.getMessage()); }
        return "redirect:" + base(bookingId, kind) + "/draft";
    }

    @PostMapping("/images/{imageId}/delete")
    public String delete(@PathVariable Long bookingId, @PathVariable String phase, @PathVariable Long imageId,
                         @AuthenticationPrincipal CustomUserPrincipal actor, RedirectAttributes redirect) {
        var kind = phase(phase);
        var view = inspections.getView(bookingId, kind, actor);
        try {
            commands.execute(view.carId(), () -> { inspections.deleteDraftImage(bookingId, kind, imageId, actor); return null; });
            redirect.addFlashAttribute("successMessage", "Đã xóa ảnh khỏi nháp.");
        } catch (BusinessException error) { redirect.addFlashAttribute("errorMessage", error.getMessage()); }
        return "redirect:" + base(bookingId, kind) + "/draft";
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<Resource> image(@PathVariable Long bookingId, @PathVariable String phase, @PathVariable Long imageId,
                                          @AuthenticationPrincipal CustomUserPrincipal actor) {
        var image = inspections.readImage(bookingId, phase(phase), imageId, actor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=inspection-" + imageId
                        + (image.mediaType().equals("image/png") ? ".png" : ".jpg"))
                .contentType(MediaType.parseMediaType(image.mediaType())).contentLength(image.sizeBytes()).body(image.resource());
    }

    private String render(InspectionView view, Model model) {
        model.addAttribute("inspection", view);
        model.addAttribute("baseUrl", base(view.bookingId(), view.phase()));
        model.addAttribute("phaseTitle", view.phase() == InspectionPhase.BEFORE ? "Biên bản trước thuê" : "Biên bản sau thuê");
        model.addAttribute("statusTitle", switch (view.status()) {
            case DRAFT -> "Nháp — chỉ người lập được xem";
            case SUBMITTED -> "Đã gửi";
            case ACCEPTED -> "Đã đồng ý";
            case DISPUTED -> "Có bất đồng";
            case RESOLVED -> "Đã xử lý";
        });
        return "inspection/detail";
    }
    private String base(Long id, InspectionPhase phase) { return "/bookings/"+id+"/inspections/"+phase.name(); }
    private InspectionPhase phase(String value) {
        try { return InspectionPhase.valueOf(value); }
        catch (IllegalArgumentException error) { throw new NotFoundException("Biên bản không tồn tại."); }
    }
}
