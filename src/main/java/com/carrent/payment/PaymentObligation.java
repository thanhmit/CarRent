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
@Table(name = "payment_obligations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentObligation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "business_key", nullable = false, length = 100)
    private String businessKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false)
    private PaymentSubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_user_id", nullable = false)
    private User payer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collector_user_id", nullable = false)
    private User collector;

    @Column(name = "amount_due", nullable = false)
    private Long amountDue;

    @Builder.Default
    @Column(name = "amount_settled", nullable = false)
    private Long amountSettled = 0L;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "due_at", nullable = true)
    private Instant dueAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentObligationStatus status = PaymentObligationStatus.OPEN;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
