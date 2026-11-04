package com.carrent.booking;

import com.carrent.car.Car;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.payment.PaymentDomainHandler;
import com.carrent.payment.PaymentSubject;
import com.carrent.payment.PaymentIntent;
import jakarta.persistence.EntityManager;
import com.carrent.subscription.SubscriptionPolicy;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "carrent.rental.advance.enabled", havingValue = "true")
public class RentalPaymentHandler implements PaymentDomainHandler {
    private final EntityManager em;
    private final SubscriptionPolicy subscriptions;
    private final ObjectProvider<BookingService> bookings;

    @Override public PaymentSubject subject() { return PaymentSubject.BOOKING_ADVANCE; }

    @Override public void lockSubject(Long id) {
        var route = em.find(Booking.class, id);
        if (route == null) throw new NotFoundException("Đơn thuê không tồn tại.");
        Long ownerId = subscriptions.lockCarOwner(route.getCar().getId());
        var car = current(Car.class, route.getCar().getId());
        subscriptions.requireSameOwner(car, ownerId);
        var booking = current(Booking.class, id);
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()) {
            throw new BusinessException("Đơn thuê không thuộc luồng trả trước mới.");
        }
    }

    private <T> T current(Class<T> type, Long id) {
        em.detach(em.getReference(type, id));
        var row = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) throw new NotFoundException("Đơn thuê không tồn tại.");
        return row;
    }

    @Override public void apply(Long receiptId) {
        bookings.getObject().applyAdvance(receiptId);
    }
    @Override public void expire(Long intentId) {
        var intent = em.find(PaymentIntent.class, intentId);
        if (intent == null) throw new NotFoundException("Không tìm thấy lần trả trước.");
        bookings.getObject().expireHold(intent.getObligation().getSubjectId());
    }
    @Override public void cancel(Long intentId) {
        bookings.getObject().cancelAdvanceLocked(intentId);
    }
}
