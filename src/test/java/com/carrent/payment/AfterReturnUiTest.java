package com.carrent.payment;

import com.carrent.inspection.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

class AfterReturnUiTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root", root.toString());
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.inspection.storage-root"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void ready() throws Exception {
        for (var table : List.of("inspection_events", "inspection_images", "booking_inspections", "notifications")) f.jdbc.update("DELETE FROM " + table);
        f.reset();
        f.jdbc.update("UPDATE bookings SET status='CONFIRMED',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at='2026-10-29 00:00:00',before_accepted_at='2026-10-29 00:00:00',advance_amount=600000,balance_amount=1400000,advance_paid_at='2026-10-29 00:00:00',vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL,start_date='2026-10-29',end_date='2026-10-31',price_per_day_snapshot=1000000,total_amount=2000000 WHERE id=1");
        draft(InspectionPhase.BEFORE, 2, "Xe trước thuê");
        f.jdbc.update("UPDATE booking_inspections SET status='ACCEPTED',submitted_at='2026-10-29 00:00:00' WHERE booking_id=1");
        f.jdbc.update("UPDATE bookings SET vehicle_received_at='2026-10-29 00:00:00',contract_confirmed_at='2026-10-29 00:00:00',contract_confirmed_by=2 WHERE id=1");
        draft(InspectionPhase.AFTER, 4, "Xe sau thuê <script>alert(1)</script>");
    }
    void draft(InspectionPhase phase, long actor, String note) throws Exception {
        var service=f.app.getBean(InspectionService.class);
        var form=new InspectionForm(); form.setConditionNote(note); form.setVisibleDamage("Không phát hiện");
        form.setOdometerKm(12345); form.setEnergyPercent(80); form.setAccessoriesNote("Chìa khóa");
        service.saveDraft(1L, phase, form, f.actor(actor));
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        var photo=new MockMultipartFile("images", "a.png", "image/png", out.toByteArray());
        service.addImages(1L, phase, List.of(photo,photo,photo,photo), f.actor(actor));
    }
    @Test void customerCanSubmitAfterAndOwnerSeesComparisonAndPhysicalReceiptForm() throws Exception {
        f.mvc.perform(get("/bookings/1/inspections/AFTER").with(user(f.actor(4))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Gửi AFTER")))
                .andExpect(content().string(containsString("Xe trước thuê")));
        f.mvc.perform(post("/bookings/1/inspections/AFTER/submit").with(user(f.actor(4))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/bookings/1/inspections/AFTER"))
                .andExpect(flash().attributeExists("successMessage"));
        f.mvc.perform(get("/owner/bookings/1").with(user(f.actor(2))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/owner/bookings/1/return")))
                .andExpect(content().string(containsString("RECEIVED_DISPUTED")));
        assertThat(f.jdbc.queryForObject("SELECT vehicle_returned_at FROM bookings WHERE id=1", java.sql.Timestamp.class)).isNull();
    }
    void submitAfter() throws Exception {
        f.mvc.perform(post("/bookings/1/inspections/AFTER/submit").with(user(f.actor(4))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
    }
    @ParameterizedTest @ValueSource(strings={"RECEIVED_ACCEPTED","RECEIVED_DISPUTED"})
    void physicalReceiptShowsReadOnlyBalanceAndReturnedStatusWithoutCheckout(String decision) throws Exception {
        submitAfter();
        f.mvc.perform(post("/owner/bookings/1/return").param("decision",decision).param("note","Vết xước <script>alert(2)</script>")
                        .param("vehicleReturnedAt","2000-01-01T00:00:00Z").param("balanceAmount","1").with(user(f.actor(2))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/owner/bookings/1"))
                .andExpect(flash().attributeExists("successMessage"));
        for (var path:List.of("/owner/bookings/1","/bookings/1")) {
            f.mvc.perform(get(path).with(user(f.actor(path.startsWith("/owner")?2:4))))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("Đã nhận lại xe")))
                    .andExpect(content().string(containsString("Nghĩa vụ thanh toán 70%")))
                    .andExpect(content().string(containsString("1.400.000 VNĐ")))
                    .andExpect(content().string(containsString("30/10/2026 11:00:00")))
                    .andExpect(content().string(not(containsString("/owner/bookings/1/return"))))
                    .andExpect(content().string(not(containsString("/owner/bookings/1/complete"))));
        }
        for(var path:List.of("/owner/bookings","/bookings")) {
            var html=f.mvc.perform(get(path).with(user(f.actor(path.startsWith("/owner")?2:4))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("Đã nhận lại xe");
        }
        f.mvc.perform(get("/bookings/1/inspections/AFTER").with(user(f.actor(4))))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("<script>alert(2)</script>"))))
                .andExpect(content().string(not(containsString("Gửi AFTER"))));
        assertThat(f.app.getBean(com.carrent.booking.BookingService.class).getBookingForView(1L,f.actor(2)).getVehicleReturnedAt()).isEqualTo(PaymentFlowFixture.NOW);
        assertThat(f.jdbc.queryForObject("SELECT amount_due FROM payment_obligations WHERE business_key='BOOKING:1:BALANCE'",Long.class)).isEqualTo(1400000L);
    }
    @Test void notReceivedIsVisibleAfterReloadAndDoesNotCreateDebt() throws Exception {
        submitAfter();
        f.mvc.perform(post("/owner/bookings/1/return").param("decision","NOT_RECEIVED").param("note","Khách chưa mang xe đến")
                        .with(user(f.actor(2))).with(csrf())).andExpect(status().is3xxRedirection());
        for(var path:List.of("/owner/bookings/1","/bookings/1")) f.mvc.perform(get(path).with(user(f.actor(path.startsWith("/owner")?2:4))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Chủ xe đã ghi nhận chưa nhận lại xe thực tế.")))
                .andExpect(content().string(not(containsString("Nghĩa vụ thanh toán 70%"))));
        assertThat(f.jdbc.queryForObject("SELECT vehicle_returned_at FROM bookings WHERE id=1",java.sql.Timestamp.class)).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE business_key='BOOKING:1:BALANCE'",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"missing","unknown","dispute-note","long-note"})
    void invalidReturnKeepsInputsAndFieldErrors(String invalid) throws Exception {
        submitAfter();
        var request=post("/owner/bookings/1/return").with(user(f.actor(2))).with(csrf());
        String note=invalid.equals("long-note")?"a".repeat(2001):invalid.equals("dispute-note")?"":"Giữ nội dung <script>alert(3)</script>";
        request.param("note",note);
        if(!invalid.equals("missing")) request.param("decision",invalid.equals("unknown")?"FORGED":invalid.equals("dispute-note")?"RECEIVED_DISPUTED":"RECEIVED_ACCEPTED");
        f.mvc.perform(request).andExpect(status().isOk()).andExpect(view().name("owner/booking-detail"))
                .andExpect(model().attributeHasFieldErrors("returnForm",invalid.equals("dispute-note")||invalid.equals("long-note")?"note":"decision"))
                .andExpect(content().string(containsString(note.replace("<","&lt;").replace(">","&gt;"))));
        assertThat(f.jdbc.queryForObject("SELECT vehicle_returned_at FROM bookings WHERE id=1",java.sql.Timestamp.class)).isNull();
    }
    @Test void ownerCannotReadCustomerDraftAndAfterMutationsRequireCsrf() throws Exception {
        f.mvc.perform(get("/owner/bookings/1").with(user(f.actor(2)))).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Xe sau thuê"))))
                .andExpect(content().string(not(containsString("/owner/bookings/1/return"))));
        f.mvc.perform(get("/bookings/1/inspections/AFTER").with(user(f.actor(2)))).andExpect(status().isNotFound());
        f.mvc.perform(post("/bookings/1/inspections/AFTER/submit").with(user(f.actor(4)))).andExpect(status().isForbidden());
        submitAfter();
        f.mvc.perform(post("/owner/bookings/1/return").param("decision","RECEIVED_ACCEPTED").with(user(f.actor(2)))).andExpect(status().isForbidden());
        f.mvc.perform(post("/owner/bookings/1/return").param("decision","RECEIVED_ACCEPTED").with(user(f.actor(4))).with(csrf())).andExpect(status().isForbidden());
    }
}
