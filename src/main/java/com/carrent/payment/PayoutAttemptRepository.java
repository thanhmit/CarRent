package com.carrent.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutAttemptRepository extends JpaRepository<PayoutAttempt, Long> {
    Optional<PayoutAttempt> findByReferenceId(String referenceId);
    Optional<PayoutAttempt> findByIdempotencyKey(String idempotencyKey);
    List<PayoutAttempt> findByRefundIdOrderByAttemptNoAsc(Long refundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PayoutAttempt e where e.refund.id = :refundId order by e.attemptNo")
    List<PayoutAttempt> findByRefundIdForUpdate(@Param("refundId") Long refundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PayoutAttempt e where e.id = :id")
    Optional<PayoutAttempt> findByIdForUpdate(@Param("id") Long id);
}
