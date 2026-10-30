package com.carrent.payment;

public enum PaymentObligationStatus {
    OPEN, SETTLED, CANCELLED, EXPIRED;

    public String message() {
        return switch (this) {
            case OPEN -> "Chờ thanh toán";
            case SETTLED -> "Đã ghi nhận đủ tiền";
            case CANCELLED -> "Đã hủy yêu cầu thanh toán";
            case EXPIRED -> "Yêu cầu thanh toán đã hết hạn";
        };
    }
}
