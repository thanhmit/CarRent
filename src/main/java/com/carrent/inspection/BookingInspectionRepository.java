package com.carrent.inspection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingInspectionRepository extends JpaRepository<BookingInspection, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BookingInspection r where r.booking.id=:bookingId and r.phase=:phase")
    Optional<BookingInspection> findForUpdate(@Param("bookingId") Long bookingId, @Param("phase") InspectionPhase phase);

    Optional<BookingInspection> findByBookingIdAndPhase(Long bookingId, InspectionPhase phase);

    List<BookingInspection> findByBookingIdOrderByPhaseAsc(Long bookingId);
}
