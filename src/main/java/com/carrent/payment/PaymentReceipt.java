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
@Table(name = "payment_receipts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReceipt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private PaymentChannel channel;

    @Column(name = "provider_transaction_id", nullable = false, length = 100)
    private String providerTransactionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inbox_id", nullable = false)
    private WebhookInbox inbox;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intent_id", nullable = true)
    private PaymentIntent intent;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Builder.Default
    @Column(name = "allocated_amount", nullable = false)
    private Long allocatedAmount = 0L;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "credited_at", nullable = false)
    private Instant creditedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentReceiptStatus status = PaymentReceiptStatus.REVIEW_REQUIRED;
}
