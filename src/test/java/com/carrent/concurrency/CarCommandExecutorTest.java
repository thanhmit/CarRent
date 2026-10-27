package com.carrent.concurrency;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarCommandExecutorTest {
    @Test
    void interruptingAQueuedCallerCancelsOnlyItsActionAndReturnsTheWaitingSlot() throws Exception {
        var properties = new CarQueueProperties(1, 1, 1, Duration.ofSeconds(10), Duration.ofSeconds(2));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelledRuns = new AtomicInteger();
        var restored = new AtomicBoolean();
        try (var queue = new CarCommandExecutor(properties)) {
            var first = call(() -> queue.execute(1L, () -> {
                entered.countDown();
                await(release);
                return "running";
            }));
            try {
                await(entered);
                var cancelled = call(() -> {
                    try {
                        return queue.execute(1L, cancelledRuns::incrementAndGet);
                    } finally {
                        restored.set(Thread.currentThread().isInterrupted());
                    }
                });
                cancelled.awaitWaiting();
                cancelled.thread.interrupt();
                assertThatThrownBy(cancelled::get).hasCauseInstanceOf(CarQueueRejectedException.class);
                assertThat(restored).isTrue();
                var replacement = call(() -> queue.execute(1L, () -> "replacement"));
                replacement.awaitWaiting();
                release.countDown();
                first.get();
                assertThat(replacement.get()).isEqualTo("replacement");
                assertThat(cancelledRuns).hasValue(0);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void shutdownGraceCanExpireWithoutCancellingTheRunningResult() throws Exception {
        var properties = new CarQueueProperties(1, 1, 1, Duration.ofSeconds(10), Duration.ofMillis(30));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queue = new CarCommandExecutor(properties);
        var first = call(() -> queue.execute(1L, () -> {
            entered.countDown();
            await(release);
            return "finished after grace";
        }));
        try {
            await(entered);
            queue.close();
            assertThat(first.result).isNotDone();
            release.countDown();
            assertThat(first.get()).isEqualTo("finished after grace");
        } finally {
            release.countDown();
            queue.close();
        }
    }

    @Test
    void interruptingShutdownPreservesTheInterruptWithoutCancellingTheRunningAction() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queue = new CarCommandExecutor(settings(Duration.ofSeconds(10)));
        var first = call(() -> queue.execute(1L, () -> {
            entered.countDown();
            await(release);
            return "finished";
        }));
        try {
            await(entered);
            assertThat(call(() -> {
                Thread.currentThread().interrupt();
                queue.close();
                return Thread.currentThread().isInterrupted();
            }).get()).isTrue();
            assertThat(first.result).isNotDone();
            release.countDown();
            assertThat(first.get()).isEqualTo("finished");
        } finally {
            release.countDown();
            queue.close();
        }
    }

    @Test
    void actionFailureIsPreservedEvenWhenItIsAlreadyACompletionException() {
        var failure = new CompletionException(new IllegalStateException("domain failure"));
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            assertThatThrownBy(() -> queue.execute(1L, () -> { throw failure; })).isSameAs(failure);
            var fatal = new AssertionError("action failed");
            assertThatThrownBy(() -> queue.execute(1L, () -> { throw fatal; })).isSameAs(fatal);
            assertThat(queue.execute(1L, () -> "after failure")).isEqualTo("after failure");
        }
    }

    @Test
    void serverConfigurationBindsOverridesAndRetainsDefaultsForOtherLimits() {
        var source = new MapConfigurationPropertySource(Map.of(
                "carrent.car-queue.workers", "2",
                "carrent.car-queue.wait-timeout", "3s"));
        var settings = new Binder(source).bind("carrent.car-queue", Bindable.of(CarQueueProperties.class)).get();
        assertThat(settings.workers()).isEqualTo(2);
        assertThat(settings.maxWaitingPerCar()).isEqualTo(50);
        assertThat(settings.maxWaitingTotal()).isEqualTo(500);
        assertThat(settings.waitTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.shutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void activeTransactionIsRejectedBeforeEnqueueing() {
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            var executed = new AtomicInteger();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> queue.execute(1L, executed::incrementAndGet))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                TransactionSynchronizationManager.clear();
            }
            assertThat(executed).hasValue(0);
            assertThat(queue.execute(1L, () -> "healthy")).isEqualTo("healthy");
        }
    }

    @Test
    void reentryIsRejectedEvenWhenTheNestedCommandTargetsAnotherCar() {
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            var executed = new AtomicInteger();
            assertThatThrownBy(() -> queue.execute(1L, () -> queue.execute(2L, executed::incrementAndGet)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(executed).hasValue(0);
            assertThat(queue.execute(1L, () -> "healthy")).isEqualTo("healthy");
        }
    }

    @Test
    void shutdownRejectsQueuedAndNewCommandsButLetsTheRunningActionFinish() throws Exception {
        var queue = new CarCommandExecutor(settings(Duration.ofSeconds(10)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelledRuns = new AtomicInteger();
        var first = call(() -> queue.execute(1L, () -> {
            entered.countDown();
            await(release);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return "finished";
        }));
        try {
            await(entered);
            var sameCar = call(() -> queue.execute(1L, cancelledRuns::incrementAndGet));
            sameCar.awaitWaiting();
            var otherCar = call(() -> queue.execute(2L, cancelledRuns::incrementAndGet));
            otherCar.awaitWaiting();
            var closing = call(() -> {
                queue.close();
                return "closed";
            });
            assertThatThrownBy(sameCar::get).hasCauseInstanceOf(CarQueueRejectedException.class);
            assertThatThrownBy(otherCar::get).hasCauseInstanceOf(CarQueueRejectedException.class);
            assertThatThrownBy(() -> queue.execute(3L, cancelledRuns::incrementAndGet))
                    .isInstanceOf(CarQueueRejectedException.class);
            assertThat(first.result).isNotDone();
            assertThat(closing.result).isNotDone();
            release.countDown();
            assertThat(first.get()).isEqualTo("finished");
            assertThat(closing.get()).isEqualTo("closed");
            assertThat(cancelledRuns).hasValue(0);
        } finally {
            release.countDown();
            queue.close();
        }
    }

    @Test
    void rejectedWorkerSubmissionReturnsItsQuotaAndDoesNotLeaveADeadLane() {
        var submissions = new AtomicInteger();
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1)) {
            @Override
            public void execute(Runnable command) {
                if (submissions.getAndIncrement() == 0) {
                    throw new RejectedExecutionException("injected rejection");
                }
                super.execute(command);
            }
        };
        var properties = new CarQueueProperties(1, 1, 1, Duration.ofSeconds(10), Duration.ofSeconds(2));
        try (var queue = new CarCommandExecutor(properties, System::nanoTime, pool)) {
            var rejectedRuns = new AtomicInteger();
            assertThatThrownBy(() -> queue.execute(1L, rejectedRuns::incrementAndGet))
                    .isInstanceOf(CarQueueRejectedException.class);
            assertThat(queue.execute(1L, () -> "recovered")).isEqualTo("recovered");
            assertThat(rejectedRuns).hasValue(0);
        }
    }

    @Test
    void enqueueRacingWithLaneCleanupNeverCreatesTwoActiveActionsForOneCar() throws Exception {
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var completed = new AtomicInteger();
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            for (int round = 0; round < 200; round++) {
                var boundary = new CyclicBarrier(2);
                var first = call(() -> queue.execute(1L, () -> {
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        cross(boundary);
                        return completed.incrementAndGet();
                    } finally {
                        active.decrementAndGet();
                    }
                }));
                var second = call(() -> {
                    cross(boundary);
                    return queue.execute(1L, () -> {
                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try {
                            return completed.incrementAndGet();
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                });
                first.get();
                second.get();
            }
            assertThat(completed).hasValue(400);
            assertThat(maximum).hasValue(1);
            assertThat(active).hasValue(0);
        }
    }

    @Test
    void invalidConfigurationIsRejectedBeforeAnyWorkerIsCreated() {
        for (int[] limits : new int[][]{{0, 1, 1}, {1, 0, 1}, {1, 1, 0}, {-1, 1, 1}}) {
            assertThatThrownBy(() -> new CarQueueProperties(limits[0], limits[1], limits[2],
                    Duration.ofSeconds(10), Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration timeout : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), null,
                Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThatThrownBy(() -> new CarQueueProperties(1, 1, 1, timeout, Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CarQueueProperties(1, 1, 1, Duration.ofSeconds(10), timeout))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void cross(CyclicBarrier barrier) {
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void queuedCommandTimesOutAndNeverRunsAfterTheWorkerIsReleased() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lateRuns = new AtomicInteger();
        try (var queue = new CarCommandExecutor(settings(Duration.ofMillis(50)))) {
            var first = call(() -> queue.execute(1L, () -> {
                entered.countDown();
                await(release);
                return "running";
            }));
            try {
                await(entered);
                assertThatThrownBy(() -> queue.execute(1L, lateRuns::incrementAndGet))
                        .isInstanceOf(CarQueueRejectedException.class);
                release.countDown();
                assertThat(first.get()).isEqualTo("running");
                assertThat(queue.execute(1L, () -> "after timeout")).isEqualTo("after timeout");
                assertThat(lateRuns).hasValue(0);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void workerChecksTheTenSecondDeadlineBeforeStartingAnotherCar() throws Exception {
        var ticker = new AtomicLong();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var expiredRuns = new AtomicInteger();
        try (var queue = new CarCommandExecutor(settings(Duration.ofSeconds(10)), ticker::get,
                Executors.newFixedThreadPool(1))) {
            var first = call(() -> queue.execute(1L, () -> {
                entered.countDown();
                await(release);
                return "first";
            }));
            try {
                await(entered);
                var expired = call(() -> queue.execute(2L, expiredRuns::incrementAndGet));
                expired.awaitWaiting();
                ticker.set(Duration.ofSeconds(10).toNanos());
                release.countDown();
                first.get();
                assertThatThrownBy(expired::get).hasCauseInstanceOf(CarQueueRejectedException.class);
                assertThat(queue.execute(2L, () -> "new request")).isEqualTo("new request");
                assertThat(expiredRuns).hasValue(0);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void runningActionOutlivesItsAdmissionTimeoutAndCallerInterruption() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var restoredInterrupt = new AtomicBoolean();
        try (var queue = new CarCommandExecutor(settings(Duration.ofMillis(50)))) {
            var first = call(() -> {
                String value = queue.execute(1L, () -> {
                    entered.countDown();
                    await(release);
                    assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    return "committed result";
                });
                restoredInterrupt.set(Thread.currentThread().isInterrupted());
                return value;
            });
            try {
                await(entered);
                first.awaitState(Thread.State.WAITING);
                first.thread.interrupt();
                assertThat(first.result).isNotDone();
                release.countDown();
                assertThat(first.get()).isEqualTo("committed result");
                assertThat(restoredInterrupt).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    private static CarQueueProperties settings(Duration waitTimeout) {
        return new CarQueueProperties(1, 2, 2, waitTimeout, Duration.ofSeconds(2));
    }

    @Test
    void invalidCommandsAreRejectedWithoutExecutingAnything() {
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            var calls = new AtomicInteger();
            for (Long id : new Long[]{null, 0L, -1L}) {
                assertThatThrownBy(() -> queue.execute(id, calls::incrementAndGet))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> queue.execute(1L, null)).isInstanceOf(NullPointerException.class);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void failedActionKeepsItsOriginalExceptionAndDoesNotBlockNextAction() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var failure = new IllegalStateException("business failure");
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            var first = call(() -> queue.execute(1L, () -> {
                entered.countDown();
                await(release);
                throw failure;
            }));
            try {
                await(entered);
                var next = call(() -> queue.execute(1L, () -> "next"));
                next.awaitWaiting();
                release.countDown();
                assertThatThrownBy(first::get).hasCause(failure);
                assertThat(next.get()).isEqualTo("next");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void fiftyWaitingCommandsPerCarAreAllowedButTheNextNeverRuns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executed = new AtomicInteger();
        var rejectedRuns = new AtomicInteger();
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            var first = call(() -> queue.execute(1L, () -> {
                entered.countDown();
                await(release);
                return "first";
            }));
            var waiting = new java.util.ArrayList<Call<Integer>>();
            try {
                await(entered);
                for (int i = 0; i < 50; i++) {
                    var next = call(() -> queue.execute(1L, executed::incrementAndGet));
                    next.awaitWaiting();
                    waiting.add(next);
                }
                assertThatThrownBy(() -> queue.execute(1L, rejectedRuns::incrementAndGet))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(queue.execute(2L, () -> "other")).isEqualTo("other");
                release.countDown();
                first.get();
                for (var next : waiting) {
                    next.get();
                }
                assertThat(executed).hasValue(50);
                assertThat(rejectedRuns).hasValue(0);
                assertThat(queue.execute(1L, () -> "reused")).isEqualTo("reused");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void globalWaitingLimitIncludesCarsWhoseDrainerHasNotStarted() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var rejectedRuns = new AtomicInteger();
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            var running = new java.util.ArrayList<Call<Integer>>();
            var waiting = new java.util.ArrayList<Call<Integer>>();
            try {
                for (long carId = 1; carId <= 4; carId++) {
                    long id = carId;
                    running.add(call(() -> queue.execute(id, () -> {
                        entered.countDown();
                        await(release);
                        return 1;
                    })));
                }
                await(entered);
                for (int i = 0; i < 500; i++) {
                    long id = 10 + i / 50;
                    var next = call(() -> queue.execute(id, () -> 1));
                    next.awaitWaiting();
                    waiting.add(next);
                }
                assertThatThrownBy(() -> queue.execute(100L, rejectedRuns::incrementAndGet))
                        .isInstanceOf(RejectedExecutionException.class);
                release.countDown();
                for (var next : running) {
                    next.get();
                }
                for (var next : waiting) {
                    assertThat(next.get()).isEqualTo(1);
                }
                assertThat(rejectedRuns).hasValue(0);
                assertThat(queue.execute(100L, () -> "capacity returned")).isEqualTo("capacity returned");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void sameCarRunsInAdmissionOrderWhileAnotherCarCanFinish() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        List<Integer> order = new CopyOnWriteArrayList<>();
        try (var queue = new CarCommandExecutor(new CarQueueProperties())) {
            var first = call(() -> queue.execute(1L, () -> {
                order.add(1);
                entered.countDown();
                await(release);
                return "first";
            }));
            try {
                await(entered);
                var second = call(() -> queue.execute(1L, () -> {
                    order.add(2);
                    return "second";
                }));
                second.awaitWaiting();
                var third = call(() -> queue.execute(1L, () -> {
                    order.add(3);
                    return "third";
                }));
                third.awaitWaiting();
                assertThat(call(() -> queue.execute(2L, () -> "other")).get()).isEqualTo("other");
                assertThat(order).containsExactly(1);
                release.countDown();
                assertThat(first.get()).isEqualTo("first");
                assertThat(second.get()).isEqualTo("second");
                assertThat(third.get()).isEqualTo("third");
                assertThat(order).containsExactly(1, 2, 3);
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS), "latch did not open");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static <T> Call<T> call(Supplier<T> action) {
        var result = new CompletableFuture<T>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable ex) {
                result.completeExceptionally(ex);
            }
        });
        return new Call<>(thread, result);
    }

    private record Call<T>(Thread thread, CompletableFuture<T> result) {
        T get() throws Exception {
            return result.get(2, TimeUnit.SECONDS);
        }

        void awaitWaiting() {
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!isWaiting() && !result.isDone()
                    && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(result).isNotDone();
            assertThat(isWaiting()).isTrue();
        }

        private boolean isWaiting() {
            return thread.getState() == Thread.State.TIMED_WAITING || thread.getState() == Thread.State.WAITING;
        }

        void awaitState(Thread.State state) {
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (thread.getState() != state && !result.isDone() && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(result).isNotDone();
            assertThat(thread.getState()).isEqualTo(state);
        }
    }
}
