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
import jakarta.persistence.Lob;
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
@Table(name = "webhook_inbox")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookInbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    private PaymentChannel channel;

    @Column(name = "provider_transaction_id", nullable = false, length = 100)
    private String providerTransactionId;

    @Lob
    @Column(name = "raw_payload", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] rawPayload;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WebhookInboxStatus status = WebhookInboxStatus.RECEIVED;

    @Column(name = "lease_owner", nullable = true, length = 100)
    private String leaseOwner;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "lease_until", nullable = true)
    private Instant leaseUntil;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "next_retry_at", nullable = true)
    private Instant nextRetryAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "processed_at", nullable = true)
    private Instant processedAt;

    @Column(name = "last_error", nullable = true, length = 500)
    private String lastError;
}
