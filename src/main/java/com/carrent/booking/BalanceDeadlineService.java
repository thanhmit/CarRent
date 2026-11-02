package com.carrent.booking;

import com.carrent.car.Car;
import com.carrent.notification.NotificationService;
import com.carrent.payment.PaymentObligation;
import com.carrent.payment.PaymentObligationRepository;
import com.carrent.payment.PaymentObligationStatus;
import com.carrent.payment.PaymentPurpose;
import com.carrent.payment.PaymentSubjectType;
import com.carrent.user.Role;
import com.carrent.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BalanceDeadlineService {
    private final EntityManager em;
    private final PaymentObligationRepository obligations;
    private final UserRepository users;
    private final NotificationService notifications;
    private final Clock clock;

    public BalanceDeadlineService(EntityManager em, PaymentObligationRepository obligations, UserRepository users,
            NotificationService notifications, Clock clock) {
        this.em = em;
        this.obligations = obligations;
        this.users = users;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public void remind(Long bookingId) {
        var route = em.find(Booking.class, bookingId);
        if (route == null) return;
        Long carId = route.getCar().getId();
        current(Car.class, carId);
        var booking = current(Booking.class, bookingId);
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()
                || booking.getStatus() != BookingStatus.CONFIRMED || booking.getVehicleReceivedAt() == null
                || booking.getVehicleReturnedAt() == null || booking.getBalanceOverdueNotifiedAt() != null) return;
        var candidate = obligations.findByBusinessKeyForUpdate("BOOKING:" + bookingId + ":BALANCE").orElse(null);
        if (candidate == null) return;
        var balance = current(PaymentObligation.class, candidate.getId());
        var now = clock.instant();
        if (balance.getPurpose() != PaymentPurpose.BALANCE || balance.getSubjectType() != PaymentSubjectType.BOOKING
                || !bookingId.equals(balance.getSubjectId()) || !balance.getPayer().getId().equals(booking.getCustomer().getId())
                || !balance.getCollector().getId().equals(booking.getCar().getOwner().getId())
                || !balance.getAmountDue().equals(booking.getBalanceAmount())
                || balance.getStatus() != PaymentObligationStatus.OPEN || balance.getAmountSettled() >= balance.getAmountDue()
                || balance.getDueAt() == null || now.isBefore(balance.getDueAt())) return;
        booking.setBalanceOverdueNotifiedAt(now);
        var recipients = new LinkedHashSet<Long>();
        recipients.add(booking.getCustomer().getId());
        recipients.add(booking.getCar().getOwner().getId());
        users.findByRole(Role.ADMIN).forEach(admin -> recipients.add(admin.getId()));
        for (var recipient : recipients) {
            notifications.notify(recipient, "BOOKING_BALANCE_OVERDUE", "Thanh toán còn lại đã quá hạn",
                    "Đơn #CR-" + bookingId + " đã quá hạn thanh toán phần còn lại. Vui lòng kiểm tra khoản nợ và thanh toán.",
                    "BOOKING", bookingId);
        }
    }

    private <T> T current(Class<T> type, Long id) {
        // Detach before locking so an earlier repeatable-read snapshot cannot hide a committed payment.
        em.detach(em.getReference(type, id));
        return em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    }
}
