package com.carrent.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefundRecipientForm {
    @NotBlank(message = "Vui lòng chọn ngân hàng.")
    @Pattern(regexp = "[0-9]{6}", message = "Mã ngân hàng phải gồm 6 chữ số.")
    private String bankBin;
    @NotBlank(message = "Vui lòng nhập số tài khoản.")
    @Pattern(regexp = "[0-9]{1,50}", message = "Số tài khoản phải gồm 1–50 chữ số.")
    private String accountNumber;
    private boolean saveForLater;
    @Size(max = 150, message = "Tên tài khoản không hợp lệ.")
    private String confirmedAccountName;
}
