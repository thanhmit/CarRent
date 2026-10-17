package com.carrent.car;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
public class CarSearchCriteria {
    @Size(max = 200, message = "Từ khóa không được quá 200 ký tự.")
    private String q;

    @Positive(message = "Loại xe phải lớn hơn 0.")
    private Long categoryId;

    @Size(max = 100, message = "Tỉnh hoặc thành phố không được quá 100 ký tự.")
    private String province;

    @PositiveOrZero(message = "Giá tối thiểu không được âm.")
    private Long minPrice;

    @PositiveOrZero(message = "Giá tối đa không được âm.")
    private Long maxPrice;

    @Min(value = 2, message = "Xe phải có ít nhất 2 chỗ ngồi.")
    @Max(value = 15, message = "Xe không được quá 15 chỗ ngồi.")
    private Integer seatCount;

    private Transmission transmission;
    private FuelType fuelType;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @FutureOrPresent(message = "Ngày nhận xe không được ở quá khứ.")
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private String sort = "newest";

    @AssertTrue(message = "Vui lòng chọn đủ ngày nhận và ngày trả xe; ngày trả phải sau ngày nhận và không sau 31/12/9999.")
    public boolean isRentalPeriodValid() {
        if (startDate == null && endDate == null) {
            return true;
        }
        return startDate != null && endDate != null && startDate.isBefore(endDate)
                && !endDate.isAfter(LocalDate.of(9999, 12, 31));
    }
}
