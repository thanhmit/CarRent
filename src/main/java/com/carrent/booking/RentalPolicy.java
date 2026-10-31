package com.carrent.booking;

import com.carrent.common.exception.BusinessException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Fixed rules for VIETQR_V1; legacy bookings keep their existing payment behavior. */
public final class RentalPolicy {
    private static final ZoneId RENTAL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private RentalPolicy() {}

    public static long advance(long total) {
        if (total < 4) {
            throw new BusinessException("Tổng tiền thuê phải đủ để mỗi phần thanh toán tối thiểu 1 VNĐ.");
        }
        return percentage(total, 30);
    }

    public static long balance(long total) {
        return total - advance(total);
    }

    public static CancellationQuote cancellation(long advance, Instant paidAt, Instant cancelledAt,
            LocalDate startDate, boolean ownerCancels) {
        if (advance <= 0 || paidAt == null || cancelledAt == null || startDate == null
                || cancelledAt.isBefore(paidAt)) {
            throw new BusinessException("Dữ liệu tính phí hủy không hợp lệ.");
        }
        if (ownerCancels || Duration.between(paidAt, cancelledAt).compareTo(Duration.ofHours(1)) <= 0) {
            return new CancellationQuote(0, advance);
        }
        long days = ChronoUnit.DAYS.between(cancelledAt.atZone(RENTAL_ZONE).toLocalDate(), startDate);
        long fee = percentage(advance, days > 3 ? 10 : 50);
        return new CancellationQuote(fee, advance - fee);
    }

    private static long percentage(long amount, int percent) {
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(percent))
                .divide(BigInteger.valueOf(100)).longValueExact();
    }
}
