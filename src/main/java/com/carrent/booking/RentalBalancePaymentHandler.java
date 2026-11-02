package com.carrent.booking;

import com.carrent.car.Car;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.payment.PaymentDomainHandler;
import com.carrent.payment.PaymentSubject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "carrent.rental.balance.enabled", havingValue = "true")
public class RentalBalancePaymentHandler implements PaymentDomainHandler {
    private final EntityManager em;
    private final ObjectProvider<BookingService> bookings;

    @Override public PaymentSubject subject() { return PaymentSubject.BOOKING_BALANCE; }

    @Override public void lockSubject(Long id) {
        var route = em.find(Booking.class, id);
        if (route == null) throw new NotFoundException("Đơn thuê không tồn tại.");
        current(Car.class, route.getCar().getId());
        var booking = current(Booking.class, id);
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()
                || booking.getVehicleReceivedAt() == null || booking.getVehicleReturnedAt() == null
                || booking.getAdvancePaidAt() == null
                || booking.getStatus() != BookingStatus.CONFIRMED && booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException("Đơn thuê chưa đủ điều kiện thanh toán phần còn lại.");
        }
    }

    private <T> T current(Class<T> type, Long id) {
        em.detach(em.getReference(type, id));
        var row = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) throw new NotFoundException("Đơn thuê không tồn tại.");
        return row;
    }

    @Override public void apply(Long receiptId) { bookings.getObject().applyBalance(receiptId); }
    // The payment core ends the attempt; expiry/cancel never releases the rental debt.
    @Override public void expire(Long intentId) { }
    @Override public void cancel(Long intentId) { }
}
