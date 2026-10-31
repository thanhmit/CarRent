package com.carrent.booking;

import com.carrent.car.Car;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id", nullable = false)
    private Car car;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "price_per_day_snapshot", nullable = false)
    private Long pricePerDaySnapshot;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "pickup_address", nullable = false, length = 300)
    private String pickupAddress;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "customer_note", length = 500)
    private String customerNote;

    @Column(name = "owner_note", length = 500)
    private String ownerNote;

    @Builder.Default
    @Column(name = "workflow_version", nullable = false, length = 20)
    private String workflowVersion = "LEGACY";

    @Builder.Default
    @Column(name = "inspection_required", nullable = false)
    private boolean inspectionRequired = false;

    @Column(name = "vehicle_received_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant vehicleReceivedAt;

    @Column(name = "vehicle_returned_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant vehicleReturnedAt;

    @Column(name = "owner_accepted_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant ownerAcceptedAt;

    @Column(name = "before_accepted_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant beforeAcceptedAt;

    @Column(name = "hold_expires_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant holdExpiresAt;

    @Column(name = "advance_amount")
    private Long advanceAmount;

    @Column(name = "balance_amount")
    private Long balanceAmount;

    @Column(name = "advance_paid_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant advancePaidAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_confirmed_by")
    private User contractConfirmedBy;

    @Column(name = "contract_confirmed_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant contractConfirmedAt;

    @Column(name = "cancelled_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant cancelledAt;

    @Column(name = "cancellation_fee")
    private Long cancellationFee;

    @Column(name = "after_reminder_sent_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant afterReminderSentAt;

    @Column(name = "balance_overdue_notified_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant balanceOverdueNotifiedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
