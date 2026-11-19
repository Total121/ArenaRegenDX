/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Folia implementation of {@link SchedulerAdapter}.
 *
 * <p>Folia replaces Bukkit's single main thread with multiple region threads,
 * so work is routed through the four region-aware schedulers that ship in the
 * Paper API ({@code RegionScheduler}, {@code EntityScheduler},
 * {@code GlobalRegionScheduler} and {@code AsyncScheduler}).</p>
 *
 * <p>These interfaces are part of {@code paper-api} itself, so they are called
 * directly: no reflection, no per-call {@code Method.invoke} boxing, and
 * compile-time checked signatures.</p>
 *
 * <p>Entity work goes through the entity's own scheduler so it follows the
 * entity across region boundaries (the previous "run at the entity's current
 * location" shortcut read the location off-thread and could land on a region
 * that no longer owned the entity).</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class FoliaSchedulerAdapter implements SchedulerAdapter {

    private static final TaskHandle NOOP = new TaskHandle() {
        @Override public void cancel() { }
        @Override public @Nullable Object raw() { return null; }
    };

    private final Plugin plugin;

    /** Repeating tasks we own (for shutdown). One-shots are never retained. */
    private final Set<ScheduledTask> owned = ConcurrentHashMap.newKeySet();

    public FoliaSchedulerAdapter(@NotNull final Plugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Async
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAsync(@NotNull final Runnable task) {
        if (!plugin.isEnabled()) return runInline(task);
        return handle(Bukkit.getAsyncScheduler().runNow(plugin, wrap(task)), false);
    }

    @Override
    public @NotNull TaskHandle runAsyncDelayed(@NotNull final Runnable task, final long delayTicks) {
        if (!plugin.isEnabled()) return NOOP;
        final long ms = SchedulerAdapter.ticksToMillis(delayTicks);
        if (ms <= 0L) {
            return runAsync(task);
        }
        return handle(Bukkit.getAsyncScheduler().runDelayed(plugin, wrap(task), ms, TimeUnit.MILLISECONDS), false);
    }

    @Override
    public @NotNull TaskHandle runAsyncRepeating(@NotNull final Runnable task,
                                                  final long delayTicks, final long periodTicks) {
        if (!plugin.isEnabled()) return NOOP;
        final long delayMs = Math.max(50L, SchedulerAdapter.ticksToMillis(delayTicks));
        final long periodMs = Math.max(50L, SchedulerAdapter.ticksToMillis(periodTicks));
        return handle(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, wrap(task),
                delayMs, periodMs, TimeUnit.MILLISECONDS), true);
    }

    // -----------------------------------------------------------------------
    // Region (at location)
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location, @NotNull final Runnable task) {
        return runAtLocation(location, task, null);
    }

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location,
                                             @NotNull final Runnable task,
                                             @Nullable final Runnable onScheduleFailure) {
        final World world = location.getWorld();
        if (world == null) {
            plugin.getSLF4JLogger().error("Cannot schedule region task: location has no world");
            if (onScheduleFailure != null) runSafely(onScheduleFailure);
            return NOOP;
        }
        return runAtChunk(world, location.getBlockX() >> 4, location.getBlockZ() >> 4, task, onScheduleFailure);
    }

    @Override
    public @NotNull TaskHandle runAtChunk(@NotNull final World world, final int chunkX, final int chunkZ,
                                          @NotNull final Runnable task,
                                          @Nullable final Runnable onScheduleFailure) {
        if (!plugin.isEnabled()) {
            if (onScheduleFailure != null) runSafely(onScheduleFailure);
            return NOOP;
        }
        try {
            return handle(Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, wrap(task)), false);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Folia region schedule failed at chunk {},{}", chunkX, chunkZ, t);
            if (onScheduleFailure != null) runSafely(onScheduleFailure);
            return NOOP;
        }
    }

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task) {
        return runAtEntity(entity, task, null);
    }

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task,
                                           @Nullable final Runnable retired) {
        if (!plugin.isEnabled()) {
            return NOOP;
        }
        try {
            final ScheduledTask st = entity.getScheduler().run(plugin, wrap(task),
                    retired == null ? null : () -> runSafely(retired));
            if (st == null && retired != null) {
                // Entity already removed: run the retired callback so callers can clean up.
                runSafely(retired);
            }
            return st == null ? NOOP : handle(st, false);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("Entity schedule failed for {}", entity.getType(), t);
            if (retired != null) runSafely(retired);
            return NOOP;
        }
    }

    // -----------------------------------------------------------------------
    // Global region
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runGlobal(@NotNull final Runnable task) {
        if (!plugin.isEnabled()) {
            return Bukkit.isGlobalTickThread() ? runInline(task) : NOOP;
        }
        return handle(Bukkit.getGlobalRegionScheduler().run(plugin, wrap(task)), false);
    }

    @Override
    public @NotNull TaskHandle runGlobalDelayed(@NotNull final Runnable task, final long delayTicks) {
        if (!plugin.isEnabled()) return NOOP;
        return handle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, wrap(task),
                Math.max(1L, delayTicks)), false);
    }

    @Override
    public @NotNull TaskHandle runGlobalRepeating(@NotNull final Runnable task,
                                                   final long delayTicks, final long periodTicks) {
        if (!plugin.isEnabled()) return NOOP;
        return handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, wrap(task),
                Math.max(1L, delayTicks), Math.max(1L, periodTicks)), true);
    }

    // -----------------------------------------------------------------------
    // Chunks
    // -----------------------------------------------------------------------

    @Override
    public boolean isChunkLoaded(@NotNull final World world, final int x, final int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public void ensureChunkLoaded(@NotNull final World world, final int chunkX, final int chunkZ,
                                  @NotNull final Runnable callback) {
        world.getChunkAtAsync(chunkX, chunkZ).whenComplete((ignored, err) -> {
            if (err != null) {
                plugin.getSLF4JLogger().warn("Async chunk load failed at {},{}", chunkX, chunkZ, err);
            }
            runAtChunk(world, chunkX, chunkZ, callback, null);
        });
    }

    // -----------------------------------------------------------------------
    // Threading
    // -----------------------------------------------------------------------

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull final World world, final int chunkX, final int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    @Override
    public void cancelOwnedTasks() {
        for (final ScheduledTask task : owned.toArray(new ScheduledTask[0])) {
            try {
                task.cancel();
            } catch (final Throwable ignored) {
                // best-effort during shutdown
            }
        }
        owned.clear();
        try {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        } catch (final Throwable ignored) {
        }
        try {
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } catch (final Throwable ignored) {
        }
    }

    @Override
    public @NotNull String implementationName() {
        return "Folia";
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private @NotNull TaskHandle handle(@Nullable final ScheduledTask task, final boolean keepUntilCancel) {
        if (task == null) {
            return NOOP;
        }
        if (keepUntilCancel) {
            owned.add(task);
        }
        return new TaskHandle() {
            @Override public void cancel() {
                try {
                    task.cancel();
                } catch (final Throwable ignored) {
                }
                owned.remove(task);
            }
            @Override public @Nullable Object raw() { return task; }
        };
    }

    /** Exceptions are logged here so they never vanish inside Folia's executors. */
    private @NotNull Consumer<ScheduledTask> wrap(@NotNull final Runnable r) {
        return ignored -> runSafely(r);
    }

    private void runSafely(@NotNull final Runnable r) {
        try {
            r.run();
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Unhandled exception in scheduled task", t);
        }
    }

    /**
     * Schedulers reject tasks from a disabled plugin. During {@code onDisable}
     * cleanup work (persisting, hiding boss bars) runs inline, best-effort.
     */
    private @NotNull TaskHandle runInline(@NotNull final Runnable task) {
        runSafely(task);
        return NOOP;
    }
}
