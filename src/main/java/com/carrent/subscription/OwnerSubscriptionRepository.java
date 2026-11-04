package com.carrent.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerSubscriptionRepository extends JpaRepository<OwnerSubscription, Long> {
}
