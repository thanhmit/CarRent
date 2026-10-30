package com.carrent.payment;

import com.carrent.user.User;
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
@Table(name = "refunds")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "business_key", nullable = false, length = 150)
    private String businessKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    private PaymentReceipt receipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_channel_id", nullable = false)
    private PaymentChannel payoutChannel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id", nullable = false)
    private User recipient;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private RefundReason reason;

    @Column(name = "bank_bin", nullable = true, length = 6)
    private String bankBin;

    @Column(name = "account_number", nullable = true, length = 50)
    private String accountNumber;

    @Column(name = "account_name", nullable = true, length = 150)
    private String accountName;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "ready_at", nullable = true)
    private Instant readyAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "due_at", nullable = true)
    private Instant dueAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RefundStatus status = RefundStatus.WAITING_RECIPIENT;

    @Column(name = "lease_owner", nullable = true, length = 100)
    private String leaseOwner;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "lease_until", nullable = true)
    private Instant leaseUntil;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "next_retry_at", nullable = true)
    private Instant nextRetryAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "overdue_notified_at", nullable = true)
    private Instant overdueNotifiedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
