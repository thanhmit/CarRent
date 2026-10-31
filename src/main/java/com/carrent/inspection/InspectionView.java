package com.carrent.inspection;

import java.util.List;

public record InspectionView(Long bookingId, Long carId, String carName, String pickupAddress,
        InspectionPhase phase, Long reportId, InspectionStatus status, String conditionNote,
        String visibleDamage, Integer odometerKm, Integer energyPercent, String accessoriesNote,
        List<Image> images, boolean editable) {
    public record Image(Long id) {}
}
