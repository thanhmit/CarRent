package com.carrent.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentChannelRepository extends JpaRepository<PaymentChannel, Long> {
    List<PaymentChannel> findByUserIdAndPurposeAndEnabledTrue(Long userId, PaymentChannelPurpose purpose);
    List<PaymentChannel> findByUserIdAndPurpose(Long userId, PaymentChannelPurpose purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PaymentChannel e where e.id = :id")
    Optional<PaymentChannel> findByIdForUpdate(@Param("id") Long id);
}
