package com.carrent.car;

import com.carrent.security.CustomUserPrincipal;
import com.carrent.booking.BookingService;
import com.carrent.common.exception.BusinessException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class CarController {
    private final CarService cars;
    private final CarImageRepository images;
    private final CarCategoryRepository categories;
    private final BookingService bookings;

    public CarController(CarService cars, CarImageRepository images, CarCategoryRepository categories, BookingService bookings) {
        this.cars = cars;
        this.images = images;
        this.categories = categories;
        this.bookings = bookings;
    }

    @GetMapping("/cars/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal CustomUserPrincipal principal,
                         @RequestParam(required = false) String start, @RequestParam(required = false) String end,
                         HttpServletResponse response, Model model) {
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("start", start);
        model.addAttribute("end", end);
        if (start != null || end != null) {
            try {
                model.addAttribute("availability", bookings.checkAvailability(id,
                        start == null || start.isBlank() ? null : LocalDate.parse(start),
                        end == null || end.isBlank() ? null : LocalDate.parse(end)));
            } catch (DateTimeParseException ex) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                model.addAttribute("availabilityError", "Ngày thuê không hợp lệ. Vui lòng chọn lại.");
            } catch (BusinessException ex) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                model.addAttribute("availabilityError", ex.getMessage());
            }
        }
        // Load view entities after a failed availability transaction may have detached them.
        Car car = cars.getPublicCar(id, principal);
        model.addAttribute("car", car);
        model.addAttribute("carImages", images.findByCarIdOrderByIdAsc(car.getId()));
        model.addAttribute("publicListing", car.getApprovalStatus() == ApprovalStatus.APPROVED
                && car.getListingStatus() == ListingStatus.ACTIVE);
        return "cars/detail";
    }

    @GetMapping("/cars")
    public String list(@Valid @ModelAttribute("criteria") CarSearchCriteria criteria, BindingResult result,
                       @RequestParam(defaultValue = "0") long page,
                       @RequestParam(defaultValue = "12") int size,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        if (result.hasErrors()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("errorMessage", "Điều kiện lọc không hợp lệ. Vui lòng kiểm tra lại.");
            return "error/400";
        }
        if (!List.of("newest", "priceAsc", "priceDesc").contains(criteria.getSort())) {
            criteria.setSort("newest");
        }
        int pageSize = Math.max(1, Math.min(size, 50));
        int pageNumber = (int) Math.max(0, Math.min(page, Integer.MAX_VALUE / pageSize));
        Page<Car> found = cars.searchPublicCars(criteria, PageRequest.of(pageNumber, pageSize));
        List<Long> ids = found.getContent().stream().map(Car::getId).toList();
        Map<Long, String> thumbnails = ids.isEmpty() ? Map.of() : images.findFirstImagesByCarIds(ids).stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> (String) row[1]));
        model.addAttribute("cars", found);
        model.addAttribute("page", found);
        model.addAttribute("criteria", criteria);
        model.addAttribute("categories", categories.findAllByOrderByIdAsc());
        model.addAttribute("thumbnails", thumbnails);
        model.addAttribute("paginationBaseUrl", UriComponentsBuilder.fromPath(request.getRequestURI())
                .query(request.getQueryString()).replaceQueryParam("page")
                .replaceQueryParam("size", pageSize).build(true).toUriString());
        return "cars/list";
    }
}
