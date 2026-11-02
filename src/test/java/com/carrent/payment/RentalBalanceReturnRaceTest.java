package com.carrent.payment;

import com.carrent.booking.*;
import com.carrent.car.Car;
import com.carrent.inspection.*;
import jakarta.persistence.LockModeType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.UnexpectedRollbackException;
import static org.assertj.core.api.Assertions.*;

class RentalBalanceReturnRaceTest {
    static PaymentFlowFixture f;
    @TempDir static Path root;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root", root.toString());
        System.setProperty("carrent.rental.balance.enabled", "true");
        try { f = new PaymentFlowFixture(false); }
        finally { System.clearProperty("carrent.inspection.storage-root"); System.clearProperty("carrent.rental.balance.enabled"); }
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void ready() throws Exception {
        for (var table : List.of("inspection_events", "inspection_images", "booking_inspections", "notifications")) f.jdbc.update("DELETE FROM " + table);
        f.reset(); f.jdbc.update("DELETE FROM payment_obligations");
        f.jdbc.update("UPDATE users SET role=CASE WHEN id=2 THEN 'OWNER' ELSE 'CUSTOMER' END WHERE id IN(2,4)");
        f.jdbc.update("UPDATE bookings SET status='CONFIRMED',workflow_version='VIETQR_V1',inspection_required=1,owner_accepted_at='2026-10-28',before_accepted_at='2026-10-28',advance_amount=600000,balance_amount=1400000,advance_paid_at='2026-10-28',vehicle_received_at='2026-10-29 03:00:00',vehicle_returned_at=NULL,after_reminder_sent_at=NULL,contract_confirmed_at='2026-10-29 03:00:00',contract_confirmed_by=2,start_date='2026-10-29',end_date='2026-10-31',price_per_day_snapshot=1000000,total_amount=2000000 WHERE id=1");
        var inspections=f.app.getBean(InspectionService.class);
        var form=new InspectionForm(); form.setConditionNote("Clean car"); form.setVisibleDamage("No damage");
        form.setOdometerKm(12500); form.setEnergyPercent(70); form.setAccessoriesNote("Two keys");
        inspections.saveDraft(1L, InspectionPhase.AFTER, form, f.actor(4));
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",out);
        var photo=new MockMultipartFile("images","a.png","image/png",out.toByteArray());
        inspections.addImages(1L,InspectionPhase.AFTER,List.of(photo,photo,photo,photo),f.actor(4));
        inspections.submitAfter(1L,f.actor(4));
    }
    BookingService bookings() { return f.app.getBean(BookingService.class); }
    ReturnReceiptForm receipt() {
        var form=new ReturnReceiptForm(); form.setDecision(ReturnReceiptForm.Decision.RECEIVED_ACCEPTED); return form;
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void returnedBookingRetryAndBalanceReceiptSerializeAgainstStaleSnapshots(boolean returnFirst) throws Exception {
        var owner=f.actor(2);
        bookings().confirmReturn(1L,receipt(),owner);
        var returned=bookings().getBookingForView(1L,owner).getVehicleReturnedAt();
        var evidence=f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE booking_id=1 AND phase='AFTER'");
        var imageRows=f.jdbc.queryForList("SELECT * FROM inspection_images ORDER BY id");
        long obligation=f.jdbc.queryForObject("SELECT id FROM payment_obligations WHERE business_key='BOOKING:1:BALANCE'",Long.class);
        var intent=f.payments.openAttempt(obligation,f.actor(4));
        long inbox=f.receive(917001,intent.code(),"1400000");
        var claim=f.app.getBean(InboxWorkService.class).claim(inbox);
        assertThat(claim).isNotNull();
        Runnable retry=()->bookings().confirmReturn(1L,receipt(),owner);
        Runnable apply=()->f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());
        var held=new CountDownLatch(1); var snapshot=new CountDownLatch(1);
        var connections=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->f.tx.executeWithoutResult(tx->{
                connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                f.em.find(Car.class,1L,LockModeType.PESSIMISTIC_WRITE);
                held.countDown(); await(snapshot);
                if(returnFirst)retry.run();else apply.run();
            }));
            var b=pool.submit(()->{
                await(held);
                try {
                    f.tx.executeWithoutResult(tx->{
                        connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                        f.em.find(Booking.class,1L).getStatus();
                        f.em.find(PaymentObligation.class,obligation).getStatus();
                        snapshot.countDown();
                        if(returnFirst)apply.run();
                        else assertThatThrownBy(retry::run).isInstanceOf(com.carrent.common.exception.BusinessException.class);
                    });
                } catch(UnexpectedRollbackException ex) { if(returnFirst)throw ex; }
            });
            try { a.get(30,TimeUnit.SECONDS); b.get(30,TimeUnit.SECONDS); }
            finally {held.countDown();snapshot.countDown();}
        }
        assertThat(connections).hasSize(2).doesNotHaveDuplicates();
        var booking=bookings().getBookingForView(1L,owner);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(booking.getVehicleReturnedAt()).isEqualTo(returned);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE id=?",Long.class,obligation)).isEqualTo(1400000L);
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.count("payment_obligations")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='RETURN_CONFIRMED'",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForMap("SELECT * FROM booking_inspections WHERE booking_id=1 AND phase='AFTER'")).isEqualTo(evidence);
        assertThat(f.jdbc.queryForList("SELECT * FROM inspection_images ORDER BY id")).isEqualTo(imageRows);
    }

    @Test void rolledBackFirstReturnLeavesNoPayableBalanceAndNoCompletedUnreturnedBooking() {
        var owner=f.actor(2);
        f.tx.executeWithoutResult(tx->{bookings().confirmReturn(1L,receipt(),owner);tx.setRollbackOnly();});
        var booking=bookings().getBookingForView(1L,owner);
        assertThat(booking.getVehicleReturnedAt()).isNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(f.count("payment_obligations")).isZero();
        assertThat(f.count("payment_intents")).isZero();
        assertThat(f.count("payment_receipts")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='RETURN_CONFIRMED'",Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='INSPECTION_RETURN_CONFIRMED'",Long.class)).isZero();
        bookings().confirmReturn(1L,receipt(),owner);
        assertThat(bookings().getBookingForView(1L,owner).getVehicleReturnedAt()).isNotNull();
        assertThat(f.count("payment_obligations")).isEqualTo(1);
    }

    static void await(CountDownLatch latch) {
        try {if(!latch.await(20,TimeUnit.SECONDS))throw new AssertionError("Race timed out");}
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new AssertionError(ex);}
    }
}
