package com.carrent.inspection;

import com.carrent.booking.*;
import com.carrent.car.CarRepository;
import com.carrent.common.exception.*;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.*;
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

    @Transactional(readOnly=true)
    public InspectionView getView(Long bookingId, InspectionPhase phase, CustomUserPrincipal principal) {
        User actor = actor(principal);
        Booking booking = supported(bookingId, phase);
        var report = reports.findByBookingIdAndPhase(bookingId, phase).orElse(null);
        if (report == null || report.getStatus() == InspectionStatus.DRAFT) {
            requireAuthor(booking, phase, actor);
            if (report != null && !report.getAuthor().getId().equals(actor.getId())) throw missing();
        } else if (!member(booking, actor) && actor.getRole() != Role.ADMIN) throw missing();
        return view(booking, phase, report, author(booking, phase, actor) && writable(booking, phase)
                && (report == null || report.getStatus() == InspectionStatus.DRAFT));
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
    private InspectionView view(Booking b, InspectionPhase phase, BookingInspection r, boolean editable) {
        List<InspectionView.Image> photos = r == null ? List.of() : images.findByInspectionIdOrderByIdAsc(r.getId())
                .stream().map(i -> new InspectionView.Image(i.getId())).toList();
        return new InspectionView(b.getId(), b.getCar().getId(), b.getCar().getBrand()+" "+b.getCar().getModel(),
                b.getPickupAddress(), phase, r == null ? null : r.getId(), r == null ? InspectionStatus.DRAFT : r.getStatus(),
                r == null ? null : r.getConditionNote(), r == null ? null : r.getVisibleDamage(),
                r == null ? null : r.getOdometerKm(), r == null ? null : r.getEnergyPercent(),
                r == null ? null : r.getAccessoriesNote(), photos, editable);
    }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private NotFoundException missing() { return new NotFoundException("Biên bản không tồn tại."); }
}
