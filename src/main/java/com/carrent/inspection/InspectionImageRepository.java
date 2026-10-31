package com.carrent.inspection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InspectionImageRepository extends JpaRepository<InspectionImage, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InspectionImage i where i.inspection.id=:inspectionId order by i.id")
    List<InspectionImage> findForUpdate(@Param("inspectionId") Long inspectionId);

    Optional<InspectionImage> findByInspectionIdAndId(Long inspectionId, Long id);

    List<InspectionImage> findByInspectionIdOrderByIdAsc(Long inspectionId);
}
