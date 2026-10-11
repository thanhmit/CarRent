package com.carrent.booking;

import com.carrent.car.ApprovalStatus;
import com.carrent.car.Car;
import com.carrent.car.CarRepository;
import com.carrent.car.ListingStatus;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.notification.NotificationService;
import com.carrent.payment.PaymentRepository;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserRepository;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
    private final BookingRepository bookings;
    private final CarRepository cars;
    private final UserRepository users;
    private final NotificationService notifications;
    private final Validator validator;
    private final EntityManager entityManager;
    private final PaymentRepository payments;

    public BookingService(BookingRepository bookings, CarRepository cars, UserRepository users,
                          NotificationService notifications, Validator validator, EntityManager entityManager,
                          PaymentRepository payments) {
        this.bookings = bookings;
        this.cars = cars;
        this.users = users;
        this.notifications = notifications;
        this.validator = validator;
        this.entityManager = entityManager;
        this.payments = payments;
    }

    @Transactional
    public Booking createBooking(BookingCreateForm form, CustomUserPrincipal principal) {
        User customer = requireCustomer(principal);
        if (form == null || !validator.validate(form).isEmpty()) {
            throw new BusinessException("Thông tin đặt xe không hợp lệ. Vui lòng kiểm tra lại.");
        }
        LocalDate start = form.getStartDate();
        LocalDate end = form.getEndDate();
        validateDates(start, end);
        Car car = lockedCar(form.getCarId());
        if (car.getApprovalStatus() != ApprovalStatus.APPROVED || car.getListingStatus() != ListingStatus.ACTIVE) {
            throw new BusinessException("Xe hiện không nhận đơn.");
        }
        if (!bookings.findConflictsForUpdate(car.getId(), start, end).isEmpty()) {
            throw new BusinessException("Xe đã có đơn trùng thời gian thuê.");
        }
        long total;
        try {
            total = Math.multiplyExact(car.getPricePerDay(), ChronoUnit.DAYS.between(start, end));
        } catch (ArithmeticException ex) {
            throw new BusinessException("Tổng tiền thuê vượt giới hạn cho phép. Vui lòng chọn khoảng ngày ngắn hơn.");
        }
        String address = Stream.of(car.getAddressDetail(), car.getDistrict(), car.getProvince())
                .filter(part -> part != null && !part.isBlank()).map(String::trim).collect(Collectors.joining(", "));
        if (address.length() > 300) {
            throw new BusinessException("Địa chỉ nhận xe vượt quá 300 ký tự. Vui lòng liên hệ chủ xe cập nhật địa chỉ.");
        }
        Booking booking = bookings.save(Booking.builder().customer(customer).car(car)
                .startDate(start).endDate(end).pricePerDaySnapshot(car.getPricePerDay()).totalAmount(total)
                .pickupAddress(address).status(BookingStatus.PENDING).customerNote(form.getCustomerNote()).build());
        notifications.notify(car.getOwner().getId(), "BOOKING_CREATED", "Có đơn thuê xe mới",
                car.getBrand() + " " + car.getModel() + " được đặt từ " + start + " đến " + end + ".",
                "BOOKING", booking.getId());
        return booking;
    }

    @Transactional(readOnly = true)
    public boolean checkAvailability(Long carId, LocalDate start, LocalDate end) {
        validateDates(start, end);
        Car car = carId == null ? null : cars.findById(carId).orElse(null);
        if (car == null || car.getApprovalStatus() != ApprovalStatus.APPROVED
                || car.getListingStatus() != ListingStatus.ACTIVE) {
            throw new NotFoundException("Xe không tồn tại.");
        }
        return !bookings.existsConflict(carId, start, end);
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BusinessException("Vui lòng chọn đủ ngày nhận và ngày trả xe.");
        }
        if (start.isBefore(LocalDate.now()) || !start.isBefore(end)) {
            throw new BusinessException("Ngày nhận xe phải từ hôm nay và trước ngày trả xe.");
        }
        if (end.isAfter(LocalDate.of(9999, 12, 31))) {
            throw new BusinessException("Ngày trả xe không được sau 31/12/9999.");
        }
    }

    @Transactional(readOnly = true)
    public User getCustomerForBooking(CustomUserPrincipal principal) {
        return requireCustomer(principal);
    }

    @Transactional(readOnly = true)
    public Page<Booking> findBookingsForCustomer(CustomUserPrincipal principal, Pageable pageable) {
        return bookings.findByCustomerId(requireCustomer(principal).getId(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<Booking> findBookingsForOwner(CustomUserPrincipal principal, Pageable pageable) {
        return bookings.findByCarOwnerId(requireOwner(principal).getId(), pageable);
    }

    @Transactional
    public void cancelBooking(Long id, CustomUserPrincipal principal) {
        User customer = requireCustomer(principal);
        Booking booking = lockedBooking(id);
        if (!booking.getCustomer().getId().equals(customer.getId())) {
            throw new NotFoundException("Đơn thuê không tồn tại.");
        }
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException("Chỉ có thể hủy đơn đang chờ hoặc đã xác nhận.");
        }
        payments.findByBookingIdForUpdate(id).forEach(entityManager::detach);
        var currentPayments = payments.findByBookingIdForUpdate(id);
        booking.setStatus(BookingStatus.CANCELLED);
        int refunded = payments.refundByBookingId(id);
        currentPayments.forEach(entityManager::detach);
        payments.findByBookingIdForUpdate(id);
        if (refunded > 0) {
            notifications.notify(customer.getId(), "BOOKING_CANCELLED", "Đơn thuê đã hủy và hoàn tiền",
                    "Đơn thuê #CR-" + id + " đã được hủy và hoàn tiền (giả lập).", "BOOKING", id);
        }
        notifications.notify(booking.getCar().getOwner().getId(), "BOOKING_CANCELLED", "Khách hàng đã hủy đơn thuê",
                "Khách hàng đã hủy đơn thuê #CR-" + id + ".", "BOOKING", id);
    }

    @Transactional
    public void confirmBooking(Long id, CustomUserPrincipal principal) {
        Booking booking = pendingBookingForOwner(id, principal);
        booking.setStatus(BookingStatus.CONFIRMED);
        notifications.notify(booking.getCustomer().getId(), "BOOKING_CONFIRMED", "Đơn thuê đã được xác nhận",
                "Chủ xe đã xác nhận đơn thuê #CR-" + id + ".", "BOOKING", id);
    }

    @Transactional
    public void completeBooking(Long id, CustomUserPrincipal principal) {
        User owner = requireOwner(principal);
        Booking booking = lockedBooking(id);
        if (!booking.getCar().getOwner().getId().equals(owner.getId())) {
            throw new NotFoundException("Đơn thuê không tồn tại.");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException("Chỉ có thể hoàn tất đơn đã xác nhận.");
        }
        if (!booking.getEndDate().isBefore(LocalDate.now())) {
            throw new BusinessException("Chỉ có thể hoàn tất đơn sau ngày trả xe.");
        }
        booking.setStatus(BookingStatus.COMPLETED);
        notifications.notify(booking.getCustomer().getId(), "BOOKING_COMPLETED", "Đơn thuê đã hoàn tất",
                "Chủ xe đã hoàn tất đơn thuê #CR-" + id + ".", "BOOKING", id);
    }

    @Transactional
    public void rejectBooking(Long id, CustomUserPrincipal principal, String reason) {
        Booking booking = pendingBookingForOwner(id, principal);
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isBlank() || trimmedReason.length() > 500) {
            throw new BusinessException("Vui lòng nhập lý do từ chối từ 1 đến 500 ký tự.");
        }
        booking.setStatus(BookingStatus.REJECTED);
        booking.setOwnerNote(trimmedReason);
        notifications.notify(booking.getCustomer().getId(), "BOOKING_REJECTED", "Đơn thuê đã bị từ chối",
                "Chủ xe từ chối đơn thuê #CR-" + id + ": " + trimmedReason, "BOOKING", id);
    }

    private Booking pendingBookingForOwner(Long id, CustomUserPrincipal principal) {
        User owner = requireOwner(principal);
        Booking booking = lockedBooking(id);
        if (!booking.getCar().getOwner().getId().equals(owner.getId())) {
            throw new NotFoundException("Đơn thuê không tồn tại.");
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessException("Chỉ có thể xử lý đơn đang chờ xác nhận.");
        }
        return booking;
    }

    private Booking lockedBooking(Long id) {
        Booking route = id == null ? null : bookings.findById(id).orElse(null);
        if (route == null) {
            throw new NotFoundException("Đơn thuê không tồn tại.");
        }
        lockedCar(route.getCar().getId());
        Booking booking = bookings.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Đơn thuê không tồn tại."));
        // Hibernate can omit FOR UPDATE on refresh of an already locked entity; detach and lock-read again.
        entityManager.detach(booking);
        return bookings.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Đơn thuê không tồn tại."));
    }

    private User requireOwner(CustomUserPrincipal principal) {
        if (principal == null || principal.getId() == null || principal.getRole() != Role.OWNER) {
            throw new ForbiddenException("Chỉ chủ xe được phép xử lý đơn thuê.");
        }
        User owner = users.findById(principal.getId())
                .orElseThrow(() -> new ForbiddenException("Tài khoản không được phép xử lý đơn thuê."));
        if (owner.getRole() != Role.OWNER || owner.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Tài khoản không được phép xử lý đơn thuê.");
        }
        return owner;
    }

    @Transactional(readOnly = true)
    public Booking getBookingForView(Long id, CustomUserPrincipal principal) {
        if (principal == null || principal.getId() == null) {
            throw new NotFoundException("Đơn thuê không tồn tại.");
        }
        User viewer = users.findById(principal.getId()).orElse(null);
        if (viewer == null || viewer.getStatus() != UserStatus.ACTIVE || viewer.getRole() != principal.getRole()) {
            throw new NotFoundException("Đơn thuê không tồn tại.");
        }
        Booking booking = id == null ? null : bookings.findById(id).orElse(null);
        if (booking == null || !(viewer.getRole() == Role.ADMIN
                || viewer.getRole() == Role.CUSTOMER && viewer.getId().equals(booking.getCustomer().getId())
                || viewer.getRole() == Role.OWNER && viewer.getId().equals(booking.getCar().getOwner().getId()))) {
            throw new NotFoundException("Đơn thuê không tồn tại.");
        }
        return booking;
    }

    private User requireCustomer(CustomUserPrincipal principal) {
        if (principal == null || principal.getId() == null || principal.getRole() != Role.CUSTOMER) {
            throw new ForbiddenException("Chỉ khách hàng được phép đặt xe.");
        }
        User customer = users.findById(principal.getId())
                .orElseThrow(() -> new ForbiddenException("Tài khoản không được phép đặt xe."));
        if (customer.getRole() != Role.CUSTOMER || customer.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Tài khoản không được phép đặt xe.");
        }
        return customer;
    }

    private Car lockedCar(Long carId) {
        entityManager.flush();
        Car car = cars.findByIdForUpdate(carId).orElseThrow(() -> new NotFoundException("Xe không tồn tại."));
        // A locking query can return a stale managed Car; reread under the retained lock.
        entityManager.detach(car);
        return cars.findByIdForUpdate(carId).orElseThrow(() -> new NotFoundException("Xe không tồn tại."));
    }
}
