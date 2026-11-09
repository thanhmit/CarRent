package com.carrent.user;

import com.carrent.security.CustomUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/profile")
public class ProfileController {
    private final UserService users;

    public ProfileController(UserService users) {
        this.users = users;
    }

    @InitBinder("form")
    void bindForm(WebDataBinder binder) {
        binder.setAllowedFields("fullName", "phone", "currentPassword", "newPassword", "confirmPassword");
    }

    @GetMapping
    public String view(@AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        model.addAttribute("profile", users.profile(actor));
        return "profile/view";
    }

    @GetMapping("/edit")
    public String edit(@AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        var profile = users.profile(actor);
        var form = new ProfileForm();
        form.setFullName(profile.fullName());
        form.setPhone(profile.phone());
        model.addAttribute("form", form);
        return "profile/edit";
    }

    @PostMapping("/edit")
    public String edit(@AuthenticationPrincipal CustomUserPrincipal actor,
                       @Valid @ModelAttribute("form") ProfileForm form, BindingResult errors,
                       RedirectAttributes redirect) {
        users.profile(actor);
        if (!errors.hasErrors()) {
            try {
                users.updateProfile(actor, form);
                // shortcut: menu name remains the login snapshot; refresh the principal if instant updates are needed.
                redirect.addFlashAttribute("successMessage", "Đã cập nhật hồ sơ. Đăng nhập lại để cập nhật tên trên menu tài khoản.");
                return "redirect:/profile";
            } catch (UserService.FormException exception) {
                errors.rejectValue(exception.getField(), "profile.invalid", exception.getMessage());
            }
        }
        return "profile/edit";
    }

    @GetMapping("/change-password")
    public String changePassword(@AuthenticationPrincipal CustomUserPrincipal actor, Model model) {
        users.profile(actor);
        model.addAttribute("form", new ChangePasswordForm());
        return "profile/change-password";
    }

    @PostMapping("/change-password")
    public String changePassword(@AuthenticationPrincipal CustomUserPrincipal actor,
                                 @Valid @ModelAttribute("form") ChangePasswordForm form, BindingResult errors,
                                 Model model, RedirectAttributes redirect) {
        users.profile(actor);
        if (!errors.hasErrors()) {
            try {
                users.changePassword(actor, form);
                redirect.addFlashAttribute("successMessage", "Đã đổi mật khẩu. Hãy dùng mật khẩu mới cho lần đăng nhập tiếp theo.");
                return "redirect:/profile";
            } catch (UserService.FormException exception) {
                errors.rejectValue(exception.getField(), "password.invalid", exception.getMessage());
            }
        }
        // Remove both bound values and rejected values before returning the error view.
        var safeForm = new ChangePasswordForm();
        var safeErrors = new BeanPropertyBindingResult(safeForm, "form");
        for (var error : errors.getAllErrors()) {
            if (error instanceof FieldError field) {
                safeErrors.addError(new FieldError("form", field.getField(), null, field.isBindingFailure(),
                        field.getCodes(), field.getArguments(), field.getDefaultMessage()));
            } else safeErrors.addError(error);
        }
        model.addAttribute("form", safeForm);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", safeErrors);
        return "profile/change-password";
    }
}
