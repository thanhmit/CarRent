package com.carrent.booking;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {
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

    Page<Booking> findByCustomerId(Long customerId, Pageable pageable);

    Page<Booking> findByCarOwnerId(Long ownerId, Pageable pageable);

    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);

    boolean existsByCarId(Long carId);

    @Query("select distinct b.car.id from Booking b where b.car.id in :carIds")
    List<Long> findCarIdsWithHistory(@Param("carIds") List<Long> carIds);
}
