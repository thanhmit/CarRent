package com.carrent.car;

import com.carrent.security.CustomUserPrincipal;
import com.carrent.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/owner/cars")
public class OwnerCarController {
    private final CarService cars;
    private final CarImageRepository images;
    private final CarCategoryRepository categories;

    public OwnerCarController(CarService cars, CarImageRepository images, CarCategoryRepository categories) {
        this.cars = cars;
        this.images = images;
        this.categories = categories;
    }

    @GetMapping("/new")
    public String newCar(@AuthenticationPrincipal CustomUserPrincipal owner, Model model) {
        return carForm(owner, model, new CarForm(), null, null);
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("form") CarForm form, BindingResult result,
                         @RequestParam(required = false) List<MultipartFile> images,
                         @AuthenticationPrincipal CustomUserPrincipal owner,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return carForm(owner, model, form, null, null);
        }
        try {
            cars.createCar(form, images, owner);
        } catch (BusinessException error) {
            model.addAttribute("errorMessage", error.getMessage());
            return carForm(owner, model, form, null, null);
        }
        redirect.addFlashAttribute("successMessage", "Đăng xe thành công. Xe đang chờ Admin duyệt.");
        return "redirect:/owner/cars";
    }

    @GetMapping("/{id}/edit")
    public String editCar(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal owner, Model model) {
        Car car = cars.getOwnedCar(id, owner);
        return carForm(owner, model, CarForm.from(car), car, images.findByCarIdOrderByIdAsc(car.getId()));
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") CarForm form, BindingResult result,
                         @RequestParam(required = false) List<MultipartFile> images,
                         @AuthenticationPrincipal CustomUserPrincipal owner,
                         Model model, RedirectAttributes redirect) {
        // Ownership is checked before validation so a foreign car never renders its form.
        Car car = cars.getOwnedCar(id, owner);
        if (result.hasErrors()) {
            return carForm(owner, model, form, car, this.images.findByCarIdOrderByIdAsc(car.getId()));
        }
        try {
            cars.updateCar(id, form, images, owner);
        } catch (BusinessException error) {
            model.addAttribute("errorMessage", error.getMessage());
            return carForm(owner, model, form, car, this.images.findByCarIdOrderByIdAsc(car.getId()));
        }
        redirect.addFlashAttribute("successMessage", "Cập nhật xe thành công.");
        return "redirect:/owner/cars";
    }

    @PostMapping("/{id}/images/{imageId}/delete")
    public String deleteImage(@PathVariable Long id, @PathVariable Long imageId,
                              @AuthenticationPrincipal CustomUserPrincipal owner, RedirectAttributes redirect) {
        cars.deleteImage(id, imageId, owner);
        redirect.addFlashAttribute("successMessage", "Đã xóa ảnh xe.");
        return "redirect:/owner/cars/" + id + "/edit";
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal owner,
                             RedirectAttributes redirect) {
        try {
            cars.deactivateCar(id, owner);
        } catch (BusinessException error) {
            redirect.addFlashAttribute("errorMessage", error.getMessage());
            return "redirect:/owner/cars";
        }
        redirect.addFlashAttribute("successMessage", "Đã ẩn xe khỏi danh sách công khai.");
        return "redirect:/owner/cars";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal owner,
                           RedirectAttributes redirect) {
        try {
            cars.activateCar(id, owner);
        } catch (BusinessException error) {
            redirect.addFlashAttribute("errorMessage", error.getMessage());
            return "redirect:/owner/cars";
        }
        redirect.addFlashAttribute("successMessage", "Đã đăng lại xe.");
        return "redirect:/owner/cars";
    }

    private String carForm(CustomUserPrincipal owner, Model model, CarForm form, Car car, List<CarImage> carImages) {
        model.addAttribute("form", form);
        model.addAttribute("categories", categories.findAllByOrderByIdAsc());
        model.addAttribute("ownerName", owner.getFullName());
        model.addAttribute("maxYear", Year.now().getValue() + 1);
        model.addAttribute("car", car);
        model.addAttribute("carImages", carImages);
        return "owner/car-form";
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserPrincipal owner,
                       @RequestParam(defaultValue = "0") long page,
                       @RequestParam(defaultValue = "10") int size,
                       HttpServletRequest request, Model model) {
        int pageSize = Math.max(1, Math.min(size, 50));
        int pageNumber = (int) Math.max(0, Math.min(page, Integer.MAX_VALUE / pageSize));
        Page<Car> result = cars.findCarsByOwner(owner.getId(),
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id")));
        List<Long> carIds = result.getContent().stream().map(Car::getId).toList();
        Map<Long, String> thumbnails = carIds.isEmpty() ? Map.of() : images.findFirstImagesByCarIds(carIds).stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> (String) row[1]));

        model.addAttribute("cars", result.getContent());
        model.addAttribute("page", result);
        model.addAttribute("thumbnails", thumbnails);
        model.addAttribute("publicCount", cars.countPublicCars(owner.getId()));
        model.addAttribute("pendingCount", cars.countPendingCars(owner.getId()));
        model.addAttribute("ownerName", owner.getFullName());
        model.addAttribute("paginationBaseUrl", UriComponentsBuilder.fromPath(request.getRequestURI())
                .query(request.getQueryString()).replaceQueryParam("page")
                .replaceQueryParam("size", pageSize).build(true).toUriString());
        return "owner/cars";
    }
}
