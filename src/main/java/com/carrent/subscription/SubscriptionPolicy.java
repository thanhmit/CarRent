package com.carrent.subscription;

import com.carrent.car.*;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owner mutex serializes admissions across different cars; expiry never changes listing intent. */
@Service
public class SubscriptionPolicy {
    private final EntityManager em;
    private final Clock clock;
    private final boolean enabled;

    public SubscriptionPolicy(EntityManager em, Clock clock,
                              @Value("${carrent.subscription.enabled:false}") boolean enabled) {
        this.em = em;
        this.clock = clock;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOwner(Long ownerId) {
        if (!enabled) return;
        current(User.class, ownerId);
        current(OwnerSubscription.class, ownerId);
    }

    /** Routing read only; recheck ownership after taking the Car lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long lockCarOwner(Long carId) {
        if (!enabled) return null;
        var rows = em.createQuery("select c.owner.id from Car c where c.id=:id", Long.class)
                .setParameter("id", carId).getResultList();
        if (rows.isEmpty()) throw new NotFoundException("Xe không tồn tại.");
        Long ownerId = rows.getFirst();
        lockOwner(ownerId);
        return ownerId;
    }

    public void requireSameOwner(Car car, Long ownerId) {
        if (enabled && !car.getOwner().getId().equals(ownerId)) {
            throw new BusinessException("Chủ xe đã thay đổi. Vui lòng thử lại.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActive(Long ownerId) {
        if (!enabled) return;
        var subscription = em.find(OwnerSubscription.class, ownerId);
        if (subscription == null || !subscription.getValidUntil().isAfter(clock.instant())) {
            throw new BusinessException("Chủ xe chưa có gói còn hiệu lực. Vui lòng mua hoặc gia hạn gói.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCanReserveSlot(Long ownerId) {
        if (!enabled) return;
        requireActive(ownerId);
        // Locking scalar read sees commits after waiting for Owner, even in an older RR snapshot.
        long reserved = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM cars WHERE owner_id=:id "
                + "AND listing_status='ACTIVE' AND approval_status IN ('PENDING','APPROVED') FOR UPDATE")
                .setParameter("id", ownerId).getSingleResult()).longValue();
        if (reserved >= em.find(OwnerSubscription.class, ownerId).getQuota()) {
            throw new BusinessException("Đã dùng hết số suất đăng xe của gói. Hãy ẩn một xe trước khi đăng thêm.");
        }
    }

    public Specification<Car> publicCars() {
        Specification<Car> visible = CarSpecifications.isPublic();
        if (!enabled) return visible;
        var now = clock.instant();
        return visible.and((root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var entitlement = sub.from(OwnerSubscription.class);
            sub.select(entitlement.get("ownerId")).where(
                    cb.equal(entitlement.get("ownerId"), root.get("owner").get("id")),
                    cb.greaterThan(entitlement.get("validUntil"), now));
            return cb.exists(sub);
        });
    }

    private <T> T current(Class<T> type, Long id) {
        var ref = em.getReference(type, id);
        var lock = em.getLockMode(ref);
        if (lock == LockModeType.PESSIMISTIC_WRITE || lock == LockModeType.PESSIMISTIC_FORCE_INCREMENT) return ref;
        if (type == User.class) {
            // Rental authorization retains this managed actor across the aggregate lock.
            em.refresh(ref, LockModeType.PESSIMISTIC_WRITE);
            return ref;
        }
        em.detach(ref);
        return em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    }
}
