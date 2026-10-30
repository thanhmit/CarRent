package com.carrent.payment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, Long> {
    List<PaymentReceipt> findByIntentId(Long intentId);
    Optional<PaymentReceipt> findByChannelIdAndProviderTransactionId(Long channelId, String providerTransactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PaymentReceipt e where e.id = :id")
    Optional<PaymentReceipt> findByIdForUpdate(@Param("id") Long id);
}
