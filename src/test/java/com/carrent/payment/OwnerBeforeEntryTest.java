package com.carrent.payment;

import com.carrent.booking.BookingService;
import com.carrent.booking.BookingStatus;
import com.carrent.common.exception.BusinessException;
import com.carrent.inspection.InspectionPhase;
import com.carrent.inspection.InspectionService;
import com.carrent.inspection.InspectionStatus;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OwnerBeforeEntryTest {
    static PaymentFlowFixture f;
    static int day;
    @TempDir static Path storage;

    @BeforeAll static void start() throws Exception {
        var settings = Map.of("carrent.rental.workflow.enabled", "true", "carrent.rental.advance.enabled", "true",
                "carrent.rental.balance.enabled", "true", "fixture.payment-pilot.owner-id", "0",
                "fixture.payment-pilot.admin-id", "0", "carrent.inspection.storage-root", storage.toString());
        var previous = new java.util.HashMap<String, String>();
        settings.forEach((key, value) -> { previous.put(key, System.getProperty(key)); System.setProperty(key, value); });
        try { f = new PaymentFlowFixture(false, false); }
        finally { previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() {
        f.jdbc.update("DELETE FROM inspection_events");
        f.jdbc.update("DELETE FROM inspection_images");
        f.jdbc.update("DELETE FROM booking_inspections");
        f.jdbc.update("DELETE FROM notifications");
        f.reset();
        f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE users SET role='OWNER',status='ACTIVE' WHERE id IN (2,3)");
        f.jdbc.update("UPDATE cars SET approval_status='APPROVED',listing_status='ACTIVE' WHERE id IN (1,6)");
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY',inspection_required=0,owner_accepted_at=NULL,before_accepted_at=NULL WHERE id=1");
    }

    long create(long car) throws Exception {
        var date = LocalDate.of(2032, 1, 1).plusDays(day++);
        String target = f.mvc.perform(post("/bookings").with(user(f.actor(4))).with(csrf())
                .param("carId", Long.toString(car)).param("startDate", date.toString()).param("endDate", date.plusDays(1).toString())
                .param("workflowVersion", "LEGACY").param("inspectionRequired", "false").param("status", "CONFIRMED"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return Long.parseLong(target.substring(target.lastIndexOf('/') + 1));
    }
    BookingService bookings() { return f.app.getBean(BookingService.class); }
    String before(long id) { return "/bookings/" + id + "/inspections/BEFORE"; }
    void accept(long id, long owner) throws Exception {
        f.mvc.perform(post("/owner/bookings/" + id + "/confirm").with(user(f.actor(owner))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(before(id)));
    }
    void noFinancialRecords() {
        for (var table : new String[]{"payment_obligations", "payment_intents", "payment_receipts", "refunds", "payout_attempts"})
            assertThat(f.count(table)).as(table).isZero();
    }

    @Test void standardApplicationConfigurationEnablesBeforeWorkflow() throws Exception {
        var props = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) { props.load(input); }
        for (String key : new String[]{"workflow", "advance", "balance"})
            assertThat(props.getProperty("carrent.rental." + key + ".enabled")).as(key).isEqualTo("true");
    }

    @ParameterizedTest @CsvSource({"2,1", "3,6"})
    void everyOwnerCanCreateAcceptSaveAndPublishBeforeWithoutPaymentPilot(long owner, long car) throws Exception {
        long id = create(car);
        var booking = bookings().getBookingForView(id, f.actor(owner));
        assertThat(booking.getWorkflowVersion()).isEqualTo("VIETQR_V1");
        assertThat(booking.isInspectionRequired()).isTrue();
        accept(id, owner);
        booking = bookings().getBookingForView(id, f.actor(owner));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getOwnerAcceptedAt()).isNotNull();
        assertThat(booking.getAdvancePaidAt()).isNull();
        assertThat(booking.getVehicleReceivedAt()).isNull();
        f.mvc.perform(get(before(id)).with(user(f.actor(owner)))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"conditionNote\"")));
        f.mvc.perform(post(before(id) + "/draft").with(user(f.actor(owner))).with(csrf())
                .param("conditionNote", "Clean bodywork").param("visibleDamage", "Small scratch on left door")
                .param("odometerKm", "12345").param("energyPercent", "80").param("accessoriesNote", "Keys and documents"))
                .andExpect(redirectedUrl(before(id) + "/draft"));
        var image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image);
        var upload = multipart(before(id) + "/images");
        for (int i = 0; i < 4; i++) upload.file(new MockMultipartFile("images", "evidence-" + i + ".png", "image/png", image.toByteArray()));
        f.mvc.perform(upload.with(user(f.actor(owner))).with(csrf())).andExpect(status().is3xxRedirection());
        f.mvc.perform(post(before(id) + "/submit").with(user(f.actor(owner))).with(csrf())).andExpect(status().is3xxRedirection());
        var report = f.app.getBean(InspectionService.class).getView(id, InspectionPhase.BEFORE, f.actor(4));
        assertThat(report.status()).isEqualTo(InspectionStatus.SUBMITTED);
        assertThat(report.conditionNote()).isEqualTo("Clean bodywork");
        assertThat(report.images()).hasSize(4);
        assertThatThrownBy(() -> bookings().acceptBefore(id, f.actor(4))).isInstanceOf(BusinessException.class);
        assertThat(bookings().getBookingForView(id, f.actor(owner)).getBeforeAcceptedAt()).isNull();
        noFinancialRecords();
    }

    @Test void repeatedAcceptanceKeepsTimestampAndSingleNotification() throws Exception {
        long id = create(1); accept(id, 2);
        var accepted = bookings().getBookingForView(id, f.actor(2)).getOwnerAcceptedAt();
        long count = f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE related_entity_id=? AND type='BOOKING_OWNER_ACCEPTED'", Long.class, id);
        f.clock.at.set(f.clock.instant().plusSeconds(60));
        accept(id, 2);
        assertThat(bookings().getBookingForView(id, f.actor(2)).getOwnerAcceptedAt()).isEqualTo(accepted);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE related_entity_id=? AND type='BOOKING_OWNER_ACCEPTED'", Long.class, id)).isEqualTo(count).isEqualTo(1L);
        noFinancialRecords();
    }

    @Test void forbiddenAndFailedConfirmNeverAdvanceToBefore() throws Exception {
        long id = create(1);
        for (long actor : new long[]{3, 4})
            f.mvc.perform(post("/owner/bookings/" + id + "/confirm").with(user(f.actor(actor))).with(csrf())).andExpect(status().is4xxClientError());
        f.mvc.perform(post("/owner/bookings/" + id + "/confirm").with(user(f.actor(2)))).andExpect(status().isForbidden());
        assertThat(bookings().getBookingForView(id, f.actor(2)).getOwnerAcceptedAt()).isNull();
        f.jdbc.update("UPDATE bookings SET status='CANCELLED' WHERE id=?", id);
        f.mvc.perform(post("/owner/bookings/" + id + "/confirm").with(user(f.actor(2))).with(csrf()))
                .andExpect(redirectedUrl("/owner/bookings/" + id)).andExpect(flash().attributeExists("errorMessage"));
        noFinancialRecords();
    }

    @Test void existingLegacyBookingKeepsItsOriginalWorkflowAndRedirect() throws Exception {
        f.mvc.perform(post("/owner/bookings/1/confirm").with(user(f.actor(2))).with(csrf()))
                .andExpect(redirectedUrl("/owner/bookings/1"));
        var booking = bookings().getBookingForView(1L, f.actor(2));
        assertThat(booking.getWorkflowVersion()).isEqualTo("LEGACY");
        assertThat(booking.isInspectionRequired()).isFalse();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        noFinancialRecords();
    }
}
