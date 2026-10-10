package com.carrent.auth;

import com.carrent.user.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterForm {
    @NotBlank(message = "Vui lòng nhập họ tên")
    @Size(max = 100, message = "Họ tên không được quá 100 ký tự")
    private String fullName;

    @NotBlank(message = "Vui lòng nhập email")
    @Size(max = 150, message = "Email không được quá 150 ký tự")
    private String email;

    @NotBlank(message = "Vui lòng nhập mật khẩu")
    @Size(min = 6, max = 72, message = "Mật khẩu phải từ 6 đến 72 ký tự")
    private String password;

    @NotBlank(message = "Vui lòng xác nhận mật khẩu")
    private String confirmPassword;

    @Size(max = 20, message = "Số điện thoại không được quá 20 ký tự")
    private String phone;

    @NotNull(message = "Vui lòng chọn vai trò")
    private Role role;
}
