package com.carrent.booking;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Query("select new com.carrent.booking.BookingDateRange(b.startDate, b.endDate) "
            + "from Booking b where b.car.id = :carId "
            + "and b.status in (com.carrent.booking.BookingStatus.PENDING, com.carrent.booking.BookingStatus.CONFIRMED) "
            + "and b.endDate > :today order by b.startDate, b.endDate")
    List<BookingDateRange> findBusyDateRanges(@Param("carId") Long carId, @Param("today") LocalDate today);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.car.id = :carId "
            + "and b.status in (com.carrent.booking.BookingStatus.PENDING, com.carrent.booking.BookingStatus.CONFIRMED) "
            + "and b.startDate < :end and b.endDate > :start order by b.id")
    List<Booking> findConflictsForUpdate(@Param("carId") Long carId, @Param("start") LocalDate start,
                                       @Param("end") LocalDate end);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.car.id = :carId order by b.id")
    List<Booking> findHistoryForUpdate(@Param("carId") Long carId);

    @Query(value = "SELECT id FROM bookings WHERE car_id=:carId AND id<>:bookingId "
            + "AND inspection_required=1 AND vehicle_received_at IS NOT NULL AND vehicle_returned_at IS NULL "
            + "ORDER BY id FOR UPDATE", nativeQuery = true)
    List<Long> findOutstandingCustodyForUpdate(@Param("carId") Long carId, @Param("bookingId") Long bookingId);

    @Query(value = "SELECT b.id,b.car_id FROM bookings b JOIN booking_inspections i ON i.booking_id=b.id "
            + "WHERE b.id>:afterId AND b.workflow_version='VIETQR_V1' AND b.inspection_required=1 "
            + "AND b.status='CONFIRMED' AND b.vehicle_received_at IS NOT NULL AND b.vehicle_returned_at IS NULL "
            + "AND b.after_reminder_sent_at IS NULL AND i.phase='AFTER' AND i.author_id=b.customer_id "
            + "AND i.status IN ('SUBMITTED','ACCEPTED','DISPUTED','RESOLVED') "
            + "AND i.submitted_at>=b.vehicle_received_at AND i.submitted_at<=:submittedBefore "
            + "ORDER BY b.id LIMIT :limit", nativeQuery = true)
    List<Object[]> findAfterReturnReminderCandidates(@Param("afterId") long afterId,
            @Param("submittedBefore") LocalDateTime submittedBefore, @Param("limit") int limit);

    @Query(value = "SELECT b.id,b.car_id FROM bookings b JOIN payment_obligations o ON o.subject_id=b.id "
            + "WHERE b.id>:afterId AND b.workflow_version='VIETQR_V1' AND b.inspection_required=1 "
            + "AND b.status='CONFIRMED' AND b.vehicle_received_at IS NOT NULL AND b.vehicle_returned_at IS NOT NULL "
            + "AND b.balance_overdue_notified_at IS NULL AND o.business_key=CONCAT('BOOKING:',b.id,':BALANCE') "
            + "AND o.subject_type='BOOKING' AND o.purpose='BALANCE' AND o.status='OPEN' "
            + "AND o.amount_settled<o.amount_due AND o.due_at<=:now ORDER BY b.id LIMIT :limit", nativeQuery = true)
    List<Object[]> findBalanceOverdueCandidates(@Param("afterId") long afterId,
            @Param("now") LocalDateTime now, @Param("limit") int limit);

    Page<Booking> findByCustomerId(Long customerId, Pageable pageable);

    Page<Booking> findByCarOwnerId(Long ownerId, Pageable pageable);

    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);

    boolean existsByCarId(Long carId);

    @Query("select distinct b.car.id from Booking b where b.car.id in :carIds")
    List<Long> findCarIdsWithHistory(@Param("carIds") List<Long> carIds);
}
