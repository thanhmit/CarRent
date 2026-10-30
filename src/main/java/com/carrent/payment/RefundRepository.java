package com.carrent.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    Optional<Refund> findByBusinessKey(String businessKey);
    List<Refund> findByReceiptId(Long receiptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.receipt.id = :id order by r.id")
    List<Refund> findByReceiptIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Refund e where e.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);
}
