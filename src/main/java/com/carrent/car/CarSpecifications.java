package com.carrent.car;

import com.carrent.booking.Booking;
import com.carrent.booking.BookingStatus;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Reusable JPA Specifications for car search. */
public final class CarSpecifications {
    private CarSpecifications() {
    }

    public static Specification<Car> brandOrModelContains(String query) {
        String pattern = "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, criteriaQuery, builder) -> builder.or(
                builder.like(builder.lower(root.get("brand")), pattern, '\\'),
                builder.like(builder.lower(root.get("model")), pattern, '\\'));
    }

    public static Specification<Car> approvalEq(ApprovalStatus status) {
        return (root, criteriaQuery, builder) -> builder.equal(root.get("approvalStatus"), status);
    }

    public static Specification<Car> listingEq(ListingStatus status) {
        return (root, criteriaQuery, builder) -> builder.equal(root.get("listingStatus"), status);
    }

    public static Specification<Car> isPublic() {
        return Specification.allOf(approvalEq(ApprovalStatus.APPROVED), listingEq(ListingStatus.ACTIVE));
    }

    public static Specification<Car> availableBetween(LocalDate start, LocalDate end) {
        return (root, query, builder) -> {
            var conflicts = query.subquery(Long.class);
            var booking = conflicts.from(Booking.class);
            conflicts.select(booking.get("id")).where(
                    builder.equal(booking.get("car"), root),
                    booking.get("status").in(BookingStatus.PENDING, BookingStatus.CONFIRMED),
                    builder.lessThan(booking.get("startDate"), end),
                    builder.greaterThan(booking.get("endDate"), start));
            return builder.not(builder.exists(conflicts));
        };
    }

    public static Specification<Car> categoryEq(Long categoryId) {
        return (root, criteriaQuery, builder) -> builder.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Car> provinceEq(String province) {
        return (root, criteriaQuery, builder) -> builder.equal(root.get("province"), province.trim());
    }

    public static Specification<Car> priceBetween(Long minPrice, Long maxPrice) {
        return (root, criteriaQuery, builder) -> {
            if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
                return builder.disjunction();
            }
            if (minPrice != null && maxPrice != null) {
                return builder.between(root.get("pricePerDay"), minPrice, maxPrice);
            }
            if (minPrice != null) {
                return builder.greaterThanOrEqualTo(root.get("pricePerDay"), minPrice);
            }
            if (maxPrice != null) {
                return builder.lessThanOrEqualTo(root.get("pricePerDay"), maxPrice);
            }
            return builder.conjunction();
        };
    }

    public static Specification<Car> seatEq(Integer seatCount) {
        return (root, criteriaQuery, builder) -> builder.equal(root.get("seatCount"), seatCount);
    }

    public static Specification<Car> transmissionEq(Transmission transmission) {
        return (root, criteriaQuery, builder) -> builder.equal(root.get("transmission"), transmission);
    }

    public static Specification<Car> fuelEq(FuelType fuelType) {
        return (root, criteriaQuery, builder) -> builder.equal(root.get("fuelType"), fuelType);
    }

    /** Escapes LIKE wildcards so user input matches literally. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
