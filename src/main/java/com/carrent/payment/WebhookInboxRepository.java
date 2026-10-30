package com.carrent.payment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookInboxRepository extends JpaRepository<WebhookInbox, Long> {
    Optional<WebhookInbox> findByChannelIdAndProviderTransactionId(Long channelId, String providerTransactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookInbox e where e.id = :id")
    Optional<WebhookInbox> findByIdForUpdate(@Param("id") Long id);
}
