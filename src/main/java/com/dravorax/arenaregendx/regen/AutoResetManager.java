/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.regen;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.scheduler.TaskHandle;
import com.dravorax.arenaregendx.util.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-arena auto-reset countdown timers.
 *
 * <p>Arenas with {@code auto-reset > 0} get a countdown driven by one 1-second
 * global task. Players inside the arena are warned at 10 min, 5 min, 1 min,
 * 30 s and 5..1 s; at zero the arena is restored (only changed voxels when
 * dirty tracking trusts its record) and the countdown restarts once the
 * restore finishes.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class AutoResetManager {

    private static final long[] BROADCAST_THRESHOLDS = {600, 300, 60, 30, 5, 4, 3, 2, 1};

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final RegenManager regenManager;
    private final SchedulerAdapter scheduler;

    /** Remaining seconds keyed by lowercase arena name. Value 0 = restore in progress. */
    private final Map<String, Long> countdowns = new ConcurrentHashMap<>();
    private TaskHandle driver;

    public AutoResetManager(@NotNull final ArenaRegenDXPlugin plugin,
                            @NotNull final ArenaManager arenaManager,
                            @NotNull final RegenManager regenManager,
                            @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.regenManager = regenManager;
        this.scheduler = scheduler;
    }

    /** Start the 1-second driver on the global region. */
    public void start() {
        if (driver != null) return;
        driver = scheduler.runGlobalRepeating(this::tick, 20L, 20L);
    }

    private void tick() {
        if (countdowns.isEmpty()) return;
        for (final Map.Entry<String, Long> entry : countdowns.entrySet()) {
            final long current = entry.getValue();
            if (current <= 0) {
                continue; // restore in flight
            }
            final long remaining = current - 1;
            // Only update if nobody restarted / stopped the timer concurrently.
            if (!countdowns.replace(entry.getKey(), current, remaining)) {
                continue;
            }
            for (final long threshold : BROADCAST_THRESHOLDS) {
                if (remaining == threshold) {
                    broadcastCountdown(entry.getKey(), threshold);
                    break;
                }
            }
            if (remaining <= 0) {
                triggerAutoRegen(entry.getKey());
            }
        }
    }

    /** Start or restart the countdown for an arena (no-op if auto-reset is off). */
    public void startCountdown(@NotNull final Arena arena) {
        if (arena.autoResetInterval() > 0) {
            countdowns.put(key(arena.name()), (long) arena.autoResetInterval());
        } else {
            countdowns.remove(key(arena.name()));
        }
    }

    /** Stop the countdown for an arena. */
    public void stopCountdown(@NotNull final String arenaName) {
        countdowns.remove(key(arenaName));
    }

    /** @return remaining seconds, 0 while restoring, or -1 if no countdown is active. */
    public long getRemaining(@NotNull final String arenaName) {
        return countdowns.getOrDefault(key(arenaName), -1L);
    }

    private void broadcastCountdown(@NotNull final String key, final long seconds) {
        final Arena arena = arenaManager.getArena(key);
        if (arena == null) return;
        final String time = formatTime(seconds);
        final Messages messages = plugin.messages();
        arenaManager.forEachPlayerInArena(arena, player -> {
            messages.send(player, "auto-reset.warning", "arena", arena.name(), "time", time);
            messages.actionBar(player, "auto-reset.action-bar", "arena", arena.name(), "time", time);
        });
    }

    private void triggerAutoRegen(@NotNull final String key) {
        final Arena arena = arenaManager.getArena(key);
        if (arena == null || arena.autoResetInterval() <= 0) {
            countdowns.remove(key);
            return;
        }
        if (arena.latestSnapshotId() <= 0) {
            plugin.getSLF4JLogger().warn("[AutoReset] '{}' has no snapshot - skipping this cycle.", arena.name());
            startCountdown(arena);
            return;
        }
        final Messages messages = plugin.messages();
        arenaManager.forEachPlayerInArena(arena, player -> messages.send(player, "auto-reset.now", "arena", arena.name()));
        final RegenMode mode = arena.asyncRegen() ? RegenMode.LAYER_BY_LAYER : RegenMode.INSTANT;
        final boolean safe = plugin.configManager().general().safeModeDefault;
        regenManager.regen(arena.name(), mode, safe, true).whenComplete((task, err) -> {
            if (err != null) {
                plugin.getSLF4JLogger().warn("[AutoReset] Restore of '{}' failed: {}", arena.name(), err.getMessage());
            }
            // Restart only if the arena still exists, is the same instance, and nobody stopped the timer.
            final Arena live = arenaManager.getArena(arena.name());
            if (live == arena && countdowns.containsKey(key)) {
                startCountdown(live);
            }
        });
    }

    private static @NotNull String formatTime(final long seconds) {
        if (seconds >= 60) {
            final long mins = seconds / 60;
            final long secs = seconds % 60;
            return secs == 0 ? mins + (mins == 1 ? " minute" : " minutes") : mins + "m " + secs + "s";
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private static @NotNull String key(@NotNull final String arenaName) {
        return arenaName.toLowerCase(Locale.ROOT);
    }

    /** Cancel the driver and drop all timers. */
    public void shutdown() {
        if (driver != null) {
            driver.cancel();
            driver = null;
        }
        countdowns.clear();
    }

    /** Start countdowns for every arena with auto-reset enabled and a snapshot. */
    public void initializeAll() {
        for (final Arena arena : arenaManager.all()) {
            if (arena.autoResetInterval() > 0 && arena.latestSnapshotId() > 0) {
                startCountdown(arena);
            }
        }
    }
}
