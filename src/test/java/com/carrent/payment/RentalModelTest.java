package com.carrent.payment;

import com.carrent.booking.BookingRepository;
import com.carrent.booking.BookingStatus;
import com.carrent.car.CarRepository;
import com.carrent.car.CarSpecifications;
import com.carrent.inspection.BookingInspection;
import com.carrent.inspection.BookingInspectionRepository;
import com.carrent.inspection.InspectionEvent;
import com.carrent.inspection.InspectionEventAction;
import com.carrent.inspection.InspectionEventRepository;
import com.carrent.inspection.InspectionImage;
import com.carrent.inspection.InspectionImageRepository;
import com.carrent.inspection.InspectionPhase;
import com.carrent.inspection.InspectionStatus;
import com.carrent.user.UserRepository;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RentalModelTest {
    @Test
    void repositoriesPreserveRentalSnapshotsUtcInstantsAndAppendOnlyEvents() throws Exception {
        try (var db = RentalLifecycleTest.migratedDatabase()) {
            TimeZone original = TimeZone.getDefault();
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            try (var app = boot(db)) {
                var bookings = app.getBean(BookingRepository.class);
                var users = app.getBean(UserRepository.class);
                var reports = app.getBean(BookingInspectionRepository.class);
                var images = app.getBean(InspectionImageRepository.class);
                var events = app.getBean(InspectionEventRepository.class);
                var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
                Instant at = Instant.parse("2026-10-30T03:04:05.123456Z");
                Long reportId = tx.execute(status -> {
                    var booking = bookings.findById(1L).orElseThrow();
                    assertThat(booking.getWorkflowVersion()).isEqualTo("LEGACY");
                    assertThat(booking.isInspectionRequired()).isFalse();
                    assertThat(booking.getVehicleReceivedAt()).isNull();
                    booking.setWorkflowVersion("VIETQR_V1");
                    booking.setInspectionRequired(true);
                    booking.setOwnerAcceptedAt(at.minusSeconds(1200));
                    booking.setBeforeAcceptedAt(at.minusSeconds(1000));
                    booking.setHoldExpiresAt(at.minusSeconds(100));
                    booking.setAdvanceAmount(540000L);
                    booking.setBalanceAmount(1260000L);
                    booking.setAdvancePaidAt(at.minusSeconds(500));
                    booking.setContractConfirmedBy(users.findById(2L).orElseThrow());
                    booking.setContractConfirmedAt(at);
                    booking.setVehicleReceivedAt(at);
                    booking.setVehicleReturnedAt(at.plusSeconds(86400));
                    booking.setAfterReminderSentAt(at.plusSeconds(86300));
                    booking.setBalanceOverdueNotifiedAt(at.plusSeconds(90000));
                    var cancelled = bookings.findById(2L).orElseThrow();
                    cancelled.setCancelledAt(at);
                    cancelled.setCancellationFee(54000L);
                    var report = reports.saveAndFlush(BookingInspection.builder().booking(booking)
                            .phase(InspectionPhase.BEFORE).author(booking.getCar().getOwner())
                            .status(InspectionStatus.SUBMITTED).conditionNote("Xe nguyên trạng")
                            .visibleDamage("Không phát hiện").odometerKm(9999999).energyPercent(100)
                            .accessoriesNote("Đủ phụ kiện").submittedAt(at).createdAt(at).updatedAt(at).build());
                    images.saveAndFlush(InspectionImage.builder().inspection(report)
                            .storageKey("04b9af27-f55a-4dbd-9765-7b0303d1eacc.png")
                            .mediaType("image/png").sizeBytes(5242880).createdAt(at).build());
                    events.save(InspectionEvent.builder().inspection(report).actor(report.getAuthor())
                            .action(InspectionEventAction.SUBMITTED).createdAt(at).build());
                    events.save(InspectionEvent.builder().inspection(report).actor(report.getAuthor())
                            .action(InspectionEventAction.ADDENDUM).note("Ghi chú gốc").createdAt(at.plusSeconds(1)).build());
                    reports.saveAndFlush(BookingInspection.builder().booking(booking).phase(InspectionPhase.AFTER)
                            .author(booking.getCustomer()).build());
                    return report.getId();
                });
                tx.executeWithoutResult(status -> {
                    var booking = bookings.findById(1L).orElseThrow();
                    assertThat(booking.getVehicleReceivedAt()).isEqualTo(at);
                    assertThat(booking.getVehicleReturnedAt()).isEqualTo(at.plusSeconds(86400));
                    assertThat(booking.getContractConfirmedAt()).isEqualTo(at);
                    assertThat(booking.getContractConfirmedBy().getId()).isEqualTo(2L);
                    assertThat(booking.getOwnerAcceptedAt()).isEqualTo(at.minusSeconds(1200));
                    assertThat(booking.getBeforeAcceptedAt()).isEqualTo(at.minusSeconds(1000));
                    assertThat(booking.getHoldExpiresAt()).isEqualTo(at.minusSeconds(100));
                    assertThat(booking.getAdvancePaidAt()).isEqualTo(at.minusSeconds(500));
                    assertThat(booking.getAdvanceAmount()).isEqualTo(540000L);
                    assertThat(booking.getBalanceAmount()).isEqualTo(1260000L);
                    assertThat(booking.getAfterReminderSentAt()).isEqualTo(at.plusSeconds(86300));
                    assertThat(booking.getBalanceOverdueNotifiedAt()).isEqualTo(at.plusSeconds(90000));
                    assertThat(bookings.findById(2L).orElseThrow().getCancelledAt()).isEqualTo(at);
                    assertThat(bookings.findById(2L).orElseThrow().getCancellationFee()).isEqualTo(54000L);
                    var report = reports.findByBookingIdAndPhase(1L, InspectionPhase.BEFORE).orElseThrow();
                    assertThat(report.getId()).isEqualTo(reportId);
                    assertThat(report.getConditionNote()).isEqualTo("Xe nguyên trạng");
                    assertThat(report.getVisibleDamage()).isEqualTo("Không phát hiện");
                    assertThat(report.getAccessoriesNote()).isEqualTo("Đủ phụ kiện");
                    assertThat(report.getOdometerKm()).isEqualTo(9999999);
                    assertThat(report.getEnergyPercent()).isEqualTo(100);
                    assertThat(report.getSubmittedAt()).isEqualTo(at);
                    assertThat(report.getCreatedAt()).isEqualTo(at);
                    assertThat(report.getUpdatedAt()).isEqualTo(at);
                    assertThat(reports.findByBookingIdOrderByPhaseAsc(1L)).hasSize(2);
                    var image = images.findByInspectionIdOrderByIdAsc(reportId).getFirst();
                    assertThat(image.getStorageKey()).isEqualTo("04b9af27-f55a-4dbd-9765-7b0303d1eacc.png");
                    assertThat(image.getSizeBytes()).isEqualTo(5242880);
                    assertThat(image.getCreatedAt()).isEqualTo(at);
                    assertThat(images.findByInspectionIdAndId(reportId + 1, image.getId())).isEmpty();
                    var history = events.findByInspectionIdOrderByCreatedAtAscIdAsc(reportId);
                    assertThat(history).extracting(InspectionEvent::getAction)
                            .containsExactly(InspectionEventAction.SUBMITTED, InspectionEventAction.ADDENDUM);
                    var addendum = history.getLast();
                    assertThat(addendum.getCreatedAt()).isEqualTo(at.plusSeconds(1));
                    events.save(InspectionEvent.builder().id(addendum.getId()).inspection(report)
                            .actor(addendum.getActor()).action(addendum.getAction())
                            .note("Tampered").createdAt(addendum.getCreatedAt()).build());
                });
                tx.executeWithoutResult(status -> assertThat(events.findByInspectionIdOrderByCreatedAtAscIdAsc(reportId)
                        .getLast().getNote()).isEqualTo("Ghi chú gốc"));
                try (var statement = db.connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT DATE_FORMAT(vehicle_received_at,'%Y-%m-%d %H:%i:%s.%f') FROM bookings WHERE id=1")) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("2026-10-30 03:04:05.123456");
                }
            } finally {
                TimeZone.setDefault(original);
            }
        }
    }

    @Test
    void onlyPendingAndConfirmedBlockHalfOpenIntervalsInSearchAndLockingQuery() throws Exception {
        try (var db = RentalLifecycleTest.migratedDatabase(); var app = boot(db)) {
            var bookings = app.getBean(BookingRepository.class);
            var cars = app.getBean(CarRepository.class);
            var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
            for (BookingStatus state : BookingStatus.values()) {
                tx.executeWithoutResult(transaction -> {
                    var booking = bookings.findById(1L).orElseThrow();
                    booking.setStatus(state);
                    bookings.flush();
                    var start = booking.getStartDate();
                    var end = booking.getEndDate();
                    boolean blocked = state == BookingStatus.PENDING || state == BookingStatus.CONFIRMED;
                    assertThat(bookings.findConflictsForUpdate(1L, start, end)).hasSize(blocked ? 1 : 0);
                    assertThat(cars.findAll(CarSpecifications.availableBetween(start, end)).stream()
                            .anyMatch(car -> car.getId().equals(1L))).isEqualTo(!blocked);
                    assertThat(bookings.findConflictsForUpdate(1L, end, end.plusDays(1))).isEmpty();
                    assertThat(bookings.findConflictsForUpdate(1L, start.minusDays(1), start)).isEmpty();
                });
            }
        }
    }

    private static ConfigurableApplicationContext boot(PaymentTestDatabase db) {
        return new SpringApplicationBuilder(com.carrent.CarRentApplication.class).run("--server.port=0",
                "--spring.datasource.url=" + db.url, "--spring.datasource.username=" + db.username,
                "--spring.datasource.password=" + db.password, "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.main.banner-mode=off", "--carrent.payment.maintenance.enabled=false",
                "--debug=false", "--logging.level.root=WARN");
    }
}
