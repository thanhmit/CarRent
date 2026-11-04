package com.carrent.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.List;

public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SubscriptionOrder o where o.owner.id=:ownerId and o.status=com.carrent.subscription.SubscriptionOrderStatus.PENDING order by o.id")
    List<SubscriptionOrder> pendingForUpdate(Long ownerId);
}
