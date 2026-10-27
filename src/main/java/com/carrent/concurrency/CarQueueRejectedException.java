package com.carrent.concurrency;

import java.util.concurrent.RejectedExecutionException;

/** Rejected before execution; it is safe for the caller to retry. */
public final class CarQueueRejectedException extends RejectedExecutionException {
    public CarQueueRejectedException(String message) {
        super(message);
    }
}
