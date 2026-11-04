package com.carrent.admin;

import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.UserStatus;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/users")
public class AdminUserController {
    private final AdminUserService users;
    public AdminUserController(AdminUserService users) { this.users=users; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @ModelAttribute("statusLabels") Map<UserStatus,String> statuses() {
        return Map.of(UserStatus.ACTIVE,"Hoạt động",UserStatus.INACTIVE,"Ngừng hoạt động",UserStatus.BANNED,"Bị chặn");
    }
    @ModelAttribute("roleLabels") Map<Role,String> roles() {
        return Map.of(Role.ADMIN,"Quản trị viên",Role.OWNER,"Chủ xe",Role.CUSTOMER,"Khách thuê");
    }

    @GetMapping
    public String list(@RequestParam(defaultValue="") String q, @RequestParam(required=false) Role role,
            @RequestParam(required=false) UserStatus status, @RequestParam(defaultValue="0") int page,
            @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        model.addAttribute("users",users.list(q,role,status,page,actor));
        model.addAttribute("q",q);model.addAttribute("role",role);model.addAttribute("status",status);
        return "admin/users";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, @AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        model.addAttribute("detail",users.detail(id,actor));
        model.addAttribute("self",actor.getId().equals(id));
        return "admin/user-detail";
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable long id, @RequestParam(required=false) UserStatus status,
            @RequestParam Map<String,String> parameters, @AuthenticationPrincipal CustomUserPrincipal actor,
            RedirectAttributes redirect) {
        if (!Set.of("status","_csrf").containsAll(parameters.keySet()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        users.changeStatus(id,status,actor);
        redirect.addFlashAttribute("successMessage","Đã cập nhật trạng thái tài khoản.");
        return "redirect:/admin/users/"+id;
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(Model model) {
        model.addAttribute("errorMessage","Thông tin tài khoản hoặc điều kiện lọc không hợp lệ.");
        return "error/400";
    }
}
