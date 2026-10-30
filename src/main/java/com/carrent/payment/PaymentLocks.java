package com.carrent.payment;

import com.carrent.common.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;

@Component
final class PaymentLocks {
    private final EntityManager em;

    PaymentLocks(EntityManager em) { this.em = em; }

    <T> T current(Class<T> type, Long id) {
        // Evict before the locking load: refresh of an already-locked entity may use a stale snapshot.
        em.detach(em.getReference(type, id));
        T row = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) throw new NotFoundException("Không tìm thấy giao dịch.");
        return row;
    }
}
