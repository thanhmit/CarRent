package com.carrent.web;

import com.carrent.car.CarService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Trang chủ dùng cùng điều kiện công khai với trang tìm xe. */
@Controller
public class HomeController {
    private final CarService cars;

    public HomeController(CarService cars) {
        this.cars = cars;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("cars", cars.searchPublicCars(null, PageRequest.of(0, 8)).getContent());
        return "home/index";
    }

    @RequestMapping("/access-denied")
    public String accessDenied() {
        return "error/403";
    }
}
