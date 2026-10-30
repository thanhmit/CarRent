package com.carrent.payment;

public enum PaymentPurpose {
    ADVANCE, BALANCE, SUBSCRIPTION;

    public String message() {
        return switch (this) {
            case ADVANCE -> "Trả trước thuê xe";
            case BALANCE -> "Thanh toán phần còn lại";
            case SUBSCRIPTION -> "Phí gói chủ xe";
        };
    }
}
