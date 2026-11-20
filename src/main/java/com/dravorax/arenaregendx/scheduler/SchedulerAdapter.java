/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.scheduler;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-agnostic scheduler abstraction.
 *
 * <p>This interface is the single point through which all scheduling flows.
 * It hides the differences between Paper's {@code BukkitScheduler} (one main
 * thread) and Folia's region/entity/async schedulers (many region threads, no
 * global main thread).</p>
 *
 * <h2>Design rules</h2>
 * <ul>
 *   <li><b>Async work</b> (compression, file IO, serialization) uses
 *       {@link #runAsync(Runnable)}. Never touch world state off-thread.</li>
 *   <li><b>Block access</b> uses {@link #runAtLocation(Location, Runnable)} so
 *       Folia dispatches to the region thread that owns the chunk.</li>
 *   <li><b>Entity / player work</b> (teleports, messages, UI) uses
 *       {@link #runAtEntity(Entity, Runnable)} which follows the entity across
 *       region boundaries.</li>
 *   <li><b>Global bookkeeping</b> (queue drivers, timers) uses the global
 *       region via {@link #runGlobalRepeating(Runnable, long, long)}.</li>
 * </ul>
 *
 * <p>Every method returns a {@link TaskHandle} for deterministic cancellation.
 * Exceptions thrown by scheduled tasks are logged and never propagate into
 * the server scheduler.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public interface SchedulerAdapter {

    /** Run a task asynchronously. Safe for IO, compression, serialization. */
    @NotNull TaskHandle runAsync(@NotNull Runnable task);

    /** Run a task asynchronously after {@code delayTicks} (20 ticks = 1 second). */
    @NotNull TaskHandle runAsyncDelayed(@NotNull Runnable task, long delayTicks);

    /** Schedule a repeating async task. */
    @NotNull TaskHandle runAsyncRepeating(@NotNull Runnable task, long delayTicks, long periodTicks);

    /**
     * Run a task on the thread that owns the chunk at {@code location}
     * (the main thread on Paper, the owning region thread on Folia).
     */
    default @NotNull TaskHandle runAtLocation(@NotNull final Location location, @NotNull final Runnable task) {
        return runAtLocation(location, task, null);
    }

    /**
     * Like {@link #runAtLocation(Location, Runnable)} but invokes
     * {@code onScheduleFailure} if the task could not be queued at all (for
     * example while the plugin is disabling). Batch drivers use this so their
     * in-flight flags are always released.
     */
    @NotNull TaskHandle runAtLocation(@NotNull Location location, @NotNull Runnable task,
                                      @Nullable Runnable onScheduleFailure);

    /** Run a task on the thread that owns {@code entity}. */
    default @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task) {
        return runAtEntity(entity, task, null);
    }

    /**
     * Run a task on the thread that owns {@code entity}. If the entity is removed
     * (player logged out, mob despawned) before the task runs, {@code retired}
     * runs instead, when provided.
     */
    @NotNull TaskHandle runAtEntity(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired);

    /** Run a task on the global region (Folia) or main thread (Paper). */
    @NotNull TaskHandle runGlobal(@NotNull Runnable task);

    /** Run a task on the global region after a delay. */
    @NotNull TaskHandle runGlobalDelayed(@NotNull Runnable task, long delayTicks);

    /** Run a repeating task on the global region. */
    @NotNull TaskHandle runGlobalRepeating(@NotNull Runnable task, long delayTicks, long periodTicks);

    /**
     * @return true when the calling thread may touch the given chunk right now
     *         (Paper: the main thread; Folia: the owning region thread). Callers
     *         use this to run work inline instead of paying a one-tick hop.
     */
    boolean isOwnedByCurrentRegion(@NotNull World world, int chunkX, int chunkZ);

    /** Check whether the chunk containing block {@code (x, z)} is loaded, without loading it. */
    default boolean isChunkLoaded(@NotNull final World world, final int x, final int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    /**
     * Load a chunk asynchronously, then run {@code callback} on the thread that
     * owns it. The callback always runs, even if the load failed.
     */
    void ensureChunkLoaded(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable callback);

    /** Cancel every task scheduled through this adapter. Used on plugin disable. */
    void cancelOwnedTasks();

    /** @return a human-readable identifier ("Paper" or "Folia"). */
    @NotNull String implementationName();

    /** Convert server ticks to milliseconds (negative values clamp to zero). */
    static long ticksToMillis(final long ticks) {
        return Math.max(0L, ticks) * 50L;
    }
}
