package com.carrent.payment;

import java.time.Instant;

public record RefundView(Long id, long amount, RefundReason reason, RefundStatus status,
        String bankBin, String accountNumber, String accountName, Instant readyAt, Instant dueAt,
        boolean editable, boolean overdue) { }
