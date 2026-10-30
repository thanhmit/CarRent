package com.carrent.payment;

import java.time.Instant;

public record PaymentIntentView(Long id, String code, long amount, Instant expiresAt, String status, String qrUrl) {
    public String message() {
        return switch (status) {
            case "ACTIVE" -> "Đang chờ thanh toán";
            case "RECEIVED" -> "Đã nhận tiền";
            case "CANCELLED" -> "Đã hủy lần thanh toán. Thao tác này không đảo giao dịch ngân hàng.";
            case "EXPIRED" -> "Mã QR đã hết hạn. Vui lòng không chuyển tiền theo mã này.";
            case "REFUND_PENDING" -> "Đã nhận tiền, đang chờ hoàn. Chưa xác nhận tiền đã về tài khoản nhận.";
            case "REFUNDED" -> "Đã hoàn tiền";
            case "UNAVAILABLE" -> "Kênh thanh toán hiện chưa khả dụng. Vui lòng không chuyển tiền theo mã này.";
            default -> "Giao dịch cần được kiểm tra";
        };
    }
}
