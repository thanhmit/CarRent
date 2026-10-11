package com.carrent.car;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Year;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarForm {
    @NotBlank(message = "Vui lòng nhập hãng xe.")
    @Size(max = 50, message = "Hãng xe không được quá 50 ký tự.")
    private String brand;

    @NotBlank(message = "Vui lòng nhập mẫu xe.")
    @Size(max = 50, message = "Mẫu xe không được quá 50 ký tự.")
    private String model;

    @NotNull(message = "Vui lòng nhập năm sản xuất.")
    @Min(value = 1990, message = "Năm sản xuất phải từ 1990 trở đi.")
    private Integer manufactureYear;

    @Size(max = 20, message = "Biển số xe không được quá 20 ký tự.")
    private String licensePlate;

    @Size(max = 30, message = "Màu xe không được quá 30 ký tự.")
    private String color;

    @NotNull(message = "Vui lòng nhập số chỗ ngồi.")
    @Min(value = 2, message = "Xe phải có ít nhất 2 chỗ ngồi.")
    @Max(value = 15, message = "Xe không được quá 15 chỗ ngồi.")
    private Integer seatCount;

    @NotNull(message = "Vui lòng chọn hộp số.")
    private Transmission transmission;

    @NotNull(message = "Vui lòng chọn loại nhiên liệu.")
    private FuelType fuelType;

    @Size(max = 2000, message = "Mô tả không được quá 2000 ký tự.")
    private String description;

    @NotNull(message = "Vui lòng nhập giá thuê mỗi ngày.")
    @Min(value = 1, message = "Giá thuê mỗi ngày phải lớn hơn 0.")
    private Long pricePerDay;

    @NotNull(message = "Vui lòng chọn loại xe.")
    private Long categoryId;

    @NotBlank(message = "Vui lòng nhập tỉnh hoặc thành phố.")
    @Size(max = 100, message = "Tỉnh hoặc thành phố không được quá 100 ký tự.")
    private String province;

    @Size(max = 100, message = "Quận hoặc huyện không được quá 100 ký tự.")
    private String district;

    @Size(max = 255, message = "Địa chỉ chi tiết không được quá 255 ký tự.")
    private String addressDetail;

    @AssertTrue(message = "Năm sản xuất không được sau năm tới.")
    public boolean isManufactureYearWithinLimit() {
        return manufactureYear == null || manufactureYear <= Year.now().getValue() + 1;
    }

    public static CarForm from(Car car) {
        CarForm form = new CarForm();
        form.setBrand(car.getBrand());
        form.setModel(car.getModel());
        form.setManufactureYear(car.getManufactureYear());
        form.setLicensePlate(car.getLicensePlate());
        form.setColor(car.getColor());
        form.setSeatCount(car.getSeatCount());
        form.setTransmission(car.getTransmission());
        form.setFuelType(car.getFuelType());
        form.setDescription(car.getDescription());
        form.setPricePerDay(car.getPricePerDay());
        form.setCategoryId(car.getCategory().getId());
        form.setProvince(car.getProvince());
        form.setDistrict(car.getDistrict());
        form.setAddressDetail(car.getAddressDetail());
        return form;
    }
}
