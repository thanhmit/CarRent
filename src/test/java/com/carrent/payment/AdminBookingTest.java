package com.carrent.payment;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminBookingTest {
    static PaymentFlowFixture f;
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path root;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root",root.toString());
        try { f=new PaymentFlowFixture(); }
        finally { System.clearProperty("carrent.inspection.storage-root"); }
    }
    @AfterAll static void stop() throws Exception { if(f!=null) f.close(); }
    @BeforeEach void reset() {
        for(String table:new String[]{"inspection_events","inspection_images","booking_inspections"}) f.jdbc.update("DELETE FROM "+table);
        f.reset();
        f.jdbc.update("UPDATE users SET role='ADMIN',status='ACTIVE' WHERE id=1");
        f.jdbc.update("""
                UPDATE bookings SET workflow_version='VIETQR_V1',inspection_required=1,status='CONFIRMED',
                advance_amount=600000,balance_amount=1400000,contract_confirmed_by=2,
                contract_confirmed_at='2026-10-29 00:00:00',vehicle_received_at='2026-10-29 00:00:00',
                vehicle_returned_at='2026-10-30 02:00:00' WHERE id=1
                """);
    }

    @Test void bookingFinanceShowsOnlyCanonicalRentalRowsAndKeepsReceiptAndRefundStatesSeparate() throws Exception {
        finance();
        var before=snapshot();
        String page=html("/admin/bookings/1",1);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(page).contains("Nghĩa vụ thanh toán 30/70", "Tiền đã nhận", "Hoàn tiền", "600.000", "1.400.000", "Khoản #401", "Khoản #501", "••••3456")
                .doesNotContain("000123456", "Khoản #402", "Khoản #502", "data-obligation-id=\"299\"");
        f.jdbc.update("UPDATE payment_obligations SET payer_user_id=5 WHERE id=201");
        page=html("/admin/bookings/1",1);
        assertThat(page).doesNotContain("Khoản #401", "Khoản #501");
        assertThat(f.app.getBean(com.carrent.booking.BookingTimeline.class).view(1L,f.actor(1)).refunds()).isEmpty();
    }

    @Test void malformedFinancialOwnershipNeverBecomesBookingMoney() {
        finance();
        var finance=f.app.getBean(com.carrent.admin.AdminFinanceService.class);
        var timeline=f.app.getBean(com.carrent.booking.BookingTimeline.class);
        f.jdbc.update("UPDATE payment_obligations SET status='SETTLED',amount_settled=1400000 WHERE id=202");
        assertThat(timeline.view(1L,f.actor(1)).balanceSettled()).isTrue();
        for(String invalid:new String[]{"payer_user_id=5","collector_user_id=1","amount_due=1400001,amount_settled=1400001"})
            f.tx.executeWithoutResult(s->{
                f.jdbc.update("UPDATE payment_obligations SET "+invalid+" WHERE id=202");
                assertThat(timeline.view(1L,f.actor(1)).balanceSettled()).isFalse();s.setRollbackOnly();
            });
        for(String update:new String[]{"UPDATE payment_channels SET user_id=3 WHERE id=101",
                "UPDATE refunds SET recipient_user_id=5 WHERE id=501",
                "UPDATE refunds SET payout_channel_id=104 WHERE id=501"})
            f.tx.executeWithoutResult(s->{
                f.jdbc.update(update);
                assertThat(finance.booking(1L,f.actor(1)).get(com.carrent.admin.AdminFinanceService.Kind.REFUNDS)).isEmpty();
                assertThat(timeline.view(1L,f.actor(1)).refunds()).isEmpty();s.setRollbackOnly();
            });
    }

    @Test void businessDeadlineIsInclusiveAndNeverUsesQrExpiryOrNotificationMarkers() throws Exception {
        finance();
        var service=f.app.getBean(com.carrent.admin.AdminBookingService.class);
        var overdue=com.carrent.admin.AdminBookingService.Attention.BALANCE_OVERDUE;
        var refund=com.carrent.admin.AdminBookingService.Attention.REFUND_OVERDUE;
        var at=f.clock.instant();
        f.clock.at.set(at.minusNanos(1000));
        assertThat(service.list(null,overdue,0,f.actor(1)).getContent()).isEmpty();
        assertThat(service.list(null,refund,0,f.actor(1)).getContent()).isEmpty();
        f.clock.at.set(at);
        assertThat(service.list(null,overdue,0,f.actor(1)).getContent()).extracting(com.carrent.booking.Booking::getId).containsExactly(1L);
        assertThat(service.list(null,refund,0,f.actor(1)).getContent()).extracting(com.carrent.booking.Booking::getId).containsExactly(1L);
        f.jdbc.update("UPDATE payment_intents SET created_at='2026-10-29 00:00:00',expires_at='2026-10-29 00:15:00' WHERE id=301");
        f.clock.at.set(at.minusNanos(1000));
        assertThat(service.list(null,overdue,0,f.actor(1)).getContent()).isEmpty();
        f.clock.at.set(at);
        assertThat(html("/admin/bookings/1",1)).contains("Quá hạn", "2026-10-30T03:00:00Z");
        f.jdbc.update("UPDATE bookings SET balance_overdue_notified_at=? WHERE id=1",java.time.LocalDateTime.ofInstant(at,java.time.ZoneOffset.UTC));
        assertThat(service.list(null,overdue,0,f.actor(1)).getTotalElements()).isEqualTo(1);
        for(String invalid:new String[]{"amount_due=1399999","payer_user_id=5","collector_user_id=1","due_at=DATE_ADD(due_at,INTERVAL 1 SECOND)","status='SETTLED',amount_settled=1400000"}) {
            f.tx.executeWithoutResult(s->{
                f.jdbc.update("UPDATE payment_obligations SET "+invalid+" WHERE id=202");
                assertThat(service.list(null,overdue,0,f.actor(1)).getTotalElements()).isZero();s.setRollbackOnly();
            });
        }
        f.jdbc.update("UPDATE refunds SET status='SUCCEEDED' WHERE id=501");
        assertThat(service.list(null,refund,0,f.actor(1)).getContent()).isEmpty();
    }

    @Test void roleMatrixStaleSessionInvalidFiltersAndMissingBookingAreRejected() throws Exception {
        for(long actor:new long[]{2,4}) for(String path:new String[]{"/admin/bookings","/admin/bookings/1"})
            f.mvc.perform(get(path).with(user(f.actor(actor)))).andExpect(status().isForbidden());
        f.mvc.perform(get("/admin/bookings")).andExpect(status().is3xxRedirection());
        for(String bad:new String[]{"status=UNKNOWN","attention=UNKNOWN","page=invalid"})
            f.mvc.perform(get("/admin/bookings?"+bad).with(user(f.actor(1)))).andExpect(status().isBadRequest());
        f.mvc.perform(get("/admin/bookings/999999").with(user(f.actor(1)))).andExpect(status().isNotFound());
        var actor=f.actor(1);
        try {
            for(String change:new String[]{"status='INACTIVE'","status='BANNED'","status='ACTIVE',role='OWNER'"}) {
                f.jdbc.update("UPDATE users SET "+change+" WHERE id=1");
                for(String path:new String[]{"/admin/bookings","/admin/bookings/1"})
                    f.mvc.perform(get(path).with(user(actor))).andExpect(status().isForbidden());
            }
        } finally { f.jdbc.update("UPDATE users SET role='ADMIN',status='ACTIVE' WHERE id=1"); }
        for(String suffix:new String[]{"/return","/handover","/resolve","/contract"})
            f.mvc.perform(post("/admin/bookings/1"+suffix).with(user(actor)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void legacyAndUserInputAreSafelyRenderedWithoutInventingMoneyOrActions() throws Exception {
        var name=f.jdbc.queryForObject("SELECT full_name FROM users WHERE id=4",String.class);
        try {
            f.jdbc.update("UPDATE users SET full_name='<img src=x onerror=alert(54)>' WHERE id=4");
            for(String path:new String[]{"/admin/bookings","/admin/bookings/1"})
                assertThat(html(path,1)).contains("&lt;img").doesNotContain("<img src=x onerror", "password_hash", "credentialReference", "/admin/bookings/1/return", "/admin/bookings/1/handover", "/admin/bookings/1/resolve");
        } finally { f.jdbc.update("UPDATE users SET full_name=? WHERE id=4",name); }
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY',inspection_required=0,vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL WHERE id=1");
        assertThat(html("/admin/bookings/1",1)).contains("Đơn thuê legacy").doesNotContain("data-finance-kind", "id=\"rental-timeline\"");
    }

    @Test void completedDisputedEvidenceRemainsPrivateAndReadOnlyWithoutRequiringAdminResolution() throws Exception {
        var inspections=f.app.getBean(com.carrent.inspection.InspectionService.class);
        f.jdbc.update("UPDATE bookings SET status='PENDING',vehicle_received_at=NULL,vehicle_returned_at=NULL,contract_confirmed_at=NULL,contract_confirmed_by=NULL WHERE id=1");
        var images=new java.util.EnumMap<com.carrent.inspection.InspectionPhase,Long>(com.carrent.inspection.InspectionPhase.class);
        for(var phase:com.carrent.inspection.InspectionPhase.values()) {
            if(phase==com.carrent.inspection.InspectionPhase.AFTER)
                f.jdbc.update("UPDATE bookings SET status='CONFIRMED',vehicle_received_at='2026-10-29 00:00:00',contract_confirmed_at='2026-10-29 00:00:00',contract_confirmed_by=2 WHERE id=1");
            var author=f.actor(phase==com.carrent.inspection.InspectionPhase.BEFORE?2:4);
            var form=new com.carrent.inspection.InspectionForm();form.setConditionNote("<script>alert(54)</script>");
            inspections.saveDraft(1L,phase,form,author);
            var bytes=new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
            inspections.addImages(1L,phase,java.util.List.of(new org.springframework.mock.web.MockMultipartFile("images","evidence.png","image/png",bytes.toByteArray())),author);
            var report=inspections.getView(1L,phase,author);
            images.put(phase,report.images().getFirst().id());
            f.mvc.perform(get("/bookings/1/inspections/"+phase+"/images/"+images.get(phase)).with(user(f.actor(1)))).andExpect(status().isNotFound());
            f.jdbc.update("UPDATE booking_inspections SET status=?,submitted_at='2026-10-29 01:00:00' WHERE id=?",phase==com.carrent.inspection.InspectionPhase.BEFORE?"ACCEPTED":"DISPUTED",report.reportId());
            f.jdbc.update("INSERT INTO inspection_events(inspection_id,actor_id,action,note,created_at) VALUES(?,2,'DISPUTED','<img src=x onerror=alert(54)>','2026-10-29 02:00:00')",report.reportId());
        }
        f.jdbc.update("UPDATE bookings SET status='COMPLETED',vehicle_returned_at='2026-10-30 02:00:00' WHERE id=1");
        var before=snapshot();
        assertThat(html("/admin/bookings?status=COMPLETED",1)).contains("Phần 70%:").doesNotContain("Còn lại 70%:");
        assertThat(html("/admin/bookings/1",1)).contains("Có bất đồng AFTER", "/inspections/BEFORE", "/inspections/AFTER");
        for(var entry:images.entrySet()) {
            String base="/bookings/1/inspections/"+entry.getKey();
            for(long actor:new long[]{1,2,4}) {
                assertThat(html(base,actor)).contains("&lt;script&gt;", "&lt;img").doesNotContain("<script>alert", "<img src=x onerror", "/resolve", "/submit");
                f.mvc.perform(get(base+"/images/"+entry.getValue()).with(user(f.actor(actor))))
                        .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                        .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
            }
            for(long outsider:new long[]{3,5}) f.mvc.perform(get(base+"/images/"+entry.getValue()).with(user(f.actor(outsider)))).andExpect(status().isNotFound());
            f.mvc.perform(get(base+"/images/"+entry.getValue())).andExpect(status().is3xxRedirection());
            f.mvc.perform(get(base.replace("/1/","/2/")+"/images/"+entry.getValue()).with(user(f.actor(1)))).andExpect(status().isNotFound());
            String wrong=entry.getKey()==com.carrent.inspection.InspectionPhase.BEFORE?"AFTER":"BEFORE";
            f.mvc.perform(get("/bookings/1/inspections/"+wrong+"/images/"+entry.getValue()).with(user(f.actor(1)))).andExpect(status().isNotFound());
            String storage=f.jdbc.queryForObject("SELECT storage_key FROM inspection_images WHERE id=?",String.class,entry.getValue());
            f.mvc.perform(get("/uploads/"+storage)).andExpect(status().isNotFound());
            var admin=f.actor(1);
            try {
                for(String change:new String[]{"status='BANNED'","status='ACTIVE',role='OWNER'"}) {
                    f.jdbc.update("UPDATE users SET "+change+" WHERE id=1");
                    f.mvc.perform(get(base+"/images/"+entry.getValue()).with(user(admin))).andExpect(status().isForbidden());
                }
            } finally { f.jdbc.update("UPDATE users SET status='ACTIVE',role='ADMIN' WHERE id=1"); }
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    private java.util.Map<String,java.util.List<String>> snapshot() {
        var result=new java.util.LinkedHashMap<String,java.util.List<String>>();
        for(String table:new String[]{"bookings","cars","payment_channels","payment_obligations","payment_intents","payment_receipts","refunds","payout_attempts","notifications","booking_inspections","inspection_events","inspection_images"})
            result.put(table,f.jdbc.query("SELECT * FROM "+table+" ORDER BY id",(rs,n)->{
                var row=new StringBuilder();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.append(rs.getString(i)).append('|');return row.toString();}));
        return result;
    }

    private void finance() {
        var now=java.time.LocalDateTime.ofInstant(f.clock.instant(),java.time.ZoneOffset.UTC);
        f.jdbc.update("""
                INSERT INTO payment_obligations(id,business_key,purpose,subject_type,subject_id,payer_user_id,
                collector_user_id,amount_due,amount_settled,due_at,status,created_at,updated_at)
                VALUES(202,'BOOKING:1:BALANCE','BALANCE','BOOKING',1,4,2,1400000,0,?,'OPEN',?,?),
                (299,'BOOKING:2:ADVANCE','ADVANCE','BOOKING',2,4,2,600000,0,NULL,'OPEN',?,?)
                """,now,now,now,now,now);
        for(long id:new long[]{201,299}) {
            long intent=id+100,receipt=id==201?401:402,refund=id==201?501:502;
            f.jdbc.update("INSERT INTO payment_intents(id,obligation_id,code,channel_id,amount,status,created_at,expires_at) VALUES(?,?,?,101,600000,'RECEIVED',?,?)",intent,id,"CRADMIN"+id,now,now.plusDays(1));
            f.jdbc.update("INSERT INTO webhook_inbox(id,channel_id,provider_transaction_id,raw_payload,received_at,status) VALUES(?,101,?,X'01',?,'PROCESSED')",receipt,Long.toString(id),now);
            f.jdbc.update("INSERT INTO payment_receipts(id,channel_id,provider_transaction_id,inbox_id,intent_id,amount,allocated_amount,credited_at,created_at,status) VALUES(?,101,?,?,?,600000,0,?,?,'REFUND_REQUIRED')",receipt,Long.toString(id),receipt,intent,now,now);
            f.jdbc.update("""
                    INSERT INTO refunds(id,business_key,receipt_id,payout_channel_id,recipient_user_id,amount,reason,
                    status,bank_bin,account_number,account_name,ready_at,due_at,created_at,updated_at) VALUES(?,?,?,103,4,600000,'DUPLICATE_PAYMENT',
                    'WAITING_FUNDS','970422','000123456','TEST',?,?,?,?)
                    """,refund,"ADMIN"+id,receipt,now.minusHours(1),now,now,now);
        }
    }

    @Test void adminBookingListIsReadOnlyAndLinksToExistingRentalDetail() throws Exception {
        var before=f.jdbc.queryForList("SELECT * FROM bookings ORDER BY id");
        assertThat(html("/admin/bookings",1)).contains("Theo dõi đơn thuê", "/admin/bookings/1", "name=\"attention\"");
        assertThat(f.jdbc.queryForList("SELECT * FROM bookings ORDER BY id")).isEqualTo(before);
    }

    private String html(String url,long actor) throws Exception {
        return f.mvc.perform(get(url).with(user(f.actor(actor)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
