package com.carrent.admin;

import com.carrent.booking.Booking;
import com.carrent.booking.BookingStatus;
import com.carrent.security.CustomUserPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminBookingService {
    public enum Attention { ALL, BALANCE_OVERDUE, REFUND_OVERDUE }
    private final EntityManager em;
    private final AdminService admins;
    private final Clock clock;

    public AdminBookingService(EntityManager em, AdminService admins, Clock clock) {
        this.em=em; this.admins=admins; this.clock=clock;
    }

    public Page<Booking> list(BookingStatus status, Attention attention, int number, CustomUserPrincipal actor) {
        admins.requireAdmin(actor);
        var page=PageRequest.of(Math.clamp(number,0,100000),20);
        if (attention!=Attention.ALL && !admins.available(actor)) return Page.empty(page);
        String from=" FROM bookings booking WHERE 1=1";
        if (status!=null) from+=" AND booking.status=:status";
        from+=switch(attention) {
            case ALL -> "";
            case BALANCE_OVERDUE -> " AND EXISTS (SELECT 1 FROM payment_obligations o WHERE o.subject_id=booking.id AND "
                    + AdminService.BALANCE_OVERDUE_SQL + ")";
            case REFUND_OVERDUE -> " AND EXISTS (SELECT 1 " + AdminFinanceService.bookingFrom(AdminFinanceService.Kind.REFUNDS)
                    + " WHERE b.id=booking.id AND rf.status<>'SUCCEEDED' AND rf.due_at<=:now)";
        };
        var count=em.createNativeQuery("SELECT COUNT(*)"+from);
        var query=em.createNativeQuery("SELECT booking.id"+from+" ORDER BY booking.id DESC");
        for(var q:List.of(count,query)) {
            if(status!=null) q.setParameter("status",status.name());
            if(attention!=Attention.ALL) q.setParameter("now",LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        }
        long total=((Number)count.getSingleResult()).longValue();
        @SuppressWarnings("unchecked")
        List<Number> ids=query.setFirstResult((int)page.getOffset()).setMaxResults(20).getResultList();
        if(ids.isEmpty()) return new PageImpl<>(List.of(),page,total);
        var rows=em.createQuery("""
                select b from Booking b join fetch b.customer join fetch b.car car join fetch car.owner
                where b.id in :ids order by b.id desc
                """,Booking.class).setParameter("ids",ids.stream().map(Number::longValue).toList()).getResultList();
        return new PageImpl<>(rows,page,total);
    }
}
