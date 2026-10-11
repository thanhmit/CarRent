package com.carrent.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @Modifying(flushAutomatically = true)
    @Query("update Payment p set p.status = com.carrent.payment.PaymentStatus.REFUNDED "
            + "where p.booking.id = :bookingId and p.status = com.carrent.payment.PaymentStatus.PAID")
    int refundByBookingId(@Param("bookingId") Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.booking.id = :bookingId order by p.id")
    List<Payment> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    List<Payment> findByBookingIdOrderByCreatedAtDesc(Long bookingId);

    boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);
}
