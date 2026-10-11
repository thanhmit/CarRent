package com.carrent.car;

import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.storage.StorageService;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserRepository;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CarService {
    private static final Logger log = LoggerFactory.getLogger(CarService.class);

    private final CarRepository cars;
    private final CarImageRepository images;
    private final CarCategoryRepository categories;
    private final UserRepository users;
    private final StorageService storage;
    private final Validator validator;
    private final EntityManager entityManager;

    public CarService(CarRepository cars, CarImageRepository images, CarCategoryRepository categories,
                      UserRepository users, StorageService storage, Validator validator, EntityManager entityManager) {
        this.cars = cars;
        this.images = images;
        this.categories = categories;
        this.users = users;
        this.storage = storage;
        this.validator = validator;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Page<Car> findCarsByOwner(Long ownerId, Pageable pageable) {
        return cars.findByOwnerId(ownerId, pageable);
    }

    @Transactional(readOnly = true)
    public long countPublicCars(Long ownerId) {
        return cars.countByOwnerIdAndApprovalStatusAndListingStatus(ownerId, ApprovalStatus.APPROVED, ListingStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public long countPendingCars(Long ownerId) {
        return cars.countByOwnerIdAndApprovalStatus(ownerId, ApprovalStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Page<Car> searchCarsForAdmin(String q, ApprovalStatus approval, ListingStatus listing, Pageable pageable,
                                        CustomUserPrincipal principal) {
        requireAdmin(principal);
        List<Specification<Car>> specifications = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            specifications.add(CarSpecifications.brandOrModelContains(q));
        }
        if (approval != null) {
            specifications.add(CarSpecifications.approvalEq(approval));
        }
        if (listing != null) {
            specifications.add(CarSpecifications.listingEq(listing));
        }
        return cars.findAll(Specification.allOf(specifications), pageable);
    }

    @Transactional(readOnly = true)
    public Page<Car> searchPublicCars(CarSearchCriteria criteria, Pageable pageable) {
        CarSearchCriteria search = criteria == null ? new CarSearchCriteria() : criteria;
        List<Specification<Car>> specifications = new ArrayList<>();
        specifications.add(CarSpecifications.isPublic());
        if (search.getQ() != null && !search.getQ().isBlank()) {
            specifications.add(CarSpecifications.brandOrModelContains(search.getQ()));
        }
        if (search.getCategoryId() != null) {
            specifications.add(CarSpecifications.categoryEq(search.getCategoryId()));
        }
        if (search.getProvince() != null && !search.getProvince().isBlank()) {
            specifications.add(CarSpecifications.provinceEq(search.getProvince()));
        }
        if (search.getMinPrice() != null || search.getMaxPrice() != null) {
            specifications.add(CarSpecifications.priceBetween(search.getMinPrice(), search.getMaxPrice()));
        }
        if (search.getSeatCount() != null) {
            specifications.add(CarSpecifications.seatEq(search.getSeatCount()));
        }
        if (search.getTransmission() != null) {
            specifications.add(CarSpecifications.transmissionEq(search.getTransmission()));
        }
        if (search.getFuelType() != null) {
            specifications.add(CarSpecifications.fuelEq(search.getFuelType()));
        }

        Sort sort = switch (search.getSort() == null ? "newest" : search.getSort()) {
            case "priceAsc" -> Sort.by(Sort.Order.asc("pricePerDay"), Sort.Order.desc("id"));
            case "priceDesc" -> Sort.by(Sort.Order.desc("pricePerDay"), Sort.Order.desc("id"));
            default -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        };
        return cars.findAll(Specification.allOf(specifications),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort));
    }

    @Transactional(readOnly = true)
    public long countPendingCars() {
        return cars.countByApprovalStatus(ApprovalStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Car getCarForAdmin(Long carId, CustomUserPrincipal principal) {
        requireAdmin(principal);
        Car car = carId == null ? null : cars.findById(carId).orElse(null);
        if (car == null) {
            throw new NotFoundException("Xe không tồn tại.");
        }
        return car;
    }

    @Transactional(readOnly = true)
    public Car getPublicCar(Long carId, CustomUserPrincipal principal) {
        Car car = carId == null ? null : cars.findById(carId).orElse(null);
        if (car == null) {
            throw new NotFoundException("Xe không tồn tại.");
        }
        if (car.getApprovalStatus() == ApprovalStatus.APPROVED
                && car.getListingStatus() == ListingStatus.ACTIVE) {
            return car;
        }
        if (principal != null && principal.getId() != null
                && (principal.getRole() == Role.OWNER || principal.getRole() == Role.ADMIN)) {
            User viewer = users.findById(principal.getId()).orElse(null);
            if (viewer != null && viewer.getStatus() == UserStatus.ACTIVE
                    && viewer.getRole() == principal.getRole()
                    && (viewer.getRole() == Role.ADMIN || viewer.getId().equals(car.getOwner().getId()))) {
                return car;
            }
        }
        throw new NotFoundException("Xe không tồn tại.");
    }

    @Transactional
    public void approveCar(Long carId, CustomUserPrincipal principal) {
        Car car = pendingCarForAdmin(carId, principal);
        car.setApprovalStatus(ApprovalStatus.APPROVED);
        car.setRejectionReason(null);
    }

    @Transactional
    public void rejectCar(Long carId, String reason, CustomUserPrincipal principal) {
        Car car = pendingCarForAdmin(carId, principal);
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("Vui lòng nhập lý do từ chối.");
        }
        if (trimmed.length() > 500) {
            throw new BusinessException("Lý do từ chối không được quá 500 ký tự.");
        }
        car.setApprovalStatus(ApprovalStatus.REJECTED);
        car.setRejectionReason(trimmed);
    }

    private User requireAdmin(CustomUserPrincipal principal) {
        if (principal == null || principal.getRole() != Role.ADMIN || principal.getId() == null) {
            throw new ForbiddenException("Bạn không có quyền quản trị.");
        }
        User admin = users.findById(principal.getId())
                .orElseThrow(() -> new ForbiddenException("Bạn không có quyền quản trị."));
        if (admin.getRole() != Role.ADMIN || admin.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Bạn không có quyền quản trị.");
        }
        return admin;
    }

    private Car pendingCarForAdmin(Long carId, CustomUserPrincipal principal) {
        requireAdmin(principal);
        Car car = lockedCar(carId);
        if (car == null) {
            throw new NotFoundException("Xe không tồn tại.");
        }
        if (car.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException("Chỉ có thể duyệt hoặc từ chối xe đang chờ duyệt.");
        }
        return car;
    }

    @Transactional
    public Car createCar(CarForm form, List<MultipartFile> uploads, CustomUserPrincipal principal) {
        User owner = requireOwner(principal);
        CarCategory category = validateAndResolveCategory(form);
        List<MultipartFile> validUploads = validateUploads(uploads);
        List<String> storedPaths = registerUploadCleanup();

        Car car = new Car();
        car.setOwner(owner);
        applyForm(car, form, category);
        car.setApprovalStatus(ApprovalStatus.PENDING);
        car.setListingStatus(ListingStatus.ACTIVE);
        car = cars.save(car);
        storeImages(car, validUploads, storedPaths);
        return car;
    }

    @Transactional
    public Car updateCar(Long carId, CarForm form, CustomUserPrincipal principal) {
        return updateCar(carId, form, null, principal);
    }

    @Transactional
    public Car updateCar(Long carId, CarForm form, List<MultipartFile> uploads, CustomUserPrincipal principal) {
        User owner = requireOwner(principal);
        Car car = ownedCarForUpdate(carId, owner);
        CarCategory category = validateAndResolveCategory(form);
        List<MultipartFile> validUploads = validateUploads(uploads);
        List<String> storedPaths = registerUploadCleanup();

        applyForm(car, form, category);
        cars.save(car);
        storeImages(car, validUploads, storedPaths);
        return car;
    }

    @Transactional(readOnly = true)
    public Car getOwnedCar(Long carId, CustomUserPrincipal principal) {
        return ownedCar(carId, requireOwner(principal));
    }

    @Transactional
    public void deleteImage(Long carId, Long imageId, CustomUserPrincipal principal) {
        User owner = requireOwner(principal);
        Car car = ownedCarForUpdate(carId, owner);
        CarImage image = imageId == null ? null : images.findByIdAndCarId(imageId, car.getId()).orElse(null);
        if (image == null) {
            throw new NotFoundException("Ảnh không tồn tại.");
        }
        String webPath = image.getImageUrl();
        images.delete(image);
        // The file is removed only once the row deletion has committed; a rollback keeps the image.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(webPath);
                } catch (RuntimeException error) {
                    log.error("Could not delete committed car image file {}", webPath, error);
                }
            }
        });
    }

    @Transactional
    public void deactivateCar(Long carId, CustomUserPrincipal principal) {
        Car car = ownedCarForUpdate(carId, requireOwner(principal));
        car.setListingStatus(ListingStatus.INACTIVE);
    }

    @Transactional
    public void activateCar(Long carId, CustomUserPrincipal principal) {
        Car car = ownedCarForUpdate(carId, requireOwner(principal));
        activateListing(car);
    }

    @Transactional
    public void deactivateCarForAdmin(Long carId, CustomUserPrincipal principal) {
        requireAdmin(principal);
        Car car = lockedCar(carId);
        if (car == null) {
            throw new NotFoundException("Xe không tồn tại.");
        }
        car.setListingStatus(ListingStatus.INACTIVE);
    }

    @Transactional
    public void activateCarForAdmin(Long carId, CustomUserPrincipal principal) {
        requireAdmin(principal);
        Car car = lockedCar(carId);
        if (car == null) {
            throw new NotFoundException("Xe không tồn tại.");
        }
        activateListing(car);
    }

    private static void activateListing(Car car) {
        if (car.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw new BusinessException("Xe chưa được duyệt, không thể đăng lại");
        }
        car.setListingStatus(ListingStatus.ACTIVE);
    }

    private User requireOwner(CustomUserPrincipal principal) {
        if (principal == null || principal.getRole() != Role.OWNER || principal.getId() == null) {
            throw new ForbiddenException("Bạn không có quyền đăng xe.");
        }
        User owner = users.findById(principal.getId())
                .orElseThrow(() -> new ForbiddenException("Bạn không có quyền đăng xe."));
        if (owner.getRole() != Role.OWNER || owner.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Bạn không có quyền đăng xe.");
        }
        return owner;
    }

    private Car ownedCar(Long carId, User owner) {
        Car car = carId == null ? null : cars.findById(carId).orElse(null);
        if (car == null) {
            throw new NotFoundException("Xe không tồn tại.");
        }
        if (!car.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenException("Bạn không có quyền với xe này");
        }
        return car;
    }

    private Car ownedCarForUpdate(Long carId, User owner) {
        Car car = lockedCar(carId);
        if (car == null) {
            throw new NotFoundException("Xe không tồn tại.");
        }
        if (!car.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenException("Bạn không có quyền với xe này");
        }
        return car;
    }

    private Car lockedCar(Long carId) {
        entityManager.flush();
        Car car = carId == null ? null : cars.findByIdForUpdate(carId).orElse(null);
        if (car != null) {
            // A lock can return an already-managed entity with stale approval/listing fields.
            entityManager.detach(car);
            car = cars.findByIdForUpdate(carId).orElseThrow();
        }
        return car;
    }

    private CarCategory validateAndResolveCategory(CarForm form) {
        if (form == null || !validator.validate(form).isEmpty()) {
            throw new BusinessException("Thông tin xe không hợp lệ. Vui lòng kiểm tra lại.");
        }
        return categories.findById(form.getCategoryId())
                .orElseThrow(() -> new BusinessException("Loại xe không tồn tại."));
    }

    private static void applyForm(Car car, CarForm form, CarCategory category) {
        car.setCategory(category);
        car.setBrand(form.getBrand().trim());
        car.setModel(form.getModel().trim());
        car.setManufactureYear(form.getManufactureYear());
        car.setLicensePlate(trimOptional(form.getLicensePlate()));
        car.setColor(trimOptional(form.getColor()));
        car.setSeatCount(form.getSeatCount());
        car.setTransmission(form.getTransmission());
        car.setFuelType(form.getFuelType());
        car.setDescription(trimOptional(form.getDescription()));
        car.setPricePerDay(form.getPricePerDay());
        car.setProvince(form.getProvince().trim());
        car.setDistrict(trimOptional(form.getDistrict()));
        car.setAddressDetail(trimOptional(form.getAddressDetail()));
    }

    private static String trimOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<MultipartFile> validateUploads(List<MultipartFile> uploads) {
        List<MultipartFile> valid = new ArrayList<>();
        if (uploads != null) {
            for (MultipartFile upload : uploads) {
                if (upload != null && !upload.isEmpty()) {
                    storage.validate(upload);
                    valid.add(upload);
                }
            }
        }
        return valid;
    }

    private void storeImages(Car car, List<MultipartFile> validUploads, List<String> storedPaths) {
        for (MultipartFile upload : validUploads) {
            String path = storage.store(upload);
            storedPaths.add(path);
            images.save(CarImage.builder().car(car).imageUrl(path).build());
        }
    }

    /** Registers rollback cleanup for uploads stored during the current transaction. */
    private List<String> registerUploadCleanup() {
        List<String> storedPaths = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    for (String path : storedPaths) {
                        try {
                            storage.delete(path);
                        } catch (RuntimeException error) {
                            log.error("Could not clean up rolled-back car upload {}", path, error);
                        }
                    }
                }
            }
        });
        return storedPaths;
    }
}
