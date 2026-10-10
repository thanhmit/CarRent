package com.carrent.auth;

import com.carrent.auth.AuthService.RegistrationException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    @GetMapping("/register")
    public String register(Model model) {
        model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form, BindingResult errors, Model model) {
        if (!errors.hasErrors()) {
            try {
                authService.register(form);
                return "redirect:/login?registered";
            } catch (RegistrationException exception) {
                errors.rejectValue(exception.getField(), "registration.invalid", exception.getMessage());
            }
        }

        // Rebuild binding errors so rejected password values never reach the rendered model.
        BindingResult safeErrors = new BeanPropertyBindingResult(form, "form");
        boolean invalidRole = errors.hasFieldErrors("role");
        for (ObjectError error : errors.getAllErrors()) {
            if (error instanceof FieldError field) {
                if (field.getField().equals("role")) {
                    continue;
                }
                if (field.getField().equals("password") || field.getField().equals("confirmPassword")) {
                    safeErrors.addError(new FieldError("form", field.getField(), null, field.isBindingFailure(),
                            field.getCodes(), field.getArguments(), field.getDefaultMessage()));
                    continue;
                }
            }
            safeErrors.addError(error);
        }
        if (invalidRole) {
            safeErrors.rejectValue("role", "registration.invalidRole", "Vai trò không hợp lệ");
        }
        form.setPassword(null);
        form.setConfirmPassword(null);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", safeErrors);
        return "auth/register";
    }
}
