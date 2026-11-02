package com.carrent.inspection;

import com.carrent.booking.*;
import com.carrent.car.CarRepository;
import com.carrent.common.exception.*;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.*;
import com.carrent.notification.NotificationService;
import java.time.Clock;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import com.carrent.storage.InspectionStorageService;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service @RequiredArgsConstructor
public class InspectionService {
    private static final Logger log = LoggerFactory.getLogger(InspectionService.class);
    private final BookingRepository bookings;
    private final CarRepository cars;
    private final UserRepository users;
    private final BookingInspectionRepository reports;
    private final InspectionImageRepository images;
    private final EntityManager em;
    private final Validator validator;
    private final InspectionStorageService storage;
    private final InspectionEventRepository events;
    private final NotificationService notifications;
    private final Clock clock;

    /** Caller already holds Car, Booking and financial locks, in that order. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void acceptBeforeLocked(Booking booking, User customer) {
        if (em.getLockMode(booking) != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
                || !booking.getCustomer().getId().equals(customer.getId())) throw missing();
        var report = lockedReport(booking.getId(), InspectionPhase.BEFORE);
        if (report == null || report.getSubmittedAt() == null
                || !report.getAuthor().getId().equals(booking.getCar().getOwner().getId())
                || (report.getStatus() != InspectionStatus.SUBMITTED && report.getStatus() != InspectionStatus.DISPUTED)) {
            throw new BusinessException("Chỉ được đồng ý biên bản BEFORE hợp lệ đã gửi.");
        }
        var form = new InspectionForm();
        form.setConditionNote(report.getConditionNote()); form.setVisibleDamage(report.getVisibleDamage());
        form.setOdometerKm(report.getOdometerKm()); form.setEnergyPercent(report.getEnergyPercent());
        form.setAccessoriesNote(report.getAccessoriesNote());
        var violations = validator.validate(form, InspectionForm.Submitted.class);
        int count = lockedImages(report.getId()).size();
        if (!violations.isEmpty() || count < 4 || count > 10) throw new BusinessException("Biên bản cần đủ năm trường và 4–10 ảnh.");
        report.setStatus(InspectionStatus.ACCEPTED);
        events.save(InspectionEvent.builder().inspection(report).actor(customer)
                .action(InspectionEventAction.BEFORE_ACCEPTED).createdAt(clock.instant()).build());
    }

    @Transactional
    public void submitBefore(Long bookingId, CustomUserPrincipal principal) {
        User actor = actor(principal);
        Booking booking = lockedBooking(bookingId, InspectionPhase.BEFORE);
        requireAuthor(booking, InspectionPhase.BEFORE, actor);
        requireBeforeOpen(booking);
        var report = lockedReport(bookingId, InspectionPhase.BEFORE);
        if (report == null || !report.getAuthor().getId().equals(actor.getId())) throw missing();
        if (report.getStatus() == InspectionStatus.SUBMITTED || report.getStatus() == InspectionStatus.DISPUTED) return;
        if (report.getStatus() != InspectionStatus.DRAFT) throw new BusinessException("Biên bản đã được đồng ý.");
        var violations = validator.validate(InspectionForm.from(view(booking, InspectionPhase.BEFORE, report, false, false, false, false)), InspectionForm.Submitted.class);
        if (!violations.isEmpty()) throw new BusinessException(violations.iterator().next().getMessage());
        int count = lockedImages(report.getId()).size();
        if (count < 4 || count > 10) throw new BusinessException("Cần từ 4 đến 10 ảnh trước khi gửi biên bản.");
        report.setStatus(InspectionStatus.SUBMITTED);
        report.setSubmittedAt(clock.instant());
        events.save(InspectionEvent.builder().inspection(report).actor(actor).action(InspectionEventAction.SUBMITTED)
                .createdAt(clock.instant()).build());
        notifications.notify(booking.getCustomer().getId(), "INSPECTION_SUBMITTED", "Biên bản trước thuê đã được gửi",
                "Chủ xe đã gửi biên bản trước thuê cho đơn #CR-" + bookingId + ". Vui lòng xem bằng chứng.", "BOOKING", bookingId);
    }

    @Transactional
    public void submitAfter(Long bookingId, CustomUserPrincipal principal) {
        User customer = actor(principal);
        Booking booking = lockedBooking(bookingId, InspectionPhase.AFTER);
        // Check current account state after aggregate locks, even in an older repeatable-read snapshot.
        em.refresh(customer, jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        if (customer.getStatus() != UserStatus.ACTIVE || customer.getRole() != principal.getRole()) {
            throw new ForbiddenException("Tài khoản không được phép gửi biên bản.");
        }
        requireAuthor(booking, InspectionPhase.AFTER, customer);
        if (booking.getStatus() != BookingStatus.CONFIRMED || !writable(booking, InspectionPhase.AFTER)
                || clock.instant().isBefore(booking.getVehicleReceivedAt())) {
            throw new BusinessException("Chỉ được gửi AFTER sau khi đã nhận xe và trước khi chủ xe nhận lại.");
        }
        var report = lockedReport(bookingId, InspectionPhase.AFTER);
        if (report == null || !report.getAuthor().getId().equals(customer.getId())) throw missing();
        if (report.getStatus() == InspectionStatus.SUBMITTED) return;
        if (report.getStatus() != InspectionStatus.DRAFT) throw new BusinessException("Biên bản đã được phản hồi.");
        validateEvidence(booking, report);
        var at = clock.instant();
        report.setStatus(InspectionStatus.SUBMITTED); report.setSubmittedAt(at);
        events.save(InspectionEvent.builder().inspection(report).actor(customer)
                .action(InspectionEventAction.SUBMITTED).createdAt(at).build());
        notifications.notify(booking.getCar().getOwner().getId(), "INSPECTION_AFTER_SUBMITTED", "Khách hàng đã gửi biên bản sau thuê",
                "Đơn #CR-" + bookingId + " đang chờ chủ xe xác nhận đã nhận lại xe thực tế.", "BOOKING", bookingId);
    }

    /** BALANCE must be locked/created before entering this method for a physical return. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean confirmReturnLocked(Booking booking, User owner, ReturnReceiptForm form, java.time.Instant at) {
        requireCoreLocks(booking);
        if (!booking.getCar().getOwner().getId().equals(owner.getId())) throw missing();
        var report = submittedAfterLocked(booking);
        if (report == null || at.isBefore(report.getSubmittedAt())) {
            throw new BusinessException("Cần biên bản AFTER hợp lệ đã được khách hàng gửi.");
        }
        validateEvidence(booking, report);
        var decision = form.getDecision();
        if (booking.getVehicleReturnedAt() != null) {
            var expected = decision == ReturnReceiptForm.Decision.RECEIVED_ACCEPTED ? InspectionStatus.ACCEPTED
                    : decision == ReturnReceiptForm.Decision.RECEIVED_DISPUTED ? InspectionStatus.DISPUTED : null;
            if (report.getStatus() != expected) throw new BusinessException("Xe đã được xác nhận nhận lại; không thể thay đổi quyết định.");
            return false;
        }
        if (report.getStatus() != InspectionStatus.SUBMITTED) throw new BusinessException("Biên bản AFTER không còn chờ nhận lại xe.");
        String note = trim(form.getNote());
        InspectionEventAction action;
        if (decision == ReturnReceiptForm.Decision.NOT_RECEIVED) {
            action = InspectionEventAction.RETURN_NOT_RECEIVED;
            if (note == null || note.isBlank()) note = "Chủ xe xác nhận chưa nhận lại xe thực tế.";
            var history = events.findLatestForUpdate(report.getId());
            if (!history.isEmpty()) {
                var last = history.getFirst();
                if (action.name().equals(last[0]) && owner.getId().longValue() == ((Number) last[1]).longValue()
                        && note.equals(last[2])) return false;
            }
        } else {
            boolean disputed = decision == ReturnReceiptForm.Decision.RECEIVED_DISPUTED;
            report.setStatus(disputed ? InspectionStatus.DISPUTED : InspectionStatus.ACCEPTED);
            action = disputed ? InspectionEventAction.DISPUTED : InspectionEventAction.RETURN_CONFIRMED;
        }
        events.save(InspectionEvent.builder().inspection(report).actor(owner).action(action).note(note).createdAt(at).build());
        return true;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void remindAfterReturnLocked(Booking booking, java.time.Instant at) {
        requireCoreLocks(booking);
        var report = submittedAfterLocked(booking);
        if (report == null || report.getStatus() != InspectionStatus.SUBMITTED
                || at.isBefore(report.getSubmittedAt().plusSeconds(3600))) return;
        booking.setAfterReminderSentAt(at);
        notifications.notify(booking.getCar().getOwner().getId(), "INSPECTION_AFTER_RETURN_OVERDUE", "Cần xác nhận nhận lại xe",
                "AFTER của đơn #CR-" + booking.getId() + " đã gửi hơn một giờ. Vui lòng kiểm tra nhận lại xe thực tế.", "BOOKING", booking.getId());
        for (var admin : users.findByRole(Role.ADMIN)) {
            notifications.notify(admin.getId(), "INSPECTION_AFTER_RETURN_OVERDUE", "Đơn thuê chờ xác nhận nhận lại xe",
                    "Đơn #CR-" + booking.getId() + " chưa được chủ xe xác nhận nhận lại sau một giờ kể từ khi gửi AFTER.", "BOOKING", booking.getId());
        }
    }

    private BookingInspection submittedAfterLocked(Booking booking) {
        var report = lockedReport(booking.getId(), InspectionPhase.AFTER);
        return report != null && report.getStatus() != InspectionStatus.DRAFT && report.getSubmittedAt() != null
                && report.getAuthor().getId().equals(booking.getCustomer().getId())
                && !report.getSubmittedAt().isBefore(booking.getVehicleReceivedAt()) ? report : null;
    }

    private void requireCoreLocks(Booking booking) {
        if (!writeLocked(booking) || !writeLocked(booking.getCar())) throw new IllegalStateException("Lock Car and Booking first");
    }
    private boolean writeLocked(Object entity) {
        var mode = em.getLockMode(entity);
        return mode == jakarta.persistence.LockModeType.PESSIMISTIC_WRITE || mode == jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT;
    }
    private void validateEvidence(Booking booking, BookingInspection report) {
        var form = InspectionForm.from(view(booking, report.getPhase(), report, false, false, false, false));
        int count = lockedImages(report.getId()).size();
        if (!validator.validate(form, InspectionForm.Submitted.class).isEmpty() || count < 4 || count > 10) {
            throw new BusinessException("Biên bản cần đủ năm trường và 4–10 ảnh.");
        }
    }

    /** Caller holds current Car and Booking locks, then any financial locks. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void confirmPickupLocked(Booking booking, User owner, java.time.Instant at) {
        if (em.getLockMode(booking) != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
                || em.getLockMode(booking.getCar()) != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
                || !booking.getCar().getOwner().getId().equals(owner.getId())) throw missing();
        var report = lockedReport(booking.getId(), InspectionPhase.BEFORE);
        if (report == null || report.getStatus() != InspectionStatus.ACCEPTED || report.getSubmittedAt() == null
                || !report.getAuthor().getId().equals(owner.getId())) {
            throw new BusinessException("BEFORE phải được khách hàng đồng ý trước khi giao xe.");
        }
        events.save(InspectionEvent.builder().inspection(report).actor(owner)
                .action(InspectionEventAction.PICKUP_CONFIRMED).createdAt(at).build());
    }

    private void requireBeforeOpen(Booking b) {
        if (!beforeOpen(b)) {
            throw new BusinessException("Đơn thuê chưa được chủ xe chấp nhận hoặc không còn cho phép gửi/phản hồi biên bản trước thuê.");
        }
    }

    private boolean beforeOpen(Booking b) {
        return b.getStatus() == BookingStatus.PENDING && b.getOwnerAcceptedAt() != null
                && b.getBeforeAcceptedAt() == null && b.getVehicleReceivedAt() == null;
    }

    @Transactional
    public void appendBeforeAddendum(Long bookingId, String note, CustomUserPrincipal principal) {
        appendBeforeEvent(bookingId, note, principal, InspectionEventAction.ADDENDUM);
    }

    @Transactional
    public void disputeBefore(Long bookingId, String note, CustomUserPrincipal principal) {
        appendBeforeEvent(bookingId, note, principal, InspectionEventAction.DISPUTED);
    }

    private void appendBeforeEvent(Long bookingId, String note, CustomUserPrincipal principal, InspectionEventAction action) {
        User actor = actor(principal);
        Booking booking = lockedBooking(bookingId, InspectionPhase.BEFORE);
        requireAuthor(booking, action == InspectionEventAction.ADDENDUM ? InspectionPhase.BEFORE : InspectionPhase.AFTER, actor);
        requireBeforeOpen(booking);
        var report = lockedReport(bookingId, InspectionPhase.BEFORE);
        if (report == null) throw missing();
        if (report.getStatus() != InspectionStatus.SUBMITTED && report.getStatus() != InspectionStatus.DISPUTED) {
            throw new BusinessException("Chỉ được phản hồi biên bản đã gửi và chưa được đồng ý.");
        }
        String text = trim(note);
        if (text == null || text.isBlank() || text.length() > 2000) {
            throw new BusinessException("Vui lòng nhập ghi chú từ 1 đến 2000 ký tự.");
        }
        events.save(InspectionEvent.builder().inspection(report).actor(actor).action(action).note(text).createdAt(clock.instant()).build());
        boolean dispute = action == InspectionEventAction.DISPUTED;
        if (dispute) report.setStatus(InspectionStatus.DISPUTED);
        notifications.notify(dispute ? booking.getCar().getOwner().getId() : booking.getCustomer().getId(),
                dispute ? "INSPECTION_DISPUTED" : "INSPECTION_ADDENDUM",
                dispute ? "Biên bản trước thuê có bất đồng" : "Biên bản trước thuê có ghi chú bổ sung",
                "Có phản hồi mới cho biên bản trước thuê của đơn #CR-" + bookingId + ". Vui lòng xem lịch sử biên bản.", "BOOKING", bookingId);
    }

    @Transactional(readOnly=true)
    public InspectionView getView(Long bookingId, InspectionPhase phase, CustomUserPrincipal principal) {
        User actor = actor(principal);
        Booking booking = supported(bookingId, phase);
        var report = reports.findByBookingIdAndPhase(bookingId, phase).orElse(null);
        if (report == null || report.getStatus() == InspectionStatus.DRAFT) {
            requireAuthor(booking, phase, actor);
            if (report != null && !report.getAuthor().getId().equals(actor.getId())) throw missing();
        } else if (!member(booking, actor) && actor.getRole() != Role.ADMIN) throw missing();
        boolean editable = author(booking, phase, actor) && writable(booking, phase)
                && (report == null || report.getStatus() == InspectionStatus.DRAFT);
        boolean open = phase == InspectionPhase.BEFORE && beforeOpen(booking);
        boolean afterOpen = phase == InspectionPhase.AFTER && booking.getStatus() == BookingStatus.CONFIRMED && writable(booking, phase);
        boolean submitted = report != null && (report.getStatus() == InspectionStatus.SUBMITTED || report.getStatus() == InspectionStatus.DISPUTED);
        return view(booking, phase, report, editable, (open || afterOpen) && editable && report != null,
                open && submitted && author(booking, InspectionPhase.BEFORE, actor),
                open && submitted && author(booking, InspectionPhase.AFTER, actor));
    }

    @Transactional
    public void saveDraft(Long bookingId, InspectionPhase phase, InspectionForm form, CustomUserPrincipal principal) {
        User actor = actor(principal);
        Booking booking = lockedBooking(bookingId, phase);
        requireAuthor(booking, phase, actor);
        if (!writable(booking, phase)) throw new BusinessException("Đơn thuê không còn cho phép sửa nháp biên bản.");
        if (form == null) throw new BusinessException("Dữ liệu biên bản không hợp lệ.");
        var violations = validator.validate(form);
        if (!violations.isEmpty()) throw new BusinessException(violations.iterator().next().getMessage());
        var report = lockedReport(bookingId, phase);
        if (report == null) report = BookingInspection.builder().booking(booking).phase(phase).author(actor).build();
        if (report.getStatus() != InspectionStatus.DRAFT || !report.getAuthor().getId().equals(actor.getId())) {
            throw new BusinessException("Biên bản đã gửi không được thay đổi.");
        }
        report.setConditionNote(trim(form.getConditionNote())); report.setVisibleDamage(trim(form.getVisibleDamage()));
        report.setOdometerKm(form.getOdometerKm()); report.setEnergyPercent(form.getEnergyPercent());
        report.setAccessoriesNote(trim(form.getAccessoriesNote()));
        reports.save(report);
    }

    @Transactional
    public void addImages(Long bookingId, InspectionPhase phase, List<MultipartFile> files, CustomUserPrincipal principal) {
        User actor = actor(principal);
        var report = mutableReport(lockedBooking(bookingId, phase), phase, actor);
        if (files == null || files.isEmpty() || files.size() > 4) {
            throw new BusinessException("Mỗi lần tải lên từ 1 đến 4 ảnh.");
        }
        if (lockedImages(report.getId()).size() + files.size() > 10) {
            throw new BusinessException("Mỗi biên bản có tối đa 10 ảnh.");
        }
        for (var file : files) {
            var stored = storage.store(file);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) cleanup(stored.storageKey());
                }
            });
            images.save(InspectionImage.builder().inspection(report).storageKey(stored.storageKey())
                    .mediaType(stored.mediaType()).sizeBytes(stored.sizeBytes()).build());
        }
    }

    @Transactional
    public void deleteDraftImage(Long bookingId, InspectionPhase phase, Long imageId, CustomUserPrincipal principal) {
        User actor = actor(principal);
        var report = mutableReport(lockedBooking(bookingId, phase), phase, actor);
        var image = lockedImages(report.getId()).stream().filter(i -> i.getId().equals(imageId))
                .findFirst().orElseThrow(this::missing);
        String key = image.getStorageKey();
        images.delete(image);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cleanup(key); }
        });
    }

    public record ImageContent(Resource resource, String mediaType, int sizeBytes) {}

    @Transactional(readOnly=true)
    public ImageContent readImage(Long bookingId, InspectionPhase phase, Long imageId, CustomUserPrincipal actor) {
        var view = getView(bookingId, phase, actor);
        if (view.reportId() == null || imageId == null) throw missing();
        var image = images.findByInspectionIdAndId(view.reportId(), imageId).orElseThrow(this::missing);
        return new ImageContent(storage.read(image.getStorageKey()), image.getMediaType(), image.getSizeBytes());
    }

    private BookingInspection mutableReport(Booking booking, InspectionPhase phase, User actor) {
        requireAuthor(booking, phase, actor);
        if (!writable(booking, phase)) throw new BusinessException("Đơn thuê không còn cho phép sửa nháp biên bản.");
        var report = lockedReport(booking.getId(), phase);
        if (report == null) throw missing();
        if (report.getStatus() != InspectionStatus.DRAFT || !report.getAuthor().getId().equals(actor.getId())) {
            throw new BusinessException("Biên bản đã gửi không được thay đổi.");
        }
        return report;
    }

    private List<InspectionImage> lockedImages(Long reportId) {
        images.findForUpdate(reportId).forEach(em::detach);
        return images.findForUpdate(reportId);
    }

    private void cleanup(String key) {
        try { storage.delete(key); }
        catch (RuntimeException failure) { log.error("Could not clean up private inspection image {}", key); }
    }

    private BookingInspection lockedReport(Long bookingId, InspectionPhase phase) {
        reports.findForUpdate(bookingId, phase).ifPresent(em::detach);
        return reports.findForUpdate(bookingId, phase).orElse(null);
    }

    private Booking lockedBooking(Long id, InspectionPhase phase) {
        Booking route = supported(id, phase);
        var car = cars.findByIdForUpdate(route.getCar().getId()).orElseThrow(this::missing);
        em.detach(car);
        cars.findByIdForUpdate(route.getCar().getId()).orElseThrow(this::missing);
        var booking = bookings.findByIdForUpdate(id).orElseThrow(this::missing);
        em.detach(booking);
        return checkSupported(bookings.findByIdForUpdate(id).orElseThrow(this::missing), phase);
    }

    private Booking supported(Long id, InspectionPhase phase) {
        if (id == null || phase == null) throw missing();
        return checkSupported(bookings.findById(id).orElseThrow(this::missing), phase);
    }
    private Booking checkSupported(Booking booking, InspectionPhase phase) {
        if (phase == null || !"VIETQR_V1".equals(booking.getWorkflowVersion()) || !booking.isInspectionRequired()) throw missing();
        return booking;
    }
    private User actor(CustomUserPrincipal principal) {
        if (principal == null || principal.getId() == null) throw new ForbiddenException("Vui lòng đăng nhập.");
        var user = users.findById(principal.getId()).orElseThrow(this::missing);
        em.refresh(user);
        if (user.getStatus() != UserStatus.ACTIVE || user.getRole() != principal.getRole()) {
            throw new ForbiddenException("Tài khoản không được phép truy cập biên bản.");
        }
        return user;
    }
    private boolean author(Booking b, InspectionPhase phase, User actor) {
        return phase == InspectionPhase.BEFORE
                ? actor.getRole() == Role.OWNER && b.getCar().getOwner().getId().equals(actor.getId())
                : actor.getRole() == Role.CUSTOMER && b.getCustomer().getId().equals(actor.getId());
    }
    private boolean member(Booking b, User actor) {
        return author(b, InspectionPhase.BEFORE, actor) || author(b, InspectionPhase.AFTER, actor);
    }
    private void requireAuthor(Booking b, InspectionPhase phase, User actor) {
        if (!author(b, phase, actor)) throw missing();
    }
    private boolean writable(Booking b, InspectionPhase phase) {
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CONFIRMED) return false;
        return phase == InspectionPhase.BEFORE ? b.getVehicleReceivedAt() == null
                : b.getVehicleReceivedAt() != null && b.getVehicleReturnedAt() == null;
    }
    private InspectionView view(Booking b, InspectionPhase phase, BookingInspection r, boolean editable,
                                boolean canSubmit, boolean canAddendum, boolean canDispute) {
        List<InspectionView.Image> photos = r == null ? List.of() : images.findByInspectionIdOrderByIdAsc(r.getId())
                .stream().map(i -> new InspectionView.Image(i.getId())).toList();
        return new InspectionView(b.getId(), b.getCar().getId(), b.getCar().getBrand()+" "+b.getCar().getModel(),
                b.getPickupAddress(), phase, r == null ? null : r.getId(), r == null ? InspectionStatus.DRAFT : r.getStatus(),
                r == null ? null : r.getConditionNote(), r == null ? null : r.getVisibleDamage(),
                r == null ? null : r.getOdometerKm(), r == null ? null : r.getEnergyPercent(),
                r == null ? null : r.getAccessoriesNote(), photos, editable, r == null ? null : r.getSubmittedAt(),
                r == null ? List.of() : events.findByInspectionIdOrderByCreatedAtAscIdAsc(r.getId()).stream()
                        .map(e -> new InspectionView.Event(e.getId(), e.getActor().getId(), e.getActor().getFullName(),
                                e.getAction(), e.getNote(), e.getCreatedAt())).toList(), canSubmit, canAddendum, canDispute);
    }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private NotFoundException missing() { return new NotFoundException("Biên bản không tồn tại."); }
}
