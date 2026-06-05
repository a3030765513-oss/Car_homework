package com.substation.controller;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TickScheduler {

    private static final int DEFAULT_INTERVAL_MS = 500;

    private final StatusDispatcher dispatcher;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private volatile boolean paused;
    private volatile int intervalMs;

    public TickScheduler(StatusDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.intervalMs = DEFAULT_INTERVAL_MS;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tick-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        schedule();
    }

    public void togglePause() {
        paused = !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setInterval(int ms) {
        this.intervalMs = ms;
        if (future != null) {
            schedule();
        }
    }

    public void stop() {
        if (future != null) {
            future.cancel(false);
        }
        executor.shutdown();
    }

    private void schedule() {
        if (future != null) {
            future.cancel(false);
        }
        future = executor.scheduleWithFixedDelay(this::tickLoop, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void tickLoop() {
        if (paused) {
            return;
        }
        dispatcher.dispatch();
    }
}
