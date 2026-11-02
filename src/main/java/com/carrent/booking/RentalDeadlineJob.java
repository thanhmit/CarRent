package com.carrent.booking;

import com.carrent.concurrency.CarCommandExecutor;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RentalDeadlineJob {
    private static final Logger log = LoggerFactory.getLogger(RentalDeadlineJob.class);
    private final BookingRepository bookings;
    private final BookingService service;
    private final CarCommandExecutor commands;
    private final Clock clock;
    private final Executor executor;
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private long cursor;
    private long balanceCursor;
    private final Set<Long> balanceQueued = ConcurrentHashMap.newKeySet();
    private final BalanceDeadlineService balanceService;

    public RentalDeadlineJob(BookingRepository bookings, BookingService service, CarCommandExecutor commands,
            Clock clock, @Qualifier("paymentDomainExecutor") Executor executor, BalanceDeadlineService balanceService) {
        this.balanceService = balanceService;
        this.bookings = bookings;
        this.service = service;
        this.commands = commands;
        this.clock = clock;
        this.executor = executor;
    }

    public synchronized void scan() {
        try {
            var due = bookings.findAfterReturnReminderCandidates(cursor,
                    LocalDateTime.ofInstant(clock.instant().minusSeconds(3600), ZoneOffset.UTC), 50);
            // Rotate beyond failing rows; the durable marker remains the source of completed work.
            cursor = due.isEmpty() ? 0 : ((Number) due.getLast()[0]).longValue();
            for (var row : due) submit(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), false);
        } catch (RuntimeException ex) {
            log.warn("Rental deadline discovery deferred");
        }
        try {
            var due = bookings.findBalanceOverdueCandidates(balanceCursor,
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), 50);
            balanceCursor = due.isEmpty() ? 0 : ((Number) due.getLast()[0]).longValue();
            for (var row : due) submit(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), true);
        } catch (RuntimeException ex) {
            log.warn("Balance deadline discovery deferred");
        }
    }

    private void submit(Long bookingId, Long carId, boolean balance) {
        var queue = balance ? balanceQueued : queued;
        if (!queue.add(bookingId)) return;
        try {
            executor.execute(() -> {
                try {
                    commands.execute(carId, () -> {
                        if (balance) balanceService.remind(bookingId);
                        else service.remindAfterReturn(bookingId);
                        return null;
                    });
                } catch (RuntimeException ex) {
                    log.warn("Rental deadline work deferred: booking={}", bookingId);
                } finally {
                    queue.remove(bookingId);
                }
            });
        } catch (RejectedExecutionException ex) {
            queue.remove(bookingId);
            log.warn("Rental deadline queue full: booking={}", bookingId);
        }
    }
}
