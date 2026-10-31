package com.carrent.payment;

import com.carrent.booking.Booking;
import com.carrent.booking.BookingService;
import com.carrent.booking.BookingStatus;
import com.carrent.common.exception.BusinessException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RentalCancellationGuardTest {
    static PaymentFlowFixture f;

    @BeforeAll static void start() throws Exception { f = new PaymentFlowFixture(false, false); }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }

    @BeforeEach
    void reset() {
        f.reset();
        f.jdbc.update("""
                UPDATE bookings SET status='CONFIRMED', workflow_version='VIETQR_V1', inspection_required=1,
                vehicle_received_at=NULL, vehicle_returned_at=NULL,
                contract_confirmed_at=NULL, contract_confirmed_by=NULL WHERE id=1
                """);
        f.jdbc.update("""
                INSERT INTO payments(id,booking_id,amount,method,status,transaction_reference,paid_at,created_at,updated_at)
                VALUES(9001,1,1800000,'MOCK_BANK_TRANSFER','PAID','P62-GUARD-FIXTURE',
                CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE status='PAID'
                """);
        f.jdbc.update("DELETE FROM notifications WHERE type='BOOKING_CANCELLED' AND related_entity_id=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CONFIRMED"})
    void aHandedOverBookingCannotBeCancelledOrRefunded(String bookingStatus) {
        f.jdbc.update("UPDATE bookings SET status=? WHERE id=1", bookingStatus);
        handover();
        var before = snapshot();
        assertThatThrownBy(() -> f.app.getBean(BookingService.class).cancelBooking(1L, f.actor(4)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("đã giao xe");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void returningTheCarDoesNotReopenCancellation() {
        handover();
        f.jdbc.update("UPDATE bookings SET vehicle_returned_at='2026-10-31 04:00:00' WHERE id=1");
        var before = snapshot();
        assertThatThrownBy(() -> f.app.getBean(BookingService.class).cancelBooking(1L, f.actor(4)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("đã giao xe");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void theHttpEndpointShowsTheBusinessErrorWithoutReportingSuccessOrChangingMoney() throws Exception {
        handover();
        var before = snapshot();
        f.mvc.perform(post("/bookings/1/cancel").with(user(f.actor(4))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/bookings/1"))
                .andExpect(flash().attribute("errorMessage", "Không thể hủy đơn thuê đã giao xe."))
                .andExpect(flash().attributeCount(1));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CONFIRMED"})
    void legacyBookingsWithoutHandoverKeepTheirExistingCancellationAndMockRefund(String bookingStatus) {
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY', inspection_required=0, status=? WHERE id=1",
                bookingStatus);
        var actor = f.actor(4);
        var service = f.app.getBean(BookingService.class);
        long beforeNotifications = f.count("notifications");
        service.cancelBooking(1L, actor);
        var booking = service.getBookingForView(1L, actor);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getWorkflowVersion()).isEqualTo("LEGACY");
        assertThat(booking.getVehicleReceivedAt()).isNull();
        assertThat(f.app.getBean(PaymentRepository.class).findByBookingIdOrderByCreatedAtDesc(1L))
                .extracting(Payment::getStatus).containsOnly(PaymentStatus.REFUNDED);
        assertThat(f.count("notifications")).isEqualTo(beforeNotifications + 2);
    }

    @Test
    void aStaleManagedBookingCannotBypassAHandoverCommittedOnAnotherConnection() {
        var actor = f.actor(4);
        var repeatable = new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        repeatable.executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            var stale = f.em.find(Booking.class, 1L);
            assertThat(stale.getVehicleReceivedAt()).isNull();
            Long firstConnection = f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            try {
                Long secondConnection = CompletableFuture.supplyAsync(() -> f.tx.execute(other -> {
                    handover();
                    return f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                })).get(15, TimeUnit.SECONDS);
                assertThat(secondConnection).isNotEqualTo(firstConnection);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertThat(stale.getVehicleReceivedAt()).isNull();
            var before = snapshot();
            assertThatThrownBy(() -> f.app.getBean(BookingService.class).cancelBooking(1L, actor))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("đã giao xe");
            assertThat(snapshot()).isEqualTo(before);
        });
        assertThat(f.app.getBean(BookingService.class).getBookingForView(1L, actor).getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(f.app.getBean(PaymentRepository.class).findByBookingIdOrderByCreatedAtDesc(1L))
                .extracting(Payment::getStatus).containsOnly(PaymentStatus.PAID);
    }

    private static Map<String, String> snapshot() {
        try {
            return f.db.legacySnapshot();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void handover() {
        f.jdbc.update("""
                UPDATE bookings SET vehicle_received_at='2026-10-31 03:00:00',
                contract_confirmed_at='2026-10-31 03:00:00', contract_confirmed_by=2 WHERE id=1
                """);
    }
}
