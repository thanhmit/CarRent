package com.carrent.inspection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingInspectionRepository extends JpaRepository<BookingInspection, Long> {
    Optional<BookingInspection> findByBookingIdAndPhase(Long bookingId, InspectionPhase phase);

    List<BookingInspection> findByBookingIdOrderByPhaseAsc(Long bookingId);
}
