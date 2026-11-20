/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.tps;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Lightweight TPS monitor with adaptive restore budgets.
 *
 * <ol>
 *   <li>Primary source: Paper's {@code Bukkit.getTPS()} / {@code getAverageTickTime()}.</li>
 *   <li>Fallback (Folia, where the global calls are unsupported): a global-region
 *       probe measures the real interval between ticks.</li>
 * </ol>
 *
 * <p>Values are sampled once per tick by the probe and cached, so the regen
 * driver, placeholders and GUIs read plain fields instead of calling into the
 * server several times per tick.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class TPSMonitor {

    private static final int WINDOW = 100; // ~5 s of probe samples

    private final ArenaRegenDXPlugin plugin;
    private final SchedulerAdapter scheduler;

    private final long[] intervalsNanos = new long[WINDOW];
    private int writeIndex;
    private int samples;
    private long intervalSum;
    private long lastFireNanos;

    private volatile boolean nativeSupported = true;
    private volatile double tps1m = 20.0;
    private volatile double tps5m = 20.0;
    private volatile double tps15m = 20.0;
    private volatile double mspt = 0.0;

    private volatile TaskHandle probeTask;

    private volatile int maxBudget = 20_000;
    private volatile double tpsFull = 19.5;
    private volatile double tpsThrottle = 18.0;
    private volatile double tpsCrawl = 14.0;
    private volatile long maxTickNanos = 6_000_000L;

    public TPSMonitor(@NotNull final ArenaRegenDXPlugin plugin, @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    /** Start the per-tick probe. Idempotent. */
    public void start() {
        if (probeTask == null) {
            probeTask = scheduler.runGlobalRepeating(this::probe, 1L, 1L);
        }
    }

    public void stop() {
        final TaskHandle task = probeTask;
        if (task != null) {
            task.cancel();
            probeTask = null;
        }
    }

    /** Runs every tick on the global region (single writer for the ring buffer). */
    private void probe() {
        final long now = System.nanoTime();
        final long last = lastFireNanos;
        lastFireNanos = now;
        if (last != 0L) {
            final long elapsed = Math.max(1_000_000L, Math.min(now - last, 5_000_000_000L));
            intervalSum += elapsed - intervalsNanos[writeIndex];
            intervalsNanos[writeIndex] = elapsed;
            writeIndex = (writeIndex + 1) % WINDOW;
            if (samples < WINDOW) {
                samples++;
            }
        }
        if (nativeSupported) {
            try {
                final double[] tps = Bukkit.getTPS();
                if (tps.length >= 3) {
                    tps1m = clamp(tps[0]);
                    tps5m = clamp(tps[1]);
                    tps15m = clamp(tps[2]);
                }
                mspt = Bukkit.getAverageTickTime();
                return;
            } catch (final Throwable t) {
                // Folia: global TPS is not available - use the probe from now on.
                nativeSupported = false;
            }
        }
        final double derived = derivedTPS();
        tps1m = derived;
        tps5m = derived;
        tps15m = derived;
        mspt = samples == 0 ? 0.0 : (intervalSum / (double) samples) / 1_000_000.0;
    }

    private double derivedTPS() {
        if (samples == 0 || intervalSum <= 0L) {
            return 20.0;
        }
        return clamp(1_000_000_000.0 / (intervalSum / (double) samples));
    }

    // -----------------------------------------------------------------------
    // Read API
    // -----------------------------------------------------------------------

    /** @return the 1-minute TPS, clamped to [0, 20]. */
    public double currentTPS() {
        return tps1m;
    }

    public double tps5m() {
        return tps5m;
    }

    public double tps15m() {
        return tps15m;
    }

    /** @return average milliseconds per tick (MSPT on Paper; tick interval on Folia). */
    public double averageTickTimeMs() {
        return mspt;
    }

    /**
     * Blocks-per-tick budget from a piecewise-linear TPS curve:
     * 100% at {@code tps-full}, 50% at {@code tps-throttle}, 10% at {@code tps-crawl}.
     */
    public int adaptiveBlockBudget() {
        final double tps = tps1m;
        final int max = maxBudget;
        if (tps >= tpsFull) {
            return max;
        }
        if (tps <= tpsCrawl) {
            return Math.max(100, max / 10);
        }
        if (tps >= tpsThrottle) {
            final double ratio = (tps - tpsThrottle) / Math.max(0.01, tpsFull - tpsThrottle);
            return Math.max(100, (int) (max * (0.5 + 0.5 * ratio)));
        }
        final double ratio = (tps - tpsCrawl) / Math.max(0.01, tpsThrottle - tpsCrawl);
        return Math.max(100, (int) (max * (0.1 + 0.4 * ratio)));
    }

    /** Nanoseconds of restore work allowed this tick, scaled like the block budget. */
    public long adaptiveNanosBudget() {
        final double tps = tps1m;
        final long max = maxTickNanos;
        if (tps >= tpsFull) {
            return max;
        }
        if (tps <= tpsCrawl) {
            return Math.max(400_000L, max / 8L);
        }
        if (tps >= tpsThrottle) {
            final double ratio = (tps - tpsThrottle) / Math.max(0.01, tpsFull - tpsThrottle);
            return Math.max(750_000L, (long) (max * (0.5 + 0.5 * ratio)));
        }
        final double ratio = (tps - tpsCrawl) / Math.max(0.01, tpsThrottle - tpsCrawl);
        return Math.max(400_000L, (long) (max * (0.15 + 0.35 * ratio)));
    }

    /** Update thresholds (enable / reload). Thresholds are sorted so a misordered config still works. */
    public void configure(final int maxBudget, final double tpsFull, final double tpsThrottle,
                          final double tpsCrawl, final double maxTickMillis) {
        final double[] t = {clampThreshold(tpsCrawl), clampThreshold(tpsThrottle), clampThreshold(tpsFull)};
        java.util.Arrays.sort(t);
        this.maxBudget = Math.max(100, maxBudget);
        this.tpsCrawl = t[0];
        this.tpsThrottle = t[1];
        this.tpsFull = t[2];
        this.maxTickNanos = (long) (Math.max(0.4, Math.min(12.0, maxTickMillis)) * 1_000_000.0);
        if (t[0] != clampThreshold(tpsCrawl) || t[2] != clampThreshold(tpsFull)) {
            plugin.getSLF4JLogger().warn("performance.yml: tps-full > tps-throttle > tps-crawl expected; values were reordered.");
        }
    }

    private static double clamp(final double v) {
        return Math.max(0.0, Math.min(20.0, v));
    }

    private static double clampThreshold(final double v) {
        return Math.max(1.0, Math.min(20.0, v));
    }
}
