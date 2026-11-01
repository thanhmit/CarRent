package com.carrent.inspection;

import java.util.List;
import java.time.Instant;

public record InspectionView(Long bookingId, Long carId, String carName, String pickupAddress,
        InspectionPhase phase, Long reportId, InspectionStatus status, String conditionNote,
        String visibleDamage, Integer odometerKm, Integer energyPercent, String accessoriesNote,
        List<Image> images, boolean editable, Instant submittedAt, List<Event> events,
        boolean canSubmit, boolean canAddendum, boolean canDispute) {
    public record Image(Long id) {}
    public record Event(Long id, Long actorId, String actorName, InspectionEventAction action, String note, Instant createdAt) {}
}
