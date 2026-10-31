package com.carrent.inspection;

import jakarta.validation.constraints.*;
import jakarta.validation.groups.Default;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class InspectionForm {
    public interface Submitted extends Default {}

    @Size(max=2000, message="Tình trạng không được quá 2000 ký tự.")
    @NotBlank(groups=Submitted.class, message="Vui lòng mô tả tình trạng xe.")
    private String conditionNote;
    @Size(max=1000, message="Hư hỏng không được quá 1000 ký tự.")
    @NotBlank(groups=Submitted.class, message="Vui lòng ghi hư hỏng hoặc Không phát hiện.")
    private String visibleDamage;
    @Min(value=0, message="Số km phải từ 0 đến 9.999.999.")
    @Max(value=9999999, message="Số km phải từ 0 đến 9.999.999.")
    @NotNull(groups=Submitted.class, message="Vui lòng nhập số km.")
    private Integer odometerKm;
    @Min(value=0, message="Nhiên liệu/pin phải từ 0 đến 100%.")
    @Max(value=100, message="Nhiên liệu/pin phải từ 0 đến 100%.")
    @NotNull(groups=Submitted.class, message="Vui lòng nhập nhiên liệu/pin.")
    private Integer energyPercent;
    @Size(max=500, message="Phụ kiện không được quá 500 ký tự.")
    @NotBlank(groups=Submitted.class, message="Vui lòng ghi phụ kiện hoặc Không bàn giao.")
    private String accessoriesNote;

    public static InspectionForm from(InspectionView view) {
        var form = new InspectionForm();
        form.setConditionNote(view.conditionNote()); form.setVisibleDamage(view.visibleDamage());
        form.setOdometerKm(view.odometerKm()); form.setEnergyPercent(view.energyPercent());
        form.setAccessoriesNote(view.accessoriesNote());
        return form;
    }
}
