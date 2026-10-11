package com.carrent.car;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

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
    private String sort = "newest";
}
