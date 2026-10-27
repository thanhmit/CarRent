package com.carrent.concurrency;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes changes to each car; callers must invoke transactional services inside the action. */
public final class CarCommandExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CarCommandExecutor.class);
    private final ConcurrentHashMap<Long, Lane> lanes = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final CarQueueProperties properties;
    private final AtomicInteger waitingTotal = new AtomicInteger();
    private final LongSupplier nanoTime;
    private final long waitNanos;
    private final Object admission = new Object();
    private final ThreadLocal<Boolean> executing = new ThreadLocal<>();
    private volatile boolean accepting = true;

    public CarCommandExecutor(CarQueueProperties properties) {
        this(properties, System::nanoTime, newPool(properties));
    }

    CarCommandExecutor(CarQueueProperties properties, LongSupplier nanoTime, ExecutorService workers) {
        this.properties = properties;
        this.nanoTime = nanoTime;
        this.workers = workers;
        waitNanos = properties.waitTimeout().toNanos();
    }

    private static ExecutorService newPool(CarQueueProperties properties) {
        return new ThreadPoolExecutor(properties.workers(), properties.workers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.maxWaitingTotal()),
                Thread.ofPlatform().name("car-command-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> T execute(Long carId, Supplier<T> action) {
        if (carId == null || carId <= 0) {
            throw new IllegalArgumentException("carId must be positive");
        }
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Enqueue before opening a transaction");
        }
        if (Boolean.TRUE.equals(executing.get())) {
            throw new IllegalStateException("Nested execution on the same car executor is not allowed");
        }
        var task = new Command<>(action, nanoTime.getAsLong());
        synchronized (admission) {
            if (!accepting) {
                throw new CarQueueRejectedException("Bộ xử lý đã dừng nhận yêu cầu. Vui lòng thử lại.");
            }
            lanes.compute(carId, (id, current) -> {
                Lane lane = current == null ? new Lane() : current;
                if (lane.waiting.size() >= properties.maxWaitingPerCar()) {
                    throw new CarQueueRejectedException("Xe đang có quá nhiều yêu cầu chờ. Vui lòng thử lại.");
                }
                reserveWaitingSlot();
                lane.waiting.addLast(task);
                if (current == null) {
                    try {
                        workers.execute(() -> drain(id, lane));
                    } catch (RejectedExecutionException ex) {
                        lane.waiting.remove(task);
                        waitingTotal.decrementAndGet();
                        throw new CarQueueRejectedException("Bộ xử lý đang bận hoặc đã dừng. Vui lòng thử lại.");
                    }
                }
                return lane;
            });
        }
        return awaitResult(carId, task);
    }

    private <T> T awaitResult(Long carId, Command<T> task) {
        boolean interrupted = false;
        try {
            long remaining = waitNanos - (nanoTime.getAsLong() - task.queuedAt);
            if (remaining > 0) {
                try {
                    return task.result.get(remaining, TimeUnit.NANOSECONDS).valueOrThrow();
                } catch (TimeoutException ex) {
                    // Cancel only if the worker has not started the action.
                } catch (InterruptedException ex) {
                    interrupted = true;
                } catch (ExecutionException ex) {
                    throw propagate(ex.getCause());
                }
            }
            cancelBeforeStart(carId, task, interrupted
                    ? "Yêu cầu bị gián đoạn trước khi xử lý. Vui lòng thử lại."
                    : "Yêu cầu đã hết thời gian chờ xử lý. Vui lòng thử lại.");
            // A running action can still commit; never report cancellation to its caller.
            return task.result.join().valueOrThrow();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException failure) {
            return failure;
        }
        if (cause instanceof Error failure) {
            throw failure;
        }
        return new CompletionException(cause);
    }

    private void cancelBeforeStart(Long carId, Command<?> task, String reason) {
        lanes.computeIfPresent(carId, (id, lane) -> {
            if (task.state == State.QUEUED && lane.waiting.remove(task)) {
                waitingTotal.decrementAndGet();
                task.cancel(reason);
            }
            return lane.waiting.isEmpty() && lane.active == null ? null : lane;
        });
    }

    private void reserveWaitingSlot() {
        int count;
        do {
            count = waitingTotal.get();
            if (count >= properties.maxWaitingTotal()) {
                throw new CarQueueRejectedException("Hệ thống đang có quá nhiều yêu cầu chờ. Vui lòng thử lại.");
            }
        } while (!waitingTotal.compareAndSet(count, count + 1));
    }

    private void drain(Long carId, Lane lane) {
        while (true) {
            var next = new AtomicReference<Command<?>>();
            lanes.compute(carId, (id, current) -> {
                if (current != lane) {
                    return current;
                }
                while (!lane.waiting.isEmpty()) {
                    var task = lane.waiting.removeFirst();
                    waitingTotal.decrementAndGet();
                    if (!accepting) {
                        task.cancel("Ứng dụng đang dừng; yêu cầu chưa được thực hiện. Vui lòng thử lại.");
                        continue;
                    }
                    if (nanoTime.getAsLong() - task.queuedAt >= waitNanos) {
                        task.cancel("Yêu cầu đã hết thời gian chờ xử lý. Vui lòng thử lại.");
                        continue;
                    }
                    task.state = State.RUNNING;
                    lane.active = task;
                    next.set(task);
                    return lane;
                }
                return null;
            });
            if (next.get() == null) {
                return;
            }
            executing.set(true);
            try {
                next.get().run();
            } finally {
                executing.remove();
            }
            lanes.computeIfPresent(carId, (id, current) -> {
                if (current != lane) {
                    return current;
                }
                lane.active.state = State.DONE;
                lane.active = null;
                return lane.waiting.isEmpty() ? null : lane;
            });
        }
    }

    @Override
    public void close() {
        if (Boolean.TRUE.equals(executing.get())) {
            throw new IllegalStateException("Cannot close the car executor from its own action");
        }
        synchronized (admission) {
            accepting = false;
            lanes.forEach((carId, ignored) -> lanes.computeIfPresent(carId, (id, lane) -> {
                while (!lane.waiting.isEmpty()) {
                    var task = lane.waiting.removeFirst();
                    waitingTotal.decrementAndGet();
                    task.cancel("Ứng dụng đang dừng; yêu cầu chưa được thực hiện. Vui lòng thử lại.");
                }
                return lane.active == null ? null : lane;
            }));
            workers.shutdown();
        }
        try {
            if (!workers.awaitTermination(properties.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                log.warn("Car queue shutdown grace elapsed; running actions still own their results and are not interrupted");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while awaiting car queue shutdown; running actions are not cancelled");
        }
    }

    private static final class Lane {
        private final Deque<Command<?>> waiting = new ArrayDeque<>();
        private Command<?> active;
    }

    private enum State { QUEUED, RUNNING, DONE, CANCELLED_BEFORE_START }

    // CompletableFuture.get unwraps CompletionException; carry failures as data to preserve identity.
    private record Outcome<T>(T value, Throwable failure) {
        private T valueOrThrow() {
            if (failure != null) {
                throw propagate(failure);
            }
            return value;
        }
    }

    private static final class Command<T> {
        private final Supplier<T> action;
        private final CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
        private final long queuedAt;
        private State state = State.QUEUED;

        private Command(Supplier<T> action, long queuedAt) {
            this.action = action;
            this.queuedAt = queuedAt;
        }

        private void cancel(String reason) {
            state = State.CANCELLED_BEFORE_START;
            result.complete(new Outcome<>(null, new CarQueueRejectedException(reason)));
        }

        private void run() {
            try {
                result.complete(new Outcome<>(action.get(), null));
            } catch (Throwable ex) {
                result.complete(new Outcome<>(null, ex));
            }
        }
    }
}
