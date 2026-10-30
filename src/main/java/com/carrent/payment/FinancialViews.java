package com.carrent.payment;

import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.security.CustomUserPrincipal;
import com.carrent.user.Role;
import com.carrent.user.User;
import com.carrent.user.UserStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FinancialViews {
    public record IntentRow(Long id, String status, String message) { }
    public record ObligationRow(Long id, PaymentPurpose purpose, Long subjectId, long amountDue,
            long amountSettled, PaymentObligationStatus status, IntentRow latestIntent, boolean payer) { }
    public record RefundRow(Long id, long amount, RefundReason reason, RefundStatus status, String account,
            Instant dueAt, boolean overdue, boolean recipient) { }
    public record RefundStatusView(Long id, RefundStatus status, Instant dueAt, boolean overdue) { }
    public record ReviewReceiptRow(Long id, long amount, Instant creditedAt, String message) { }
    private final EntityManager em;
    private final Clock clock;
    private final PaymentIntentTransactions intents;

    public FinancialViews(EntityManager em, Clock clock, PaymentIntentTransactions intents) {
        this.em = em; this.clock = clock; this.intents = intents;
    }

    public boolean available(String view, CustomUserPrincipal actor) {
        obligationScope(view, active(actor));
        return ((Number) em.createNativeQuery("""
                select count(*) from information_schema.tables where table_schema=database()
                and table_name in ('payment_channels','payment_obligations','payment_intents',
                'webhook_inbox','payment_receipts','refunds','payout_attempts')
                """, Long.class).getSingleResult()).intValue() == 7;
    }

    public Page<ObligationRow> obligations(String view, int page, CustomUserPrincipal actor) {
        var user = active(actor);
        String scope = obligationScope(view, user);
        var paging = PageRequest.of(Math.clamp(page, 0, 100000), 20);
        if (view.equals("refunds")) return Page.empty(paging);
        var query = em.createQuery("select o from PaymentObligation o where " + scope + " order by o.id desc", PaymentObligation.class);
        var count = em.createQuery("select count(o) from PaymentObligation o where " + scope, Long.class);
        if (!view.equals("managed")) { query.setParameter("user", user.getId()); count.setParameter("user", user.getId()); }
        var rows = query.setFirstResult((int) paging.getOffset()).setMaxResults(20).getResultList();
        var ids = rows.stream().map(PaymentObligation::getId).toList();
        var intents = ids.isEmpty() ? List.<PaymentIntent>of() : em.createQuery("""
                select i from PaymentIntent i where i.obligation.id in :ids
                and i.id=(select max(p.id) from PaymentIntent p where p.obligation=i.obligation)
                """, PaymentIntent.class).setParameter("ids", ids).getResultList();
        return new PageImpl<>(rows.stream().map(o -> new ObligationRow(o.getId(), o.getPurpose(), o.getSubjectId(),
                o.getAmountDue(), o.getAmountSettled(), o.getStatus(), intents.stream()
                .filter(i -> i.getObligation().getId().equals(o.getId())).map(i -> {
                    var display = this.intents.view(i);
                    return new IntentRow(i.getId(), display.status(), display.message());
                }).findFirst().orElse(null),
                o.getPayer().getId().equals(user.getId()))).toList(), paging, count.getSingleResult());
    }

    public Page<RefundRow> refunds(String view, int page, CustomUserPrincipal actor) {
        var user = active(actor);
        obligationScope(view, user);
        var paging = PageRequest.of(Math.clamp(page, 0, 100000), 20);
        String scope = view.equals("managed") ? "1=1" : view.equals("collected") ? collectorScope(user) : recipientScope(user);
        var query = em.createQuery("select r from Refund r where " + scope + " order by r.id desc", Refund.class);
        var count = em.createQuery("select count(r) from Refund r where " + scope, Long.class);
        if (!view.equals("managed")) { query.setParameter("user", user.getId()); count.setParameter("user", user.getId()); }
        return new PageImpl<>(query.setFirstResult((int) paging.getOffset()).setMaxResults(20).getResultList().stream()
                .map(r -> row(r, user)).toList(), paging, count.getSingleResult());
    }

    public Page<ReviewReceiptRow> reviewReceipts(String view, int page, CustomUserPrincipal actor) {
        var user = active(actor);
        obligationScope(view, user);
        var paging = PageRequest.of(Math.clamp(page, 0, 100000), 20);
        if (view.equals("refunds")) return Page.empty(paging);
        String scope = switch (view) {
            case "managed" -> "1=1";
            case "collected" -> "c.user.id=:user and c.ownerRole=c.user.role "
                    + "and c.provider=com.carrent.payment.PaymentProvider.SEPAY "
                    + "and c.purpose=com.carrent.payment.PaymentChannelPurpose.COLLECT "
                    + "and (i is null or (o.collector.id=:user and " + purposeScope("o", user.getRole(), false) + "))";
            default -> "o.payer.id=:user and " + purposeScope("o", user.getRole(), true);
        };
        String from = " from PaymentReceipt r join r.channel c left join r.intent i left join i.obligation o "
                + "where r.status=com.carrent.payment.PaymentReceiptStatus.REVIEW_REQUIRED and (" + scope + ")";
        var query = em.createQuery("select r" + from + " order by r.id desc", PaymentReceipt.class);
        var count = em.createQuery("select count(r)" + from, Long.class);
        if (!view.equals("managed")) { query.setParameter("user", user.getId()); count.setParameter("user", user.getId()); }
        return new PageImpl<>(query.setFirstResult((int) paging.getOffset()).setMaxResults(20).getResultList().stream()
                .map(r -> new ReviewReceiptRow(r.getId(), r.getAmount(), r.getCreditedAt(), r.getIntent() == null
                        ? "Chưa khớp lần thanh toán" : "Số tiền hoặc dữ liệu giao dịch cần được kiểm tra"))
                .toList(), paging, count.getSingleResult());
    }

    public RefundRow refund(Long id, CustomUserPrincipal actor) {
        var user = active(actor);
        String scope = user.getRole() == Role.ADMIN ? "1=1" : "(" + recipientScope(user) + ") or (" + collectorScope(user) + ")";
        var query = em.createQuery("select r from Refund r where r.id=:id and (" + scope + ")", Refund.class).setParameter("id", id);
        if (user.getRole() != Role.ADMIN) query.setParameter("user", user.getId());
        var refund = query.getResultStream().findFirst().orElse(null);
        if (refund == null) {
            if (em.find(Refund.class, id) == null) throw new NotFoundException("Không tìm thấy khoản hoàn tiền.");
            throw forbidden();
        }
        return row(refund, user);
    }

    public RefundStatusView status(Long id, CustomUserPrincipal actor) {
        var r = refund(id, actor);
        return new RefundStatusView(r.id(), r.status(), r.dueAt(), r.overdue());
    }

    private RefundRow row(Refund r, User user) {
        String account = r.getAccountNumber();
        return new RefundRow(r.getId(), r.getAmount(), r.getReason(), r.getStatus(),
                account == null ? null : "••••" + (account.length() > 4 ? account.substring(account.length() - 4) : ""), r.getDueAt(),
                r.getDueAt() != null && !clock.instant().isBefore(r.getDueAt()) && r.getStatus() != RefundStatus.SUCCEEDED,
                r.getRecipient().getId().equals(user.getId()));
    }

    private String obligationScope(String view, User user) {
        return switch (view) {
            case "paid", "refunds" -> "o.payer.id=:user and " + purposeScope("o", user.getRole(), true);
            case "collected" -> {
                if (user.getRole() == Role.CUSTOMER) throw forbidden();
                yield "o.collector.id=:user and " + purposeScope("o", user.getRole(), false);
            }
            case "managed" -> { if (user.getRole() != Role.ADMIN) throw forbidden(); yield "1=1"; }
            default -> throw forbidden();
        };
    }

    private String recipientScope(User user) {
        return "r.recipient.id=:user and r.receipt.intent.obligation.payer.id=:user and "
                + purposeScope("r.receipt.intent.obligation", user.getRole(), true);
    }

    private String collectorScope(User user) {
        return "r.payoutChannel.user.id=:user and r.receipt.channel.user.id=:user and r.receipt.intent.obligation.collector.id=:user and "
                + purposeScope("r.receipt.intent.obligation", user.getRole(), false);
    }

    private static String purposeScope(String o, Role role, boolean payer) {
        if (payer && role == Role.CUSTOMER || !payer && role == Role.OWNER)
            return o + ".purpose in (com.carrent.payment.PaymentPurpose.ADVANCE, com.carrent.payment.PaymentPurpose.BALANCE)";
        if (payer && role == Role.OWNER || !payer && role == Role.ADMIN)
            return o + ".purpose=com.carrent.payment.PaymentPurpose.SUBSCRIPTION";
        return "1=0";
    }

    private User active(CustomUserPrincipal actor) {
        if (actor == null) throw forbidden();
        var user = em.find(User.class, actor.getId());
        if (user != null) em.refresh(user);
        if (user == null || user.getStatus() != UserStatus.ACTIVE || user.getRole() != actor.getRole()) throw forbidden();
        return user;
    }
    private static ForbiddenException forbidden() { return new ForbiddenException("Bạn không có quyền xem dữ liệu tài chính này."); }
}
