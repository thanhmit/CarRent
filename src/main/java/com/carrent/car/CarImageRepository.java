package com.carrent.car;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CarImageRepository extends JpaRepository<CarImage, Long> {
    List<CarImage> findByCarIdOrderByIdAsc(Long carId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CarImage i where i.car.id = :carId order by i.id")
    List<CarImage> findByCarIdForUpdate(@Param("carId") Long carId);

    Optional<CarImage> findByIdAndCarId(Long imageId, Long carId);

    boolean existsByCarId(Long carId);

    @Query(value = "SELECT i.car_id, i.image_url FROM car_images i "
            + "JOIN (SELECT car_id, MIN(id) AS first_id FROM car_images WHERE car_id IN (:carIds) GROUP BY car_id) first_image "
            + "ON i.id = first_image.first_id", nativeQuery = true)
    List<Object[]> findFirstImagesByCarIds(@Param("carIds") List<Long> carIds);
}
