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
        var balance = em.createQuery("select o from PaymentObligation o where o.businessKey=:key", PaymentObligation.class)
                .setParameter("key", "BOOKING:" + id + ":BALANCE").getResultStream().findFirst().orElse(null);
        boolean settled = balance != null && balance.getStatus() == PaymentObligationStatus.SETTLED
                && balance.getAmountSettled() >= balance.getAmountDue();
        Instant balancePaidAt = settled ? em.createQuery("""
                select r.creditedAt from PaymentReceipt r where r.intent.obligation.id=:id
                and r.allocatedAmount>0 order by r.creditedAt desc, r.id desc
                """, Instant.class).setParameter("id", balance.getId()).setMaxResults(1)
                .getResultStream().findFirst().orElse(null) : null;
        var refunds = em.createQuery("""
                select r from Refund r where r.receipt.intent.obligation.businessKey in :keys order by r.id
                """, Refund.class).setParameter("keys", List.of("BOOKING:" + id + ":ADVANCE", "BOOKING:" + id + ":BALANCE"))
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
