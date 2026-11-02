package com.carrent.payment;

import java.time.Instant;

public record PaymentIntentView(Long id, String code, long amount, Instant expiresAt, String status, String qrUrl, Rental rental) {
    public record Rental(Long bookingId, Long advanceAmount, Long balanceAmount, boolean cancelled, boolean canCancel,
                         boolean confirmationDelayed, boolean balance, Long obligationId, long remainingAmount,
                         Instant dueAt, boolean completed) {
        public Rental(Long bookingId, Long advanceAmount, Long balanceAmount, boolean cancelled, boolean canCancel, boolean confirmationDelayed) {
            this(bookingId, advanceAmount, balanceAmount, cancelled, canCancel, confirmationDelayed, false, null, 0, null, false);
        }
    }
    public String message() {
        if (rental != null && rental.balance()) {
            return switch (status) {
                case "ACTIVE" -> "Đang chờ thanh toán phần còn lại 70%.";
                case "SETTLED", "RECEIVED" -> "Đã nhận đủ phần còn lại 70%.";
                case "CANCELLED" -> "Đã hủy lần thanh toán. Khoản nợ và hạn trả vẫn giữ nguyên.";
                case "EXPIRED" -> "QR đã hết hạn. Khoản nợ và hạn trả vẫn giữ nguyên; hãy tạo lần thanh toán mới.";
                case "REFUND_PENDING" -> "Đang chờ hoàn khoản chuyển dư hoặc trùng. Xem riêng trạng thái khoản nợ bên dưới.";
                case "REFUNDED" -> "Đã hoàn khoản chuyển dư hoặc trùng. Xem riêng trạng thái khoản nợ bên dưới.";
                case "UNAVAILABLE" -> "Kênh thanh toán hiện chưa khả dụng. Vui lòng không chuyển tiền theo mã này.";
                default -> "Giao dịch cần được kiểm tra; chưa tự cộng khoản chuyển thiếu vào tiền đã thanh toán.";
            };
        }
        if (rental != null && rental.cancelled() && "CANCELLED".equals(status)) return "Đã hủy đặt xe. Không chuyển tiền theo mã QR của đơn này.";
        if (rental != null && rental.confirmationDelayed() && "REFUND_PENDING".equals(status)) {
            return "Tiền vào đúng hạn nhưng hệ thống xác nhận chậm; đơn đã hết giữ chỗ. Đang chờ hoàn đủ tiền, chưa xác nhận tiền đã về tài khoản nhận.";
        }
        return switch (status) {
            case "ACTIVE" -> "Đang chờ thanh toán";
            case "RECEIVED" -> rental == null ? "Đã nhận tiền" : "Đã nhận đủ trả trước và xác nhận đặt xe. Hợp đồng và giao xe sẽ được xác nhận riêng.";
            case "CANCELLED" -> "Đã hủy lần thanh toán. Thao tác này không đảo giao dịch ngân hàng.";
            case "EXPIRED" -> "Mã QR đã hết hạn. Vui lòng không chuyển tiền theo mã này.";
            case "REFUND_PENDING" -> "Đã nhận tiền, đang chờ hoàn. Chưa xác nhận tiền đã về tài khoản nhận.";
            case "REFUNDED" -> "Đã hoàn tiền";
            case "UNAVAILABLE" -> "Kênh thanh toán hiện chưa khả dụng. Vui lòng không chuyển tiền theo mã này.";
            default -> "Giao dịch cần được kiểm tra";
        };
    }
}
