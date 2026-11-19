/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.scheduler;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Platform-agnostic scheduler abstraction.
 *
 * <p>This interface is the single point through which all scheduling must flow.
 * It hides the differences between Paper's {@code BukkitScheduler} (one main thread)
 * and Folia's region/entity/async schedulers (multiple region threads, no global
 * main thread).</p>
 *
 * <h2>Design rules</h2>
 * <ul>
 *   <li><b>Async work</b> (compression, file IO, snapshot serialization) must use
 *       {@link #runAsync(Runnable)} or {@link #runAsyncDelayed(Runnable, long)}.
 *       Never touch Bukkit API off-thread except for thread-safe reads.</li>
 *   <li><b>Block placement</b> must use {@link #runAtLocation(Location, Runnable)}
 *       so Folia dispatches to the correct region thread.</li>
 *   <li><b>Entity-related work</b> must use {@link #runAtEntity(Entity, Runnable)}
 *       (teleports, entity metadata).</li>
 *   <li><b>Periodic tasks</b> (TPS monitor, visualizer) use
 *       {@link #runAsyncRepeating(Runnable, long, long)}.</li>
 * </ul>
 *
 * <p>All methods return a {@link TaskHandle} that can be used for cancellation,
 * enabling deterministic shutdown.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public interface SchedulerAdapter {

    /**
     * Execute a task asynchronously. Safe for IO, compression, serialization.
     *
     * @param task the task to run.
     * @return a handle for cancellation.
     */
    @NotNull TaskHandle runAsync(@NotNull Runnable task);

    /**
     * Execute a task asynchronously after a delay.
     *
     * @param task      the task to run.
     * @param delayTicks the delay in server ticks (20 = 1s). Converted internally.
     * @return a handle.
     */
    @NotNull TaskHandle runAsyncDelayed(@NotNull Runnable task, long delayTicks);

    /**
     * Schedule a repeating async task.
     *
     * @param task        the task.
     * @param delayTicks  initial delay in ticks.
     * @param periodTicks period in ticks.
     * @return a handle.
     */
    @NotNull TaskHandle runAsyncRepeating(@NotNull Runnable task, long delayTicks, long periodTicks);

    /**
     * Run a task on the region that owns the given location.
     *
     * <p>On Paper this is the main thread; on Folia it is the region thread for
     * the chunk at that location. Use this for all block-state mutations.</p>
     *
     * @param location the location (defines the region).
     * @param task     the task to run.
     * @return a handle.
     */
    @NotNull TaskHandle runAtLocation(@NotNull Location location, @NotNull Runnable task);

    /**
     * Like {@link #runAtLocation(Location, Runnable)} but invokes
     * {@code onScheduleFailure} on the global scheduler if the region task
     * could not be queued after retries (Folia). Paper never calls the failure
     * callback. Use this so batch drivers can clear {@code batchInFlight}.
     */
    default @NotNull TaskHandle runAtLocation(@NotNull final Location location,
                                              @NotNull final Runnable task,
                                              @Nullable final Runnable onScheduleFailure) {
        return runAtLocation(location, task);
    }

    /**
     * Run a task on the region that owns the given entity, on the entity scheduler.
     *
     * @param entity the entity.
     * @param task   the task to run.
     * @return a handle.
     */
    @NotNull TaskHandle runAtEntity(@NotNull Entity entity, @NotNull Runnable task);

    /**
     * Like {@link #runAtEntity(Entity, Runnable)} but runs {@code retired} instead
     * if the entity is removed before the task can execute (Folia). Paper runs the
     * task on the main thread and never calls {@code retired} unless the plugin
     * is disabled.
     */
    default @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task,
                                            @Nullable final Runnable retired) {
        return runAtEntity(entity, task);
    }

    /**
     * Run a task on the region that owns the given chunk.
     *
     * @param world             the world.
     * @param chunkX            chunk X.
     * @param chunkZ            chunk Z.
     * @param task              the task.
     * @param onScheduleFailure optional callback when the task could not be queued.
     * @return a handle.
     */
    default @NotNull TaskHandle runAtChunk(@NotNull final World world, final int chunkX, final int chunkZ,
                                           @NotNull final Runnable task,
                                           @Nullable final Runnable onScheduleFailure) {
        return runAtLocation(new Location(world, (chunkX << 4) + 8, 0, (chunkZ << 4) + 8), task, onScheduleFailure);
    }

    /**
     * @return true if the calling thread may touch the given chunk right now
     *         (Folia: owning region thread; Paper: main thread).
     */
    boolean isOwnedByCurrentRegion(@NotNull World world, int chunkX, int chunkZ);

    /**
     * Run a task on the global region scheduler (Folia) or main thread (Paper).
     * Use sparingly — prefer region-specific scheduling.
     *
     * @param task the task.
     * @return a handle.
     */
    @NotNull TaskHandle runGlobal(@NotNull Runnable task);

    /**
     * Run a task on the global region scheduler after a delay.
     *
     * @param task        the task.
     * @param delayTicks  the delay in ticks.
     * @return a handle.
     */
    @NotNull TaskHandle runGlobalDelayed(@NotNull Runnable task, long delayTicks);

    /**
     * Run a repeating task on the global region scheduler.
     *
     * @param task        the task.
     * @param delayTicks  the initial delay.
     * @param periodTicks the period.
     * @return a handle.
     */
    @NotNull TaskHandle runGlobalRepeating(@NotNull Runnable task, long delayTicks, long periodTicks);

    /**
     * Check whether a chunk at the given coordinates is loaded, without forcing load.
     *
     * @param world the world.
     * @param x     block X.
     * @param z     block Z.
     * @return true if the chunk is loaded.
     */
    boolean isChunkLoaded(@NotNull World world, int x, int z);

    /**
     * Ensure a chunk is loaded (async on Folia, sync on Paper). The callback runs
     * on the appropriate thread once loaded.
     *
     * @param world    the world.
     * @param chunkX   chunk X.
     * @param chunkZ   chunk Z.
     * @param callback runs after load (may run on region thread).
     */
    void ensureChunkLoaded(@NotNull World world, int chunkX, int chunkZ, @NotNull Runnable callback);

    /**
     * Cancel every task scheduled through this adapter. Used on plugin disable.
     */
    void cancelOwnedTasks();

    /**
     * @return a human-readable identifier of the implementation ("Paper" or "Folia").
     */
    @NotNull String implementationName();

    /**
     * Helper: convert ticks to milliseconds.
     *
     * @param ticks the tick count.
     * @return milliseconds.
     */
    static long ticksToMillis(final long ticks) {
        return Math.max(0, ticks) * 50L;
    }

    /**
     * Helper: convert ticks to a {@link TimeUnit#MILLISECONDS} value.
     */
    static long ticksToMillisUnit(final long ticks) {
        return ticksToMillis(ticks);
    }
}
