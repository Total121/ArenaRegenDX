/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.regen;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages per-arena auto-reset countdown timers.
 *
 * <p>Each arena with {@code autoResetInterval > 0} gets a countdown. At key
 * thresholds (10 min, 5 min, 1 min, 30 sec, 5-1 sec) a broadcast message is
 * sent to all players in the arena. At zero, regeneration is triggered.</p>
 *
 * <p>The countdown is driven by a single 1-second repeating task. Each tick
 * decrements the remaining time for every arena that has an active timer.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class AutoResetManager {

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final RegenManager regenManager;
    private final SchedulerAdapter scheduler;

    /** Per-arena remaining seconds until reset. */
    private final ConcurrentMap<String, Long> countdowns = new ConcurrentHashMap<>();

    /** The repeating driver task. */
    private com.dravorax.arenaregendx.scheduler.TaskHandle driver;

    /** Broadcast thresholds (in seconds), descending. */
    private static final long[] BROADCAST_THRESHOLDS = {
            600,  // 10 min
            300,  // 5 min
            60,   // 1 min
            30,   // 30 sec
            5, 4, 3, 2, 1
    };

    public AutoResetManager(@NotNull final ArenaRegenDXPlugin plugin,
                            @NotNull final ArenaManager arenaManager,
                            @NotNull final RegenManager regenManager,
                            @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.regenManager = regenManager;
        this.scheduler = scheduler;
    }

    /**
     * Start the countdown driver (1-second repeating task on the global region).
     */
    public void start() {
        if (driver != null) return;
        // Global region — broadcasts touch players and must not run on async.
        driver = scheduler.runGlobalRepeating(this::tick, 20L, 20L);
        plugin.getSLF4JLogger().info("Auto-reset timer driver started.");
    }

    /**
     * Per-second tick: decrement all countdowns, broadcast at thresholds, trigger regen at zero.
     */
    private void tick() {
        if (countdowns.isEmpty()) return;
        for (final String arenaName : countdowns.keySet()) {
            // computeIfPresent: a countdown stopped concurrently (delete / rename /
            // GUI toggle) must not be resurrected by a blind put().
            final Long next = countdowns.computeIfPresent(arenaName, (k, v) -> v > 0 ? v - 1 : v);
            if (next == null) {
                continue;
            }
            final long remaining = next;
            if (remaining < 0) {
                continue;
            }

            // Check if we hit a broadcast threshold.
            for (final long threshold : BROADCAST_THRESHOLDS) {
                if (remaining == threshold) {
                    broadcastCountdown(arenaName, threshold);
                    break;
                }
            }

            // At zero, trigger regeneration once; park the timer at -1 until the
            // restore finishes so a slow restore is not re-triggered every second.
            if (remaining == 0 && countdowns.replace(arenaName, 0L, -1L)) {
                triggerAutoRegen(arenaName);
            }
        }
    }

    /**
     * Start or restart the countdown for an arena.
     *
     * @param arena the arena.
     */
    public void startCountdown(@NotNull final Arena arena) {
        if (arena.autoResetInterval() > 0) {
            countdowns.put(arena.name(), (long) arena.autoResetInterval());
            plugin.getSLF4JLogger().info("Auto-reset timer started for '{}' ({} sec).",
                    arena.name(), arena.autoResetInterval());
        }
    }

    /**
     * Stop the countdown for an arena.
     *
     * @param arenaName the arena name.
     */
    public void stopCountdown(@NotNull final String arenaName) {
        countdowns.remove(arenaName);
    }

    /**
     * Get remaining seconds until auto-reset.
     *
     * @param arenaName the arena name.
     * @return remaining seconds, or -1 if no active countdown.
     */
    public long getRemaining(@NotNull final String arenaName) {
        return countdowns.getOrDefault(arenaName, -1L);
    }

    /**
     * Broadcast a countdown message to all players in the arena.
     */
    private void broadcastCountdown(@NotNull final String arenaName, final long seconds) {
        final Arena arena = arenaManager.getArena(arenaName);
        if (arena == null) return;
        final String timeStr = formatTime(seconds);
        final Component msg = ColorUtil.mini(
                "<gold>⚠ <yellow>" + arenaName + " <gold>will be reset in <red>" + timeStr + "<gold>!");
        arenaManager.forEachPlayerInArena(arena, player -> {
            player.sendMessage(msg);
            player.sendActionBar(ColorUtil.mini("<red>" + timeStr + " <dark_gray>until reset"));
        });
        plugin.getSLF4JLogger().info("[AutoReset] {} will reset in {}.", arenaName, timeStr);
    }

    /**
     * Trigger auto-regeneration and reset the countdown.
     */
    private void triggerAutoRegen(@NotNull final String arenaName) {
        final Arena arena = arenaManager.getArena(arenaName);
        if (arena == null) {
            countdowns.remove(arenaName);
            return;
        }
        if (arena.latestSnapshotId() <= 0) {
            plugin.getSLF4JLogger().warn("[AutoReset] '{}' has no snapshot — skipping reset.", arenaName);
            // Restart the countdown for the next cycle.
            startCountdown(arena);
            return;
        }
        plugin.getSLF4JLogger().info("[AutoReset] Triggering regeneration for '{}'.", arenaName);
        // Broadcast "resetting now" message.
        final Component msg = ColorUtil.mini(
                "<gold>⚠ <yellow>" + arenaName + " <red>is now regenerating<gold>...");
        arenaManager.forEachPlayerInArena(arena, player -> player.sendMessage(msg));
        // Trigger regen (will teleport players to spawn first).
        // When dirty-tracking is enabled, restore only changed voxels for speed;
        // otherwise do a full snapshot restore.
        final RegenMode mode = arena.asyncRegen()
                ? RegenMode.LAYER_BY_LAYER
                : RegenMode.INSTANT;
        final boolean allowDirty = plugin.configManager().performance().dirtyTracking;
        regenManager.regen(arenaName, mode, true, allowDirty)
                .whenComplete((task, err) -> {
                    if (err != null) {
                        plugin.getSLF4JLogger().warn("[AutoReset] Regen failed for '{}': {}",
                                arenaName, err.getMessage());
                    }
                    // Restart for the next cycle only if the arena still exists under
                    // this name and nobody stopped its countdown meanwhile.
                    if (arenaManager.getArena(arena.name()) == arena && countdowns.containsKey(arena.name())) {
                        startCountdown(arena);
                    }
                });
    }

    /**
     * Format seconds as a human-readable time string.
     */
    private @NotNull String formatTime(final long seconds) {
        if (seconds >= 60) {
            final long mins = seconds / 60;
            final long secs = seconds % 60;
            if (secs == 0) {
                return mins + " minute" + (mins > 1 ? "s" : "");
            }
            return mins + "m " + secs + "s";
        }
        return seconds + " second" + (seconds > 1 ? "s" : "");
    }

    /**
     * Shutdown: cancel the driver.
     */
    public void shutdown() {
        if (driver != null) {
            driver.cancel();
            driver = null;
        }
        countdowns.clear();
    }

    /**
     * Initialize countdowns for all arenas that have auto-reset enabled.
     * Called after arenas are loaded from disk.
     */
    public void initializeAll() {
        for (final Arena arena : arenaManager.all()) {
            if (arena.autoResetInterval() > 0 && arena.latestSnapshotId() > 0) {
                startCountdown(arena);
            }
        }
    }
}
