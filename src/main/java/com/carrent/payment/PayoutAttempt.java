package com.carrent.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "payout_attempts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id", nullable = false)
    private Refund refund;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_channel_id", nullable = false)
    private PaymentChannel payoutChannel;

    @Column(name = "reference_id", nullable = false, length = 100)
    private String referenceId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "provider_payout_id", nullable = true, length = 100)
    private String providerPayoutId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "bank_bin", nullable = false, length = 6)
    private String bankBin;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Column(name = "account_name", nullable = true, length = 150)
    private String accountName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PayoutAttemptStatus status = PayoutAttemptStatus.PREPARED;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "submitted_at", nullable = true)
    private Instant submittedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "resolved_at", nullable = true)
    private Instant resolvedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "next_reconcile_at", nullable = true)
    private Instant nextReconcileAt;

    @Column(name = "last_error", nullable = true, length = 500)
    private String lastError;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
