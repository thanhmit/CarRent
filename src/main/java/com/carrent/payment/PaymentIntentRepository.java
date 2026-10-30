package com.carrent.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {
    Optional<PaymentIntent> findByCode(String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PaymentIntent> findByObligationIdAndStatus(Long obligationId, PaymentIntentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PaymentIntent e where e.id = :id")
    Optional<PaymentIntent> findByIdForUpdate(@Param("id") Long id);
}
