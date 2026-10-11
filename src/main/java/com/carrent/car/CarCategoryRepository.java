package com.carrent.car;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarCategoryRepository extends JpaRepository<CarCategory, Long> {
    List<CarCategory> findAllByOrderByIdAsc();
}
