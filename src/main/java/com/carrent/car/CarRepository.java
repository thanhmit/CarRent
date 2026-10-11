package com.carrent.car;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CarRepository extends JpaRepository<Car, Long>, JpaSpecificationExecutor<Car> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select car from Car car where car.id = :id")
    Optional<Car> findByIdForUpdate(@Param("id") Long id);

    Page<Car> findByOwnerId(Long ownerId, Pageable pageable);

    long countByOwnerIdAndApprovalStatusAndListingStatus(Long ownerId, ApprovalStatus approvalStatus, ListingStatus listingStatus);

    long countByOwnerIdAndApprovalStatus(Long ownerId, ApprovalStatus approvalStatus);

    long countByApprovalStatus(ApprovalStatus approvalStatus);
}
