package com.carrent.payment;

public enum RefundStatus {
    WAITING_RECIPIENT, READY, WAITING_FUNDS, PROCESSING, SUCCEEDED, REVIEW_REQUIRED;

    public String message() {
        return switch (this) {
            case WAITING_RECIPIENT -> "Chờ xác nhận tài khoản nhận";
            case READY -> "Chờ xử lý hoàn tiền";
            case WAITING_FUNDS -> "Chờ bên hoàn tiền bổ sung số dư";
            case PROCESSING -> "Đang đối soát kết quả chuyển tiền";
            case SUCCEEDED -> "Nhà cung cấp đã xác nhận hoàn tiền thành công";
            case REVIEW_REQUIRED -> "Khoản hoàn cần được kiểm tra";
        };
    }
}
