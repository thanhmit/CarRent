package com.carrent.booking;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class ReturnReceiptForm {
    public enum Decision { RECEIVED_ACCEPTED, RECEIVED_DISPUTED, NOT_RECEIVED }
    @NotNull(message = "Vui lòng chọn tình trạng nhận lại xe.")
    private Decision decision;
    @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự.")
    private String note;
}
