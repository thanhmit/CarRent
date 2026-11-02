package com.carrent.inspection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface InspectionEventRepository extends Repository<InspectionEvent, Long> {
    @org.springframework.data.jpa.repository.Query(value = "SELECT action,actor_id,note FROM inspection_events "
            + "WHERE inspection_id=:inspectionId ORDER BY id DESC LIMIT 1 FOR UPDATE", nativeQuery = true)
    List<Object[]> findLatestForUpdate(@org.springframework.data.repository.query.Param("inspectionId") Long inspectionId);
    InspectionEvent save(InspectionEvent event);

    Optional<InspectionEvent> findById(Long id);

    List<InspectionEvent> findByInspectionIdOrderByCreatedAtAscIdAsc(Long inspectionId);
}
