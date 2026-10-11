package com.carrent.booking;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
public class BookingCreateForm {
    @NotNull(message = "Vui lòng chọn xe.")
    private Long carId;

    @NotNull(message = "Vui lòng chọn ngày nhận xe.")
    @FutureOrPresent(message = "Ngày nhận xe không được ở quá khứ.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @NotNull(message = "Vui lòng chọn ngày trả xe.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    @Size(max = 500, message = "Ghi chú không được quá 500 ký tự.")
    private String customerNote;
}
