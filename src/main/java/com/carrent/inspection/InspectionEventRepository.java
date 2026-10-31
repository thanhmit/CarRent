package com.carrent.inspection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface InspectionEventRepository extends Repository<InspectionEvent, Long> {
    InspectionEvent save(InspectionEvent event);

    Optional<InspectionEvent> findById(Long id);

    List<InspectionEvent> findByInspectionIdOrderByCreatedAtAscIdAsc(Long inspectionId);
}
