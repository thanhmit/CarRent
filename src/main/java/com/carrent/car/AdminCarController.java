package com.carrent.car;

import com.carrent.common.exception.BusinessException;
import com.carrent.concurrency.CarCommandExecutor;
import com.carrent.security.CustomUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/cars")
public class AdminCarController {
    private final CarService cars;
    private final CarImageRepository images;
    private final CarCommandExecutor commands;

    public AdminCarController(CarService cars, CarImageRepository images, CarCommandExecutor commands) {
        this.cars = cars;
        this.images = images;
        this.commands = commands;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserPrincipal admin,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) ApprovalStatus approval,
                       @RequestParam(required = false) ListingStatus listing,
                       @RequestParam(defaultValue = "0") long page,
                       @RequestParam(defaultValue = "10") int size,
                       HttpServletRequest request, Model model) {
        int pageSize = Math.max(1, Math.min(size, 50));
        int pageNumber = (int) Math.max(0, Math.min(page, Integer.MAX_VALUE / pageSize));
        Page<Car> result = cars.searchCarsForAdmin(q, approval, listing,
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id")), admin);
        List<Long> carIds = result.getContent().stream().map(Car::getId).toList();
        Map<Long, String> thumbnails = carIds.isEmpty() ? Map.of() : images.findFirstImagesByCarIds(carIds).stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> (String) row[1]));

        model.addAttribute("cars", result.getContent());
        model.addAttribute("page", result);
        model.addAttribute("thumbnails", thumbnails);
        model.addAttribute("q", q);
        model.addAttribute("approval", approval);
        model.addAttribute("listing", listing);
        model.addAttribute("pendingCount", cars.countPendingCars());
        model.addAttribute("adminName", admin.getFullName());
        model.addAttribute("paginationBaseUrl", UriComponentsBuilder.fromPath(request.getRequestURI())
                .query(request.getQueryString()).replaceQueryParam("page")
                .replaceQueryParam("size", pageSize).build(true).toUriString());
        return "admin/cars";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal admin, Model model) {
        Car car = cars.getCarForAdmin(id, admin);
        model.addAttribute("car", car);
        model.addAttribute("carImages", images.findByCarIdOrderByIdAsc(car.getId()));
        model.addAttribute("adminName", admin.getFullName());
        return "admin/car-detail";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal admin,
                          RedirectAttributes redirect) {
        try {
            cars.getCarForAdmin(id, admin);
            commands.execute(id, () -> {
                cars.approveCar(id, admin);
                return null;
            });
        } catch (BusinessException error) {
            redirect.addFlashAttribute("errorMessage", error.getMessage());
            return "redirect:/admin/cars/" + id;
        }
        redirect.addFlashAttribute("successMessage", "Đã duyệt xe.");
        return "redirect:/admin/cars/" + id;
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String reason,
                         @AuthenticationPrincipal CustomUserPrincipal admin,
                         Model model, RedirectAttributes redirect) {
        try {
            cars.getCarForAdmin(id, admin);
            commands.execute(id, () -> {
                cars.rejectCar(id, reason, admin);
                return null;
            });
        } catch (BusinessException error) {
            Car car = cars.getCarForAdmin(id, admin);
            model.addAttribute("car", car);
            model.addAttribute("carImages", images.findByCarIdOrderByIdAsc(car.getId()));
            model.addAttribute("adminName", admin.getFullName());
            model.addAttribute("reason", reason);
            model.addAttribute("errorMessage", error.getMessage());
            return "admin/car-detail";
        }
        redirect.addFlashAttribute("successMessage", "Đã từ chối xe.");
        return "redirect:/admin/cars/" + id;
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal admin,
                             RedirectAttributes redirect) {
        cars.getCarForAdmin(id, admin);
        commands.execute(id, () -> {
            cars.deactivateCarForAdmin(id, admin);
            return null;
        });
        redirect.addFlashAttribute("successMessage", "Đã ẩn xe khỏi danh sách công khai.");
        return "redirect:/admin/cars/" + id;
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal admin,
                           RedirectAttributes redirect) {
        try {
            cars.getCarForAdmin(id, admin);
            commands.execute(id, () -> {
                cars.activateCarForAdmin(id, admin);
                return null;
            });
            redirect.addFlashAttribute("successMessage", "Đã đăng lại xe.");
        } catch (BusinessException error) {
            redirect.addFlashAttribute("errorMessage", error.getMessage());
        }
        return "redirect:/admin/cars/" + id;
    }
}
