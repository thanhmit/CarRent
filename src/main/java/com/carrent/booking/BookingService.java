package com.carrent.booking;

import com.carrent.car.ApprovalStatus;
import com.carrent.car.Car;
import com.carrent.car.CarRepository;
import com.carrent.car.ListingStatus;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.notification.NotificationService;
import com.carrent.payment.*;
import com.carrent.inspection.InspectionService;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserRepository;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.Clock;
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
    private final Clock clock;
    private final PaymentObligationRepository obligations;
    private final PaymentIntentTransactions intentTransactions;
    private final InspectionService inspections;
    private final PaymentIntentRepository intents;
    private final RefundService refunds;

    /** Payment-core owns aggregate and financial locks and commits receipt/booking atomically. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void applyAdvance(Long receiptId) {
        var receipt = entityManager.find(PaymentReceipt.class, receiptId);
        if (receipt == null || receipt.getIntent() == null) throw new BusinessException("Thiếu giao dịch trả trước.");
        var intent = receipt.getIntent();
        var obligation = intent.getObligation();
        var booking = requireLockedAdvance(intent);
        if (receipt.getStatus() == PaymentReceiptStatus.REFUND_REQUIRED && receipt.getAllocatedAmount() == 0) {
            if (!receipt.getAmount().equals(intent.getAmount())) throw new BusinessException("Giao dịch lệch số tiền cần đối soát.");
            RefundReason reason = booking.getAdvancePaidAt() != null ? RefundReason.DUPLICATE_PAYMENT
                    : booking.getStatus() == BookingStatus.EXPIRED && receipt.getCreditedAt().isBefore(intent.getExpiresAt())
                    ? RefundReason.SYSTEM_CONFIRMATION_DELAY : RefundReason.LATE_ADVANCE;
            if (booking.getAdvancePaidAt() == null && booking.getStatus() != BookingStatus.CANCELLED
                    && booking.getStatus() != BookingStatus.EXPIRED) throw new BusinessException("Đơn thuê chưa kết thúc giữ chỗ.");
            refunds.request(reason == RefundReason.DUPLICATE_PAYMENT ? "DUPLICATE:" + receiptId : reason.name() + ":" + receiptId,
                    receiptId, receipt.getAmount(), reason);
            return;
        }
        if (receipt.getStatus() != PaymentReceiptStatus.APPLIED
                || !receipt.getAmount().equals(booking.getAdvanceAmount())
                || !receipt.getAllocatedAmount().equals(receipt.getAmount())
                || obligation.getStatus() != PaymentObligationStatus.SETTLED
                || !obligation.getAmountSettled().equals(obligation.getAmountDue())
                || intent.getStatus() != PaymentIntentStatus.RECEIVED
                || !receipt.getCreditedAt().isBefore(intent.getExpiresAt())) {
            throw new BusinessException("Giao dịch chưa đủ điều kiện xác nhận trả trước.");
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED && receipt.getCreditedAt().equals(booking.getAdvancePaidAt())) return;
        if (booking.getStatus() != BookingStatus.PENDING || booking.getAdvancePaidAt() != null
                || booking.getVehicleReceivedAt() != null) throw new BusinessException("Đơn thuê không còn cho phép xác nhận trả trước.");
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setAdvancePaidAt(receipt.getCreditedAt());
        for (Long recipient : java.util.List.of(booking.getCustomer().getId(), booking.getCar().getOwner().getId())) {
            notifications.notify(recipient, "BOOKING_CONFIRMED", "Đã nhận trả trước và xác nhận đặt xe",
                    "Đơn thuê #CR-" + booking.getId() + " đã nhận đủ trả trước. Hợp đồng và giao xe sẽ được xác nhận riêng.",
                    "BOOKING", booking.getId());
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void applyBalance(Long receiptId) {
        var receipt = entityManager.find(PaymentReceipt.class, receiptId);
        if (receipt == null || receipt.getIntent() == null) throw new BusinessException("Thiếu giao dịch thanh toán còn lại.");
        var intent = receipt.getIntent();
        var obligation = intent.getObligation();
        var booking = entityManager.find(Booking.class, obligation.getSubjectId());
        if (booking == null || !writeLocked(booking) || !writeLocked(booking.getCar())
                || !writeLocked(obligation) || !writeLocked(intent)) {
            throw new IllegalStateException("Lock Car, Booking, obligation and intent before applying a balance");
        }
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()
                || obligation.getPurpose() != PaymentPurpose.BALANCE || obligation.getSubjectType() != PaymentSubjectType.BOOKING
                || !("BOOKING:" + booking.getId() + ":BALANCE").equals(obligation.getBusinessKey())
                || !obligation.getPayer().getId().equals(booking.getCustomer().getId())
                || !obligation.getCollector().getId().equals(booking.getCar().getOwner().getId())
                || !obligation.getAmountDue().equals(booking.getBalanceAmount())
                || booking.getAdvancePaidAt() == null || booking.getVehicleReceivedAt() == null || booking.getVehicleReturnedAt() == null
                || booking.getStatus() != BookingStatus.CONFIRMED && booking.getStatus() != BookingStatus.COMPLETED
                || obligation.getStatus() != PaymentObligationStatus.SETTLED
                || !obligation.getAmountSettled().equals(obligation.getAmountDue())
                || receipt.getStatus() != PaymentReceiptStatus.APPLIED && receipt.getStatus() != PaymentReceiptStatus.REFUND_REQUIRED) {
            throw new BusinessException("Giao dịch chưa đủ điều kiện hoàn tất đơn thuê.");
        }
        long excess = Math.subtractExact(receipt.getAmount(), receipt.getAllocatedAmount());
        if (excess > 0) {
            boolean duplicate = receipt.getAllocatedAmount() == 0;
            refunds.request((duplicate ? "DUPLICATE:" : "EXCESS:") + receiptId, receiptId, excess,
                    duplicate ? RefundReason.DUPLICATE_PAYMENT : RefundReason.EXCESS_BALANCE);
        }
        if (booking.getStatus() == BookingStatus.COMPLETED) return;
        booking.setStatus(BookingStatus.COMPLETED);
        for (Long recipient : java.util.List.of(booking.getCustomer().getId(), booking.getCar().getOwner().getId())) {
            notifications.notify(recipient, "BOOKING_COMPLETED", "Đơn thuê đã hoàn tất",
                    "Đơn thuê #CR-" + booking.getId() + " đã nhận lại xe và thanh toán đủ phần còn lại.", "BOOKING", booking.getId());
        }
    }

    private Booking requireLockedAdvance(PaymentIntent intent) {
        var obligation = intent.getObligation();
        var booking = entityManager.find(Booking.class, obligation.getSubjectId());
        if (booking == null || !writeLocked(booking) || !writeLocked(booking.getCar())
                || !writeLocked(obligation) || !writeLocked(intent)) {
            throw new IllegalStateException("Lock Car, Booking, obligation and intent before applying an advance");
        }
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()
                || obligation.getPurpose() != PaymentPurpose.ADVANCE || obligation.getSubjectType() != PaymentSubjectType.BOOKING
                || !("BOOKING:" + booking.getId() + ":ADVANCE").equals(obligation.getBusinessKey())
                || !obligation.getPayer().getId().equals(booking.getCustomer().getId())
                || !obligation.getCollector().getId().equals(booking.getCar().getOwner().getId())
                || booking.getOwnerAcceptedAt() == null || booking.getBeforeAcceptedAt() == null
                || !intent.getExpiresAt().equals(booking.getHoldExpiresAt())
                || !obligation.getAmountDue().equals(booking.getAdvanceAmount())
                || !intent.getAmount().equals(booking.getAdvanceAmount())) {
            throw new BusinessException("Yêu cầu trả trước không khớp đơn thuê đã đồng ý.");
        }
        return booking;
    }

    private boolean writeLocked(Object row) {
        var mode = entityManager.getLockMode(row);
        // Hibernate can upgrade the held write lock after flushing financial updates.
        return mode == jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
                || mode == jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT;
    }

    /** Called only by payment expiry after the aggregate/financial locks have been acquired. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void expireHold(Long bookingId) {
        var intent = intents.findFirstByObligationBusinessKeyOrderByCreatedAtDescIdDesc("BOOKING:" + bookingId + ":ADVANCE")
                .orElseThrow(() -> new BusinessException("Thiếu yêu cầu trả trước để kết thúc giữ chỗ."));
        var booking = requireLockedAdvance(intent);
        var obligation = intent.getObligation();
        if (booking.getStatus() == BookingStatus.EXPIRED) return;
        if (intent.getStatus() != PaymentIntentStatus.EXPIRED || clock.instant().isBefore(booking.getHoldExpiresAt())
                || booking.getStatus() != BookingStatus.PENDING || booking.getAdvancePaidAt() != null
                || booking.getVehicleReceivedAt() != null || obligation.getStatus() != PaymentObligationStatus.OPEN
                || obligation.getAmountSettled() != 0) throw new BusinessException("Đơn thuê không còn cho phép hết giữ chỗ.");
        booking.setStatus(BookingStatus.EXPIRED);
        obligation.setStatus(PaymentObligationStatus.EXPIRED); obligation.setUpdatedAt(clock.instant());
        for (Long recipient : java.util.List.of(booking.getCustomer().getId(), booking.getCar().getOwner().getId())) {
            notifications.notify(recipient, "BOOKING_EXPIRED", "Đã hết thời gian giữ chỗ",
                    "Đơn thuê #CR-" + bookingId + " đã hết giữ chỗ. Không chuyển tiền theo QR cũ; giao dịch đến sau sẽ được đối soát và hoàn.",
                    "BOOKING", bookingId);
        }
    }

    public BookingService(BookingRepository bookings, CarRepository cars, UserRepository users,
                          NotificationService notifications, Validator validator, EntityManager entityManager,
                          PaymentRepository payments, Clock clock, PaymentObligationRepository obligations,
                          PaymentIntentTransactions intentTransactions, InspectionService inspections,
                          PaymentIntentRepository intents, RefundService refunds) {
        this.bookings = bookings;
        this.cars = cars;
        this.users = users;
        this.notifications = notifications;
        this.validator = validator;
        this.entityManager = entityManager;
        this.payments = payments;
        this.clock = clock;
        this.obligations = obligations;
        this.intentTransactions = intentTransactions;
        this.inspections = inspections;
        this.intents = intents;
        this.refunds = refunds;
    }

    @Transactional
    public PaymentIntentView acceptBefore(Long id, CustomUserPrincipal principal) {
        User customer = requireCustomer(principal);
        entityManager.refresh(customer);
        if (customer.getStatus() != UserStatus.ACTIVE || customer.getRole() != Role.CUSTOMER) {
            throw new ForbiddenException("Tài khoản không được phép đồng ý biên bản.");
        }
        Booking booking = lockedBooking(id);
        if (!booking.getCustomer().getId().equals(customer.getId())) throw new NotFoundException("Đơn thuê không tồn tại.");
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()
                || booking.getStatus() != BookingStatus.PENDING || booking.getOwnerAcceptedAt() == null
                || booking.getVehicleReceivedAt() != null) {
            throw new BusinessException("Đơn thuê chưa sẵn sàng hoặc không còn cho phép đồng ý BEFORE.");
        }
        long advance = RentalPolicy.advance(booking.getTotalAmount());
        long balance = RentalPolicy.balance(booking.getTotalAmount());
        var obligation = obligations.findByBusinessKeyForUpdate("BOOKING:" + id + ":ADVANCE").orElse(null);
        if (obligation == null) {
            if (booking.getBeforeAcceptedAt() != null) throw new BusinessException("Thiếu yêu cầu trả trước của biên bản đã đồng ý.");
            obligation = obligations.saveAndFlush(PaymentObligation.builder().businessKey("BOOKING:" + id + ":ADVANCE")
                    .purpose(PaymentPurpose.ADVANCE).subjectType(PaymentSubjectType.BOOKING).subjectId(id)
                    .payer(customer).collector(booking.getCar().getOwner()).amountDue(advance)
                    .createdAt(clock.instant()).updatedAt(clock.instant()).build());
        } else {
            entityManager.detach(obligation);
            obligation = obligations.findByIdForUpdate(obligation.getId()).orElseThrow();
            if (obligation.getAmountDue() != advance) throw new BusinessException("Số tiền trả trước không khớp đơn thuê.");
        }
        var intent = intentTransactions.openForLockedBooking(new PaymentRoutes.Route(obligation.getId(),
                PaymentSubject.BOOKING_ADVANCE, id, booking.getCar().getId()), principal);
        if (booking.getBeforeAcceptedAt() == null) {
            entityManager.find(PaymentObligation.class, obligation.getId()).setDueAt(intent.expiresAt());
            inspections.acceptBeforeLocked(booking, customer);
            booking.setBeforeAcceptedAt(clock.instant());
            booking.setAdvanceAmount(advance); booking.setBalanceAmount(balance);
            booking.setHoldExpiresAt(intent.expiresAt());
        } else if (!intent.expiresAt().equals(booking.getHoldExpiresAt())) {
            throw new BusinessException("Không được gia hạn giữ chỗ của biên bản đã đồng ý.");
        }
        entityManager.flush();
        // Reuse the locked intent view: a plain refresh can miss a concurrent winner at REPEATABLE READ.
        return new PaymentIntentView(intent.id(), intent.code(), intent.amount(), intent.expiresAt(), intent.status(), intent.qrUrl(),
                new PaymentIntentView.Rental(id, booking.getAdvanceAmount(), booking.getBalanceAmount(), false, true, false));
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
        if ("VIETQR_V1".equals(booking.getWorkflowVersion())) {
            endUnpaidAdvance(booking);
            return;
        }
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException("Chỉ có thể hủy đơn đang chờ hoặc đã xác nhận.");
        }
        if (booking.getVehicleReceivedAt() != null) {
            throw new BusinessException("Không thể hủy đơn thuê đã giao xe.");
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

    /** Payment-core has already locked Car, Booking, obligation and intent. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void cancelAdvanceLocked(Long intentId) {
        var intent = entityManager.find(PaymentIntent.class, intentId);
        if (intent == null || intent.getObligation().getPurpose() != PaymentPurpose.ADVANCE) {
            throw new NotFoundException("Không tìm thấy lần trả trước.");
        }
        var booking = entityManager.find(Booking.class, intent.getObligation().getSubjectId());
        if (booking == null || entityManager.getLockMode(booking) != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
                || entityManager.getLockMode(booking.getCar()) != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) {
            throw new IllegalStateException("Lock Car and Booking before cancelling an advance");
        }
        endUnpaidAdvance(booking);
    }

    private void endUnpaidAdvance(Booking booking) {
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion())) throw new BusinessException("Không thuộc luồng trả trước mới.");
        if (booking.getVehicleReceivedAt() != null) throw new BusinessException("Không thể hủy đơn thuê đã giao xe.");
        if (booking.getStatus() == BookingStatus.CANCELLED) return;
        if (booking.getStatus() != BookingStatus.PENDING || booking.getAdvancePaidAt() != null) {
            throw new BusinessException("Chỉ được hủy yêu cầu chưa nhận tiền và chưa giao xe tại bước này.");
        }
        var obligation = obligations.findByBusinessKeyForUpdate("BOOKING:" + booking.getId() + ":ADVANCE").orElse(null);
        if (obligation != null) {
            entityManager.flush();
            entityManager.detach(obligation);
            obligation = obligations.findByIdForUpdate(obligation.getId()).orElseThrow();
            if (obligation.getAmountSettled() != 0 || obligation.getStatus() == PaymentObligationStatus.SETTLED) {
                throw new BusinessException("Khoản trả trước đã nhận cần được xử lý theo luồng hủy/hoàn.");
            }
            if (!obligation.getPayer().getId().equals(booking.getCustomer().getId())
                    || !obligation.getCollector().getId().equals(booking.getCar().getOwner().getId())) {
                throw new BusinessException("Chủ thể thanh toán không khớp đơn thuê.");
            }
            intents.findByObligationIdAndStatus(obligation.getId(), PaymentIntentStatus.ACTIVE).forEach(entityManager::detach);
            for (var intent : intents.findByObligationIdAndStatus(obligation.getId(), PaymentIntentStatus.ACTIVE)) {
                intent.setStatus(PaymentIntentStatus.CANCELLED); intent.setCancelledAt(clock.instant());
            }
            obligation.setStatus(PaymentObligationStatus.CANCELLED); obligation.setUpdatedAt(clock.instant());
        } else if (booking.getBeforeAcceptedAt() != null) {
            throw new BusinessException("Thiếu yêu cầu trả trước; cần kiểm tra dữ liệu trước khi hủy.");
        }
        booking.setStatus(BookingStatus.CANCELLED); booking.setCancelledAt(clock.instant()); booking.setCancellationFee(0L);
        notifications.notify(booking.getCar().getOwner().getId(), "BOOKING_CANCELLED", "Khách hàng đã hủy đơn thuê",
                "Khách hàng đã hủy đơn thuê #CR-" + booking.getId() + ". Chưa ghi nhận tiền trả trước.", "BOOKING", booking.getId());
    }

    @Transactional
    public void confirmBooking(Long id, CustomUserPrincipal principal) {
        Booking booking = pendingBookingForOwner(id, principal);
        if ("VIETQR_V1".equals(booking.getWorkflowVersion())) {
            if (booking.getOwnerAcceptedAt() != null) return;
            booking.setOwnerAcceptedAt(clock.instant());
            notifications.notify(booking.getCustomer().getId(), "BOOKING_OWNER_ACCEPTED", "Chủ xe đã chấp nhận yêu cầu",
                    "Chủ xe đã chấp nhận yêu cầu #CR-" + id + " và sẽ chuẩn bị biên bản trước thuê.", "BOOKING", id);
            return;
        }
        booking.setStatus(BookingStatus.CONFIRMED);
        notifications.notify(booking.getCustomer().getId(), "BOOKING_CONFIRMED", "Đơn thuê đã được xác nhận",
                "Chủ xe đã xác nhận đơn thuê #CR-" + id + ".", "BOOKING", id);
    }

    @Transactional
    public void confirmHandover(Long id, boolean contractSigned, CustomUserPrincipal principal) {
        User owner = requireOwner(principal);
        Booking booking = lockedBooking(id);
        if (!booking.getCar().getOwner().getId().equals(owner.getId())) throw new NotFoundException("Đơn thuê không tồn tại.");
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()) {
            throw new BusinessException("Đơn thuê không thuộc luồng giao xe này.");
        }
        if (!contractSigned) throw new BusinessException("Vui lòng xác nhận đã ký hợp đồng thuê xe với khách hàng.");
        if (booking.getVehicleReceivedAt() != null) return;
        var now = clock.instant();
        var today = now.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        if (booking.getStatus() != BookingStatus.CONFIRMED || booking.getOwnerAcceptedAt() == null
                || booking.getBeforeAcceptedAt() == null || booking.getAdvancePaidAt() == null
                || today.isBefore(booking.getStartDate()) || !today.isBefore(booking.getEndDate())) {
            throw new BusinessException("Chỉ được giao xe trong khoảng ngày thuê, sau khi đồng ý BEFORE và nhận đủ trả trước.");
        }
        if (!bookings.findOutstandingCustodyForUpdate(booking.getCar().getId(), id).isEmpty()) {
            throw new BusinessException("Xe còn ở đơn thuê khác chưa xác nhận nhận lại. Vui lòng kiểm tra trước khi giao xe.");
        }
        var advance = obligations.findByBusinessKeyForUpdate("BOOKING:" + id + ":ADVANCE").orElse(null);
        if (advance != null) {
            entityManager.detach(advance);
            advance = obligations.findByIdForUpdate(advance.getId()).orElseThrow();
        }
        if (advance == null || advance.getPurpose() != PaymentPurpose.ADVANCE
                || advance.getSubjectType() != PaymentSubjectType.BOOKING || !id.equals(advance.getSubjectId())
                || advance.getStatus() != PaymentObligationStatus.SETTLED
                || !advance.getAmountDue().equals(advance.getAmountSettled())
                || !advance.getAmountDue().equals(booking.getAdvanceAmount())
                || advance.getAmountDue() != RentalPolicy.advance(booking.getTotalAmount())
                || !Long.valueOf(RentalPolicy.balance(booking.getTotalAmount())).equals(booking.getBalanceAmount())
                || !advance.getPayer().getId().equals(booking.getCustomer().getId())
                || !advance.getCollector().getId().equals(owner.getId())) {
            throw new BusinessException("Khoản trả trước chưa được thanh toán đủ hoặc không khớp đơn thuê.");
        }
        inspections.confirmPickupLocked(booking, owner, now);
        booking.setContractConfirmedBy(owner);
        booking.setContractConfirmedAt(now);
        booking.setVehicleReceivedAt(now);
        notifications.notify(booking.getCustomer().getId(), "INSPECTION_PICKUP_CONFIRMED", "Chủ xe đã xác nhận giao xe",
                "Chủ xe đã xác nhận hợp đồng và giao xe cho đơn #CR-" + id + ". Chuyến thuê đang diễn ra.", "BOOKING", id);
    }

    @Transactional
    public void confirmReturn(Long id, ReturnReceiptForm form, CustomUserPrincipal principal) {
        User owner = requireOwner(principal);
        if (form == null || !validator.validate(form).isEmpty()) throw new BusinessException("Thông tin nhận lại xe không hợp lệ.");
        if (form.getDecision() == ReturnReceiptForm.Decision.RECEIVED_DISPUTED
                && (form.getNote() == null || form.getNote().isBlank())) throw new BusinessException("Vui lòng nhập ghi chú bất đồng.");
        Booking booking = lockedBooking(id);
        entityManager.refresh(owner, jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        if (owner.getRole() != Role.OWNER || owner.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Tài khoản không được phép nhận lại xe.");
        }
        if (!booking.getCar().getOwner().getId().equals(owner.getId())) throw new NotFoundException("Đơn thuê không tồn tại.");
        requireReturnWorkflow(booking);
        var now = clock.instant();
        boolean received = form.getDecision() != ReturnReceiptForm.Decision.NOT_RECEIVED;
        PaymentObligation balance = null;
        if (received) {
            if (!Long.valueOf(RentalPolicy.balance(booking.getTotalAmount())).equals(booking.getBalanceAmount())
                    || !Long.valueOf(RentalPolicy.advance(booking.getTotalAmount())).equals(booking.getAdvanceAmount())) {
                throw new BusinessException("Số tiền thanh toán không khớp snapshot đơn thuê.");
            }
            balance = obligations.findByBusinessKeyForUpdate("BOOKING:" + id + ":BALANCE").orElse(null);
            if (balance == null) {
                if (booking.getVehicleReturnedAt() != null) throw new BusinessException("Thiếu nghĩa vụ thanh toán của xe đã nhận lại.");
                balance = obligations.saveAndFlush(PaymentObligation.builder().businessKey("BOOKING:" + id + ":BALANCE")
                        .purpose(PaymentPurpose.BALANCE).subjectType(PaymentSubjectType.BOOKING).subjectId(id)
                        .payer(booking.getCustomer()).collector(owner).amountDue(booking.getBalanceAmount())
                        .dueAt(now.plusSeconds(3600)).createdAt(now).updatedAt(now).build());
            } else {
                entityManager.detach(balance);
                balance = obligations.findByIdForUpdate(balance.getId()).orElseThrow();
                if (booking.getVehicleReturnedAt() == null || balance.getPurpose() != PaymentPurpose.BALANCE
                        || balance.getSubjectType() != PaymentSubjectType.BOOKING || !id.equals(balance.getSubjectId())
                        || !balance.getPayer().getId().equals(booking.getCustomer().getId())
                        || !balance.getCollector().getId().equals(owner.getId())
                        || !balance.getAmountDue().equals(booking.getBalanceAmount())
                        || !booking.getVehicleReturnedAt().plusSeconds(3600).equals(balance.getDueAt())) {
                    throw new BusinessException("Nghĩa vụ thanh toán còn lại không khớp đơn thuê.");
                }
            }
        }
        // The report is locked after BALANCE; invalid evidence rolls its creation back.
        if (!inspections.confirmReturnLocked(booking, owner, form, now)) return;
        if (received) booking.setVehicleReturnedAt(now);
        notifications.notify(booking.getCustomer().getId(), received ? "INSPECTION_RETURN_CONFIRMED" : "INSPECTION_RETURN_NOT_RECEIVED",
                received ? "Chủ xe đã nhận lại xe" : "Chủ xe chưa nhận lại xe",
                received ? "Đơn #CR-" + id + " đã nhận lại xe thực tế. Còn " + balance.getAmountDue()
                        + " VNĐ phải thanh toán trong một giờ; xem chi tiết đơn thuê."
                        : "Chủ xe xác nhận chưa nhận lại xe thực tế của đơn #CR-" + id + ". Vui lòng liên hệ chủ xe.", "BOOKING", id);
    }

    @Transactional
    public void remindAfterReturn(Long id) {
        Booking booking = lockedBooking(id);
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()
                || booking.getStatus() != BookingStatus.CONFIRMED || booking.getVehicleReceivedAt() == null
                || booking.getVehicleReturnedAt() != null || booking.getAfterReminderSentAt() != null) return;
        inspections.remindAfterReturnLocked(booking, clock.instant());
    }

    private void requireReturnWorkflow(Booking booking) {
        if (!"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()
                || booking.getStatus() != BookingStatus.CONFIRMED || booking.getVehicleReceivedAt() == null
                || clock.instant().isBefore(booking.getVehicleReceivedAt())) {
            throw new BusinessException("Chỉ được nhận lại xe thuộc đơn đang thuê, đã giao xe thực tế.");
        }
    }

    @Transactional
    public void completeBooking(Long id, CustomUserPrincipal principal) {
        User owner = requireOwner(principal);
        Booking booking = lockedBooking(id);
        if (!booking.getCar().getOwner().getId().equals(owner.getId())) {
            throw new NotFoundException("Đơn thuê không tồn tại.");
        }
        if ("VIETQR_V1".equals(booking.getWorkflowVersion())) {
            throw new BusinessException("Đơn thuê sẽ tự hoàn tất sau khi nhận lại xe và thanh toán đủ tiền.");
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
        if ("VIETQR_V1".equals(booking.getWorkflowVersion()) && booking.getBeforeAcceptedAt() != null) {
            throw new BusinessException("Không thể từ chối đơn đã đồng ý BEFORE và mở trả trước.");
        }
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
        entityManager.refresh(owner);
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
