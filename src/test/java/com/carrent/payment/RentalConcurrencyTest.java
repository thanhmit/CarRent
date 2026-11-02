package com.carrent.payment;

import com.carrent.booking.Booking;
import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RentalConcurrencyTest {
    static PaymentFlowFixture f;
    static RentalLifecycleGateTest.Steps steps;
    @TempDir static Path root;
    @BeforeAll static void start() throws Exception { f=RentalLifecycleGateTest.Steps.boot(root);steps=new RentalLifecycleGateTest.Steps(f); }
    @AfterAll static void stop() throws Exception { if(f!=null)f.close(); }
    @BeforeEach void reset() { steps.reset(); }

    @ParameterizedTest @ValueSource(strings={"receipt-first","cancel-first"})
    void repeatableReadSnapshotCannotLoseBankMoneyOrReopenCancelledRental(String order) throws Exception {
        var intent=steps.agree();long inbox=steps.enqueue(intent,600000,65001);
        var claim=f.app.getBean(InboxWorkService.class).claim(inbox);var customer=f.actor(4);
        staleAfterCommit(
            ()->{f.em.find(Booking.class,1L).getStatus();f.em.find(PaymentIntent.class,intent.id()).getStatus();},
            ()->{if(order.equals("receipt-first"))f.tx.execute(s->{f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());return null;});else steps.bookings().cancelBooking(1L,customer,"first cancellation");},
            ()->{if(order.equals("receipt-first"))steps.bookings().cancelBooking(1L,customer,"paid cancellation");else f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());});
        assertThat(steps.state()).isEqualTo("CANCELLED");
        assertThat(f.jdbc.queryForObject("SELECT amount FROM refunds",Long.class)).isEqualTo(600000);
        assertThat(f.jdbc.queryForObject("SELECT allocated_amount FROM payment_receipts",Long.class)).isEqualTo(order.equals("receipt-first")?600000:0);
        assertThat(f.jdbc.queryForObject("SELECT status FROM refunds",String.class)).isEqualTo("WAITING_RECIPIENT");
    }

    @Test void staleOwnerSnapshotCannotHandoverAfterCommittedBan() throws Exception {
        steps.receive(steps.agree(),600000,65002);var owner=f.actor(2);
        assertThatThrownBy(()->staleAfterCommit(
            ()->{f.em.find(com.carrent.user.User.class,2L).getStatus();f.em.find(Booking.class,1L).getStatus();},
            ()->f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=2"),
            ()->steps.bookings().confirmHandover(1L,true,owner)))
            .isInstanceOf(ForbiddenException.class);
        assertThat(f.jdbc.queryForObject("SELECT vehicle_received_at FROM bookings WHERE id=1",java.time.LocalDateTime.class)).isNull();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='PICKUP_CONFIRMED'",Long.class)).isZero();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> actorMutations() {
        return java.util.stream.Stream.of("owner-accept","submit-before","accept-before","draft","add-image","delete-image","addendum","dispute")
                .flatMap(command -> java.util.stream.Stream.of("ban","role").map(change -> org.junit.jupiter.params.provider.Arguments.of(command,change)));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("actorMutations")
    void staleActorCannotMutateBeforeLifecycleAfterCommittedAccessChange(String command,String change) throws Exception {
        if (!command.equals("owner-accept")) steps.bookings().confirmBooking(1L,f.actor(2));
        if (!command.equals("owner-accept") && !command.equals("draft")) steps.report(com.carrent.inspection.InspectionPhase.BEFORE,2);
        if (java.util.Set.of("accept-before","addendum","dispute").contains(command)) steps.inspections().submitBefore(1L,f.actor(2));
        long actorId=command.equals("accept-before") || command.equals("dispute")?4:2;
        Long imageId=command.equals("delete-image")?f.jdbc.queryForObject("SELECT MIN(id) FROM inspection_images",Long.class):null;
        var encoded=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",encoded);
        var photo=new org.springframework.mock.web.MockMultipartFile("images","gate.png","image/png",encoded.toByteArray());
        long images=f.count("inspection_images"),events=f.count("inspection_events");
        var actor=f.actor(actorId);
        long notices=f.count("notifications");
        assertThatThrownBy(()->staleAfterCommit(
            ()->{f.em.find(com.carrent.user.User.class,actorId).getStatus();f.em.find(Booking.class,1L).getStatus();},
            ()->{if(change.equals("ban"))f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=?",actorId);
                else f.jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",actorId);},
            ()->{switch(command){
                case "owner-accept" -> steps.bookings().confirmBooking(1L,actor);
                case "submit-before" -> steps.inspections().submitBefore(1L,actor);
                case "accept-before" -> steps.bookings().acceptBefore(1L,actor);
                case "draft" -> steps.inspections().saveDraft(1L,com.carrent.inspection.InspectionPhase.BEFORE,new com.carrent.inspection.InspectionForm(),actor);
                case "add-image" -> steps.inspections().addImages(1L,com.carrent.inspection.InspectionPhase.BEFORE,java.util.List.of(photo),actor);
                case "delete-image" -> steps.inspections().deleteDraftImage(1L,com.carrent.inspection.InspectionPhase.BEFORE,imageId,actor);
                case "addendum" -> steps.inspections().appendBeforeAddendum(1L,"must not publish",actor);
                case "dispute" -> steps.inspections().disputeBefore(1L,"must not publish",actor);
            }})).isInstanceOf(ForbiddenException.class);
        assertThat(f.count("notifications")).isEqualTo(notices);
        assertThat(f.count("inspection_images")).isEqualTo(images);
        assertThat(f.count("inspection_events")).isEqualTo(events);
        assertThat(f.count("payment_intents")).isZero();
        assertThat(f.jdbc.queryForObject("SELECT before_accepted_at FROM bookings WHERE id=1",java.time.LocalDateTime.class)).isNull();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"open,ban","cancel,ban","open,role","cancel,role"})
    void stalePaymentPayerCannotMutateAdvanceAfterCommittedAccessChange(String command,String change) throws Exception {
        var intent=steps.agree();var actor=f.actor(4);var route=f.app.getBean(PaymentRoutes.class).intent(intent.id());
        assertThatThrownBy(()->staleAfterCommit(
            ()->{f.em.find(com.carrent.user.User.class,4L).getStatus();f.em.find(Booking.class,1L).getStatus();},
            ()->{if(change.equals("ban"))f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=4");else f.jdbc.update("UPDATE users SET role='OWNER' WHERE id=4");},
            ()->{if(command.equals("open"))f.app.getBean(PaymentIntentTransactions.class).open(route,actor);
                else f.app.getBean(PaymentIntentTransactions.class).cancel(route,intent.id(),actor);}))
            .isInstanceOf(ForbiddenException.class);
        assertThat(steps.state()).isEqualTo("PENDING");
        assertThat(f.jdbc.queryForObject("SELECT status FROM payment_intents WHERE id=?",String.class,intent.id())).isEqualTo("ACTIVE");
    }

    @Test void staleCancellationAfterCommittedHandoverCannotCreateRefund() throws Exception {
        steps.receive(steps.agree(),600000,65003);var owner=f.actor(2);var customer=f.actor(4);
        assertThatThrownBy(()->staleAfterCommit(
            ()->f.em.find(Booking.class,1L).getVehicleReceivedAt(),
            ()->steps.bookings().confirmHandover(1L,true,owner),
            ()->steps.bookings().cancelBooking(1L,customer,"stale cancellation")))
            .isInstanceOf(BusinessException.class);
        assertThat(f.count("refunds")).isZero();assertThat(steps.state()).isEqualTo("CONFIRMED");
    }

    @Test void staleReturnAfterCommittedReturnAndSettlementCannotReopenDebtOrOverwriteDispute() throws Exception {
        steps.paidAndHandedOver();steps.after();var owner=f.actor(2);
        assertThatThrownBy(()->staleAfterCommit(
            ()->f.em.find(Booking.class,1L).getVehicleReturnedAt(),
            ()->{steps.returnDisputed();try{steps.receive(steps.balance(),1400000,65004);}catch(Exception e){throw new RuntimeException(e);}},
            ()->steps.bookings().confirmReturn(1L,steps.disputed(),owner))).isInstanceOf(BusinessException.class);
        assertThat(steps.state()).isEqualTo("COMPLETED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM payment_obligations WHERE purpose='BALANCE'",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations WHERE purpose='BALANCE'",Long.class)).isEqualTo(1400000);
        assertThat(f.jdbc.queryForObject("SELECT status FROM booking_inspections WHERE phase='AFTER'",String.class)).isEqualTo("DISPUTED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM inspection_events WHERE action='DISPUTED'",Long.class)).isEqualTo(1);
    }

    @Test void notificationFailureOnOtherConnectionRollsBackReceiptAndOldSnapshotCanRetryAtomically() throws Exception {
        var intent=steps.agree();long inbox=steps.enqueue(intent,600000,65007);
        var claim=f.app.getBean(InboxWorkService.class).claim(inbox);
        f.jdbc.execute("CREATE TRIGGER gate_advance_notice BEFORE INSERT ON notifications FOR EACH ROW BEGIN IF NEW.type='BOOKING_CONFIRMED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='gate rollback'; END IF; END");
        try {
            staleAfterCommit(
                ()->f.em.find(Booking.class,1L).getStatus(),
                ()->{
                    assertThatThrownBy(()->f.tx.execute(s->{f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner());return null;})).isInstanceOf(RuntimeException.class);
                    assertThat(steps.state()).isEqualTo("PENDING");assertThat(f.count("payment_receipts")).isZero();
                    assertThat(f.jdbc.queryForObject("SELECT amount_settled FROM payment_obligations",Long.class)).isZero();
                    f.jdbc.execute("DROP TRIGGER gate_advance_notice");
                },
                ()->f.app.getBean(ReceiptApplicationService.class).apply(inbox,claim.leaseOwner()));
        } finally { f.jdbc.execute("DROP TRIGGER IF EXISTS gate_advance_notice"); }
        assertThat(steps.state()).isEqualTo("CONFIRMED");
        assertThat(f.count("payment_receipts")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_CONFIRMED'",Long.class)).isEqualTo(2);
    }

    @Test void competingBalanceReceiptsFromIndependentConnectionsCompleteOnceAndRefundOther() throws Exception {
        steps.paidAndHandedOver();steps.after();steps.returnDisputed();var intent=steps.balance();
        long first=steps.enqueue(intent,1400000,65005),second=steps.enqueue(intent,1400000,65006);
        var a=f.app.getBean(InboxWorkService.class).claim(first);var b=f.app.getBean(InboxWorkService.class).claim(second);
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        var connections=ConcurrentHashMap.<Long>newKeySet();
        try {
            var x=pool.submit(()->applyAfterSnapshot(first,a.leaseOwner(),barrier,connections));
            var y=pool.submit(()->applyAfterSnapshot(second,b.leaseOwner(),barrier,connections));
            x.get(25,TimeUnit.SECONDS);y.get(25,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertThat(connections).hasSize(2);assertThat(steps.state()).isEqualTo("COMPLETED");
        assertThat(f.jdbc.queryForObject("SELECT SUM(allocated_amount) FROM payment_receipts",Long.class)).isEqualTo(2000000);
        assertThat(f.jdbc.queryForObject("SELECT SUM(amount) FROM refunds",Long.class)).isEqualTo(1400000);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type='BOOKING_COMPLETED'",Long.class)).isEqualTo(2);
    }
    static void applyAfterSnapshot(long inbox,String lease,CyclicBarrier barrier,java.util.Set<Long> connections) {
        f.tx.execute(s->{
            assertThat(f.jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
            connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
            f.em.find(Booking.class,1L).getStatus();
            try{barrier.await(15,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
            f.app.getBean(ReceiptApplicationService.class).apply(inbox,lease);return null;
        });
    }
    static void staleAfterCommit(Runnable snapshot,Runnable competingCommit,Runnable staleCommand) {
        f.tx.execute(s->{
            assertThat(f.jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
            long connection=f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);snapshot.run();
            var pool=Executors.newSingleThreadExecutor();
            try {pool.submit(()->{
                assertThat(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)).isNotEqualTo(connection);
                competingCommit.run();
            }).get(25,TimeUnit.SECONDS);}
            catch(Exception e){throw new RuntimeException(e);}finally{pool.shutdownNow();}
            staleCommand.run();return null;
        });
    }
}
