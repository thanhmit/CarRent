package com.carrent.payment;

public enum PaymentIntentStatus {
    ACTIVE, RECEIVED, EXPIRED, CANCELLED, REVIEW_REQUIRED;

    public String message() {
        return switch (this) {
            case ACTIVE -> "Đang chờ thanh toán";
            case RECEIVED -> "Đã nhận tiền";
            case EXPIRED -> "Mã QR đã hết hạn";
            case CANCELLED -> "Đã hủy lần thanh toán";
            case REVIEW_REQUIRED -> "Giao dịch cần được kiểm tra";
        };
    }
}
