package com.carrent.booking;

import com.carrent.inspection.BookingInspectionRepository;
import com.carrent.inspection.InspectionPhase;
import com.carrent.inspection.InspectionStatus;
import com.carrent.payment.*;
import com.carrent.security.CustomUserPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookingTimeline {
    public record Milestone(String key, String label, Instant at) { }
    public record RefundRow(Long id, long amount, RefundStatus status) { }
    public record View(List<Milestone> milestones, List<RefundRow> refunds,
                       boolean beforeSubmitted, boolean afterSubmitted, String afterStatus,
                       boolean balanceSettled) { }
    private final BookingService bookings;
    private final BookingInspectionRepository inspections;
    private final EntityManager em;

    public BookingTimeline(BookingService bookings, BookingInspectionRepository inspections, EntityManager em) {
        this.bookings = bookings; this.inspections = inspections; this.em = em;
    }

    public View view(Long id, CustomUserPrincipal actor) {
        var b = bookings.getBookingForView(id, actor);
        if (!"VIETQR_V1".equals(b.getWorkflowVersion())) return null;
        var before = inspections.findByBookingIdAndPhase(id, InspectionPhase.BEFORE)
                .filter(r -> r.getStatus() != InspectionStatus.DRAFT).orElse(null);
        var after = inspections.findByBookingIdAndPhase(id, InspectionPhase.AFTER)
                .filter(r -> r.getStatus() != InspectionStatus.DRAFT).orElse(null);
        var obligations = em.createQuery("""
                select o from PaymentObligation o where o.subjectType=com.carrent.payment.PaymentSubjectType.BOOKING
                and o.subjectId=:id and o.payer.id=:payer and o.collector.id=:collector
                and ((o.businessKey=:advanceKey and o.purpose=com.carrent.payment.PaymentPurpose.ADVANCE and o.amountDue=:advance)
                or (o.businessKey=:balanceKey and o.purpose=com.carrent.payment.PaymentPurpose.BALANCE and o.amountDue=:balance))
                """, PaymentObligation.class).setParameter("id",id).setParameter("payer",b.getCustomer().getId())
                .setParameter("collector",b.getCar().getOwner().getId()).setParameter("advanceKey","BOOKING:"+id+":ADVANCE")
                .setParameter("balanceKey","BOOKING:"+id+":BALANCE").setParameter("advance",b.getAdvanceAmount())
                .setParameter("balance",b.getBalanceAmount()).getResultList();
        var balance = obligations.stream().filter(o -> o.getPurpose()==PaymentPurpose.BALANCE).findFirst().orElse(null);
        boolean settled = balance != null && balance.getStatus() == PaymentObligationStatus.SETTLED
                && balance.getAmountSettled() >= balance.getAmountDue();
        Instant balancePaidAt = settled ? em.createQuery("""
                select r.creditedAt from PaymentReceipt r where r.intent.obligation.id=:id
                and r.channel.id=r.intent.channel.id and r.channel.user.id=:collector
                and r.channel.ownerRole=com.carrent.user.Role.OWNER and r.channel.provider='SEPAY'
                and r.channel.purpose=com.carrent.payment.PaymentChannelPurpose.COLLECT
                and r.allocatedAmount>0 order by r.creditedAt desc, r.id desc
                """, Instant.class).setParameter("id", balance.getId()).setMaxResults(1)
                .setParameter("collector",b.getCar().getOwner().getId())
                .getResultStream().findFirst().orElse(null) : null;
        var refunds = obligations.isEmpty() ? List.<RefundRow>of() : em.createQuery("""
                select r from Refund r where r.receipt.intent.obligation.id in :ids
                and r.recipient.id=:payer and r.receipt.channel.id=r.receipt.intent.channel.id
                and r.receipt.channel.user.id=:collector and r.receipt.channel.ownerRole=com.carrent.user.Role.OWNER
                and r.receipt.channel.provider='SEPAY' and r.receipt.channel.purpose=com.carrent.payment.PaymentChannelPurpose.COLLECT
                and r.payoutChannel.user.id=:collector and r.payoutChannel.ownerRole=com.carrent.user.Role.OWNER
                and r.payoutChannel.provider='PAYOS' and r.payoutChannel.purpose=com.carrent.payment.PaymentChannelPurpose.PAYOUT
                order by r.id
                """, Refund.class).setParameter("ids",obligations.stream().map(PaymentObligation::getId).toList())
                .setParameter("payer",b.getCustomer().getId()).setParameter("collector",b.getCar().getOwner().getId())
                .getResultList().stream().map(r -> new RefundRow(r.getId(), r.getAmount(), r.getStatus())).toList();
        return new View(List.of(
                new Milestone("owner-accepted", "Chủ xe chấp nhận", b.getOwnerAcceptedAt()),
                new Milestone("before-agreed", "Hai bên đồng ý BEFORE", b.getBeforeAcceptedAt()),
                new Milestone("advance-paid", "Đã nhận trả trước 30%", b.getAdvancePaidAt()),
                new Milestone("contract-confirmed", "Chủ xe xác nhận hợp đồng", b.getContractConfirmedAt()),
                new Milestone("handover", "Giao xe thực tế", b.getVehicleReceivedAt()),
                new Milestone("after-submitted", "AFTER đã gửi", after == null ? null : after.getSubmittedAt()),
                new Milestone("returned", "Nhận lại xe thực tế", b.getVehicleReturnedAt()),
                new Milestone("balance-paid", "Đã nhận thanh toán 70%", balancePaidAt)),
                refunds, before != null, after != null, after == null ? null : switch (after.getStatus()) {
                    case SUBMITTED -> "Chờ chủ xe xác nhận";
                    case ACCEPTED -> "Hai bên đồng ý AFTER";
                    case DISPUTED -> "Có bất đồng AFTER; bằng chứng được giữ nguyên";
                    case RESOLVED -> "Bất đồng đã được xử lý";
                    case DRAFT -> throw new IllegalStateException("Draft excluded");
                }, settled);
    }
}
