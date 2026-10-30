package com.carrent.payment;

import com.carrent.booking.Booking;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.concurrency.CarCommandExecutor;
import jakarta.persistence.EntityManager;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentRoutes {
    public record Route(Long obligationId, PaymentSubject subject, Long subjectId, Long carId) { }
    private final EntityManager em;
    private final CarCommandExecutor commands;
    private final Map<PaymentSubject, PaymentDomainHandler> handlers = new EnumMap<>(PaymentSubject.class);

    public PaymentRoutes(EntityManager em, CarCommandExecutor commands, List<PaymentDomainHandler> handlers) {
        this.em = em;
        this.commands = commands;
        for (var handler : handlers) {
            if (this.handlers.put(handler.subject(), handler) != null) {
                throw new IllegalStateException("Duplicate payment domain handler");
            }
        }
    }

    public PaymentDomainHandler handler(PaymentSubject subject) {
        var handler = handlers.get(subject);
        if (handler == null) throw new BusinessException("Luồng thanh toán này chưa được kích hoạt.");
        return handler;
    }

    public boolean available(PaymentSubject subject) { return handlers.containsKey(subject); }

    boolean matchesParties(PaymentObligation obligation) {
        if (obligation.getSubjectType() != PaymentSubjectType.BOOKING) return true;
        // The handler has already locked/refreshed Car and Booking before any financial lock.
        var booking = em.find(Booking.class, obligation.getSubjectId());
        return booking != null && booking.getCustomer().getId().equals(obligation.getPayer().getId())
                && booking.getCar().getOwner().getId().equals(obligation.getCollector().getId());
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Route obligation(Long id) {
        var obligation = em.find(PaymentObligation.class, id);
        if (obligation == null) throw new NotFoundException("Không tìm thấy nghĩa vụ thanh toán.");
        return route(obligation);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Route intent(Long id) {
        var intent = em.find(PaymentIntent.class, id);
        if (intent == null) throw new NotFoundException("Không tìm thấy lần thanh toán.");
        return route(intent.getObligation());
    }

    private Route route(PaymentObligation obligation) {
        var subject = PaymentSubject.of(obligation);
        Long carId = null;
        if (subject != PaymentSubject.SUBSCRIPTION) {
            var booking = em.find(Booking.class, obligation.getSubjectId());
            if (booking == null) throw new NotFoundException("Không tìm thấy đơn thuê.");
            carId = booking.getCar().getId();
        }
        return new Route(obligation.getId(), subject, obligation.getSubjectId(), carId);
    }

    public <T> T execute(Route route, Supplier<T> action) {
        return route.carId() == null ? action.get() : commands.execute(route.carId(), action);
    }
}
