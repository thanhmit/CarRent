package com.carrent.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Trang chủ công khai. Phase 0 chỉ render layout tĩnh; P5.8 thay danh sách rỗng bằng query thật. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("cars", List.of());
        return "home/index";
    }

    @RequestMapping("/access-denied")
    public String accessDenied() {
        return "error/403";
    }
}
