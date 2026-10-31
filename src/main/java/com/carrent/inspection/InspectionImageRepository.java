package com.carrent.inspection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionImageRepository extends JpaRepository<InspectionImage, Long> {
    Optional<InspectionImage> findByInspectionIdAndId(Long inspectionId, Long id);

    List<InspectionImage> findByInspectionIdOrderByIdAsc(Long inspectionId);
}
