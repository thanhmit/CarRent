package com.carrent.admin;

import com.carrent.common.exception.NotFoundException;
import com.carrent.payment.*;
import com.carrent.security.CustomUserPrincipal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AdminFinanceService {
    public enum Kind { OBLIGATIONS, RECEIPTS, REFUNDS }
    public enum Order { LATEST, OLDEST, AMOUNT }
    public record Filter(Kind kind, String status, boolean overdue, PaymentSubjectType subjectType,
            Long subjectId, Long collectorId, Order sort, int page) { }
    public record Row(long id, String status, String message, PaymentPurpose purpose,
            PaymentSubjectType subjectType, Long subjectId, Long payerId, long collectorId,
            String collectorRole, long amount, long allocated, Instant occurredAt, Instant dueAt,
            boolean overdue, String account) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminService admins;
    private final Clock clock;

    public AdminFinanceService(NamedParameterJdbcTemplate jdbc, AdminService admins, Clock clock) {
        this.jdbc = jdbc; this.admins = admins; this.clock = clock;
    }

    public Page<Row> list(Filter filter, CustomUserPrincipal actor) {
        admins.requireAdmin(actor);
        validate(filter);
        var page = PageRequest.of(Math.clamp(filter.page(), 0, 100000), 20);
        var params = parameters(filter);
        String from = " FROM (" + source(filter.kind()) + ") q WHERE 1=1";
        if (!filter.status().isEmpty()) from += " AND q.status=:status";
        if (filter.overdue()) from += " AND q.overdue=1";
        if (filter.subjectType() != null) from += " AND q.subject_type=:subjectType";
        if (filter.subjectId() != null) from += " AND q.subject_id=:subjectId";
        if (filter.collectorId() != null) from += " AND q.collector_id=:collectorId";
        String sort = switch (filter.sort()) {
            case LATEST -> "q.occurred_at DESC,q.id DESC";
            case OLDEST -> "q.occurred_at ASC,q.id ASC";
            case AMOUNT -> "q.amount DESC,q.id DESC";
        };
        params.put("offset", page.getOffset());
        var total = jdbc.queryForObject("SELECT COUNT(*)" + from, params, Long.class);
        var rows = jdbc.query("SELECT q.*" + from + " ORDER BY " + sort + " LIMIT 20 OFFSET :offset", params,
                mapper(filter.kind()));
        return new PageImpl<>(rows, page, total);
    }

    private org.springframework.jdbc.core.RowMapper<Row> mapper(Kind kind) {
        return (rs, n) -> {
                    String status = rs.getString("status");
                    String purpose = rs.getString("purpose");
                    String subject = rs.getString("subject_type");
                    String account = rs.getString("account");
                    return new Row(rs.getLong("id"), status, message(kind, status),
                            purpose == null ? null : PaymentPurpose.valueOf(purpose),
                            subject == null ? null : PaymentSubjectType.valueOf(subject),
                            rs.getObject("subject_id", Long.class), rs.getObject("payer_id", Long.class),
                            rs.getLong("collector_id"), rs.getString("collector_role"), rs.getLong("amount"),
                            rs.getLong("allocated"), instant(rs.getObject("occurred_at", LocalDateTime.class)),
                            instant(rs.getObject("due_at", LocalDateTime.class)), rs.getBoolean("overdue"),
                            account == null ? null : "••••" + (account.length() > 4 ? account.substring(account.length()-4) : ""));
                };
    }

    public Row detail(Kind kind, long id, CustomUserPrincipal actor) {
        admins.requireAdmin(actor);
        // The same bounded, masked projection serves lists and details; never expose provider payloads.
        var filter = new Filter(kind, "", false, null, null, null, Order.LATEST, 0);
        var params = parameters(filter);
        params.put("id", id);
        var rows = jdbc.query("SELECT q.* FROM (" + source(kind) + ") q WHERE q.id=:id", params, mapper(kind));
        if (rows.isEmpty()) throw new NotFoundException("Không tìm thấy khoản tài chính.");
        return rows.getFirst();
    }

    public List<String> statuses(Kind kind) {
        return switch (kind) {
            case OBLIGATIONS -> Arrays.stream(PaymentObligationStatus.values()).map(Enum::name).toList();
            case RECEIPTS -> Arrays.stream(PaymentReceiptStatus.values()).map(Enum::name).toList();
            case REFUNDS -> Arrays.stream(RefundStatus.values()).map(Enum::name).toList();
        };
    }

    public String statusMessage(Kind kind, String status) { return message(kind, status); }

    public Map<Kind,List<Row>> booking(long bookingId, CustomUserPrincipal actor) {
        admins.requireAdmin(actor);
        var result=new EnumMap<Kind,List<Row>>(Kind.class);
        var params=parameters(new Filter(Kind.OBLIGATIONS,"",false,null,null,null,Order.LATEST,0));
        params.put("booking",bookingId);
        for(var kind:Kind.values()) {
            String id=switch(kind) { case OBLIGATIONS -> "o.id"; case RECEIPTS -> "pr.id"; case REFUNDS -> "rf.id"; };
            result.put(kind,jdbc.query("SELECT q.* FROM ("+source(kind)+") q WHERE q.id IN (SELECT "
                    +id+" "+bookingFrom(kind)+" WHERE b.id=:booking) ORDER BY q.id",params,mapper(kind)));
        }
        return result;
    }

    static String bookingFrom(Kind kind) {
        String from="""
                FROM bookings b JOIN cars car ON car.id=b.car_id
                JOIN payment_obligations o ON o.subject_type='BOOKING' AND o.subject_id=b.id
                AND o.payer_user_id=b.customer_id AND o.collector_user_id=car.owner_id
                AND o.purpose IN ('ADVANCE','BALANCE') AND o.business_key=CONCAT('BOOKING:',b.id,':',o.purpose)
                AND o.amount_due=CASE o.purpose WHEN 'ADVANCE' THEN b.advance_amount ELSE b.balance_amount END
                AND b.workflow_version='VIETQR_V1'
                """;
        if(kind!=Kind.OBLIGATIONS) from+="""
                JOIN payment_intents i ON i.obligation_id=o.id
                JOIN payment_receipts pr ON pr.intent_id=i.id AND pr.channel_id=i.channel_id
                JOIN payment_channels collect ON collect.id=pr.channel_id AND collect.user_id=o.collector_user_id
                AND collect.owner_role='OWNER' AND collect.provider='SEPAY' AND collect.purpose='COLLECT'
                """;
        if(kind==Kind.REFUNDS) from+="""
                JOIN refunds rf ON rf.receipt_id=pr.id AND rf.recipient_user_id=o.payer_user_id
                JOIN payment_channels payout ON payout.id=rf.payout_channel_id AND payout.user_id=o.collector_user_id
                AND payout.owner_role='OWNER' AND payout.provider='PAYOS' AND payout.purpose='PAYOUT'
                """;
        return from;
    }

    private void validate(Filter f) {
        if (!f.status().isEmpty() && !statuses(f.kind()).contains(f.status())
                || f.subjectId()!=null && f.subjectId()<=0 || f.collectorId()!=null && f.collectorId()<=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Điều kiện lọc không hợp lệ.");
    }

    private Map<String,Object> parameters(Filter f) {
        var params = new HashMap<String,Object>();
        params.put("now", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        params.put("status", f.status()); params.put("subjectType", f.subjectType()==null?null:f.subjectType().name());
        params.put("subjectId", f.subjectId()); params.put("collectorId", f.collectorId());
        return params;
    }

    private static Instant instant(LocalDateTime time) { return time==null?null:time.toInstant(ZoneOffset.UTC); }

    private static String message(Kind kind, String status) {
        return switch (kind) {
            case OBLIGATIONS -> PaymentObligationStatus.valueOf(status).message();
            case REFUNDS -> RefundStatus.valueOf(status).message();
            case RECEIPTS -> switch (PaymentReceiptStatus.valueOf(status)) {
                case APPLIED -> "Đã phân bổ thanh toán";
                case REFUND_REQUIRED -> "Cần hoàn tiền";
                case REVIEW_REQUIRED -> "Cần đối soát";
            };
        };
    }

    private static String source(Kind kind) {
        return switch (kind) {
            case OBLIGATIONS -> """
                SELECT o.id,o.status,o.purpose,o.subject_type,o.subject_id,o.payer_user_id payer_id,
                o.collector_user_id collector_id,u.role collector_role,o.amount_due amount,o.amount_settled allocated,
                o.created_at occurred_at,o.due_at,NULL account,
                (%s) overdue
                FROM payment_obligations o JOIN users u ON u.id=o.collector_user_id
                """.formatted(AdminService.BALANCE_OVERDUE_SQL);
            case RECEIPTS -> """
                SELECT r.id,r.status,o.purpose,o.subject_type,o.subject_id,o.payer_user_id payer_id,
                c.user_id collector_id,c.owner_role collector_role,r.amount,r.allocated_amount allocated,
                r.credited_at occurred_at,NULL due_at,NULL account,0 overdue
                FROM payment_receipts r JOIN payment_channels c ON c.id=r.channel_id
                LEFT JOIN payment_intents i ON i.id=r.intent_id LEFT JOIN payment_obligations o ON o.id=i.obligation_id
                """;
            case REFUNDS -> """
                SELECT r.id,r.status,o.purpose,o.subject_type,o.subject_id,r.recipient_user_id payer_id,
                c.user_id collector_id,c.owner_role collector_role,r.amount,0 allocated,
                r.created_at occurred_at,r.due_at,r.account_number account,
                (r.status<>'SUCCEEDED' AND r.due_at<=:now) overdue
                FROM refunds r JOIN payment_channels c ON c.id=r.payout_channel_id
                JOIN payment_receipts pr ON pr.id=r.receipt_id LEFT JOIN payment_intents i ON i.id=pr.intent_id
                LEFT JOIN payment_obligations o ON o.id=i.obligation_id
                """;
        };
    }
}
