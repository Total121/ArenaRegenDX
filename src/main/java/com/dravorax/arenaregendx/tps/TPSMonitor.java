/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.tps;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lightweight TPS (ticks-per-second) monitor with adaptive block-budget computation.
 *
 * <p>Strategy:</p>
 * <ol>
 *   <li>Primary source: Paper's {@code Bukkit.getTPS()} (returns double[3] for
 *       1m/5m/15m averages). Resolved reflectively so we don't crash on Folia.</li>
 *   <li>Secondary source (Folia or non-Paper): a self-sampling tick-time probe.
 *       A global-region repeating task fires nominally every tick; we measure the
 *       real elapsed time between fires and derive TPS.</li>
 * </ol>
 *
 * <p>The adaptive block budget uses a piecewise-linear TPS→budget curve (see
 * {@link #adaptiveBlockBudget()}) so the regeneration engine never starves the
 * server thread on laggy hosts, and never throttles unnecessarily on healthy ones.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class TPSMonitor {

    private static final int WINDOW = 60; // 60 samples ≈ 3s @ 20 samples/s
    private static final long NOMINAL_TICK_NANOS = 50_000_000L; // 50ms

    private final ArenaRegenDXPlugin plugin;
    private final SchedulerAdapter scheduler;

    // Rolling window of measured tick intervals (ms).
    private final AtomicLongArray intervalsMs = new AtomicLongArray(WINDOW);
    private volatile int writeIndex = 0;
    private volatile int samples = 0;

    // Last probe fire time.
    private volatile long lastFireNanos = 0L;

    // Cached reflective Bukkit#getTPS() (Paper-only).
    private volatile Method bukkitGetTPS;
    private volatile boolean bukkitGetTPSChecked;

    // Probe task handle.
    private volatile TaskHandle probeTask;

    // Cached thresholds (loaded from performance.yml at startup).
    private volatile int maxBudget = 12_000;
    private volatile double tpsFull = 19.5;   // above this → full speed
    private volatile double tpsThrottle = 18.0;
    private volatile double tpsCrawl = 14.0;
    /** Max nanoseconds of restore work allowed on a healthy tick. */
    private volatile long maxTickNanos = 3_500_000L;

    public TPSMonitor(@NotNull final ArenaRegenDXPlugin plugin,
                      @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    /**
     * Start the monitor. Idempotent.
     */
    public void start() {
        if (probeTask != null) {
            return;
        }
        // Probe fires on the global region every tick (period=1). On Paper this is
        // the main thread; on Folia the global region thread.
        probeTask = scheduler.runGlobalRepeating(this::probe, 1L, 1L);
        plugin.getSLF4JLogger().info("TPS monitor started ({}).",
                scheduler.implementationName());
    }

    /**
     * Stop the monitor and release the probe task.
     */
    public void stop() {
        if (probeTask != null) {
            probeTask.cancel();
            probeTask = null;
        }
    }

    /**
     * Probe callback — records the actual elapsed time since the last fire.
     */
    private void probe() {
        final long now = System.nanoTime();
        final long last = lastFireNanos;
        lastFireNanos = now;
        if (last == 0L) {
            return; // first fire, no interval yet
        }
        final long elapsedNanos = now - last;
        // Clamp absurd values (e.g., GC pauses) to a sane range [5ms, 5s].
        final long clamped = Math.max(5_000_000L, Math.min(elapsedNanos, 5_000_000_000L));
        final long ms = clamped / 1_000_000L;
        final int idx = writeIndex;
        intervalsMs.set(idx, ms);
        writeIndex = (idx + 1) % WINDOW;
        if (samples < WINDOW) {
            samples++;
        }
    }

    // -----------------------------------------------------------------------
    // Public read API
    // -----------------------------------------------------------------------

    /**
     * @return the current 1-minute-equivalent TPS (clamped to [0, 20]).
     */
    public double currentTPS() {
        // Prefer Paper's native TPS if available (more accurate, server-authoritative).
        final double[] nativeTPS = nativeTPS();
        if (nativeTPS != null) {
            return Math.max(0.0, Math.min(20.0, nativeTPS[0]));
        }
        return Math.max(0.0, Math.min(20.0, derivedTPS()));
    }

    /**
     * @return 5-minute TPS (Paper native) or derived equivalent.
     */
    public double tps5m() {
        final double[] n = nativeTPS();
        if (n != null) return clamp(n[1]);
        return clamp(derivedTPS());
    }

    /**
     * @return 15-minute TPS (Paper native) or derived equivalent.
     */
    public double tps15m() {
        final double[] n = nativeTPS();
        if (n != null) return clamp(n[2]);
        return clamp(derivedTPS());
    }

    /**
     * @return average tick time in ms (derived from probe samples).
     */
    public double averageTickTimeMs() {
        final int n = samples;
        if (n == 0) {
            return 50.0; // nominal assumption
        }
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            sum += intervalsMs.get(i);
        }
        return (double) sum / n;
    }

    /**
     * Compute the adaptive block budget for this tick, based on current TPS.
     *
     * <p>Piecewise-linear mapping:</p>
     * <ul>
     *   <li>TPS ≥ {@code tpsFull} (default 19.5) → {@code maxBudget} (default 10,000).</li>
     *   <li>TPS {@code tpsFull} → {@code tpsThrottle} → linear 100% → 50% of max.</li>
     *   <li>TPS {@code tpsThrottle} → {@code tpsCrawl} → linear 50% → 10% of max.</li>
     *   <li>TPS ≤ {@code tpsCrawl} (default 14.0) → 10% of max (floor of 100 blocks).</li>
     * </ul>
     *
     * @return blocks-per-tick budget, always ≥ 100.
     */
    public int adaptiveBlockBudget() {
        final double tps = currentTPS();
        final int max = maxBudget;
        if (tps >= tpsFull) {
            return max;
        }
        if (tps <= tpsCrawl) {
            return Math.max(100, max / 10);
        }
        if (tps >= tpsThrottle) {
            // Linear from 100% (at tpsFull) down to 50% (at tpsThrottle).
            final double ratio = (tps - tpsThrottle) / (tpsFull - tpsThrottle);
            return Math.max(100, (int) (max * (0.5 + 0.5 * ratio)));
        }
        // Between tpsThrottle and tpsCrawl: 50% → 10%.
        final double ratio = (tps - tpsCrawl) / (tpsThrottle - tpsCrawl);
        return Math.max(100, (int) (max * (0.1 + 0.4 * ratio)));
    }

    /**
     * Nanoseconds of restore work allowed this tick. Scales with TPS the same
     * way as the block budget so a lagging server never spends a full tick
     * inside the restorer.
     */
    public long adaptiveNanosBudget() {
        final double tps = currentTPS();
        final long max = maxTickNanos;
        if (tps >= tpsFull) {
            return max;
        }
        if (tps <= tpsCrawl) {
            return Math.max(400_000L, max / 8L);
        }
        if (tps >= tpsThrottle) {
            final double ratio = (tps - tpsThrottle) / (tpsFull - tpsThrottle);
            return Math.max(750_000L, (long) (max * (0.5 + 0.5 * ratio)));
        }
        final double ratio = (tps - tpsCrawl) / (tpsThrottle - tpsCrawl);
        return Math.max(400_000L, (long) (max * (0.15 + 0.35 * ratio)));
    }

    /**
     * Update tuning thresholds (called when performance.yml is reloaded).
     *
     * @param maxBudget   max blocks per tick at full TPS.
     * @param tpsFull     TPS above which full speed is used.
     * @param tpsThrottle TPS at which to begin throttling.
     * @param tpsCrawl    TPS at which to crawl.
     */
    public void configure(final int maxBudget, final double tpsFull,
                          final double tpsThrottle, final double tpsCrawl) {
        configure(maxBudget, tpsFull, tpsThrottle, tpsCrawl, 3.5);
    }

    public void configure(final int maxBudget, final double tpsFull,
                          final double tpsThrottle, final double tpsCrawl,
                          final double maxTickMillis) {
        this.maxBudget = Math.max(100, maxBudget);
        this.tpsFull = clampTPS(tpsFull);
        this.tpsThrottle = clampTPS(tpsThrottle);
        this.tpsCrawl = clampTPS(tpsCrawl);
        this.maxTickNanos = (long) (Math.max(0.4, Math.min(12.0, maxTickMillis)) * 1_000_000.0);
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private double derivedTPS() {
        final int n = samples;
        if (n == 0) {
            return 20.0;
        }
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            sum += intervalsMs.get(i);
        }
        final double avgMs = (double) sum / n;
        if (avgMs <= 0.0) {
            return 20.0;
        }
        return clamp(1000.0 / avgMs);
    }

    private @Nullable double[] nativeTPS() {
        if (!bukkitGetTPSChecked) {
            try {
                bukkitGetTPS = Bukkit.class.getMethod("getTPS");
            } catch (final NoSuchMethodException ignored) {
                bukkitGetTPS = null;
            }
            bukkitGetTPSChecked = true;
        }
        if (bukkitGetTPS == null) {
            return null;
        }
        try {
            final Object result = bukkitGetTPS.invoke(Bukkit.getServer());
            if (result instanceof double[] arr && arr.length >= 3) {
                return arr;
            }
        } catch (final Throwable ignored) {
            // ignore — fall back to derived
        }
        return null;
    }

    private static double clamp(final double v) {
        return Math.max(0.0, Math.min(20.0, v));
    }

    private static double clampTPS(final double v) {
        return Math.max(1.0, Math.min(20.0, v));
    }
}
