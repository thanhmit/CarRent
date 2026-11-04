package com.carrent.subscription;

import com.carrent.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "owner_subscriptions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerSubscription {
    @Id
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false, insertable = false, updatable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false)
    private SubscriptionPlan planCode;

    @Column(name = "quota", nullable = false)
    private Integer quota;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_applied_order_id", nullable = false)
    private SubscriptionOrder lastAppliedOrder;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
