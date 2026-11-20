/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
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
 * <p>Folia replaces the single main thread with region threads, so work is
 * routed through the four schedulers that ship in the Paper API:</p>
 * <ul>
 *   <li>{@code RegionScheduler} for block access at a chunk.</li>
 *   <li>{@code EntityScheduler} for entities / players (follows them across regions).</li>
 *   <li>{@code AsyncScheduler} for off-thread IO and compression.</li>
 *   <li>{@code GlobalRegionScheduler} for global, non-location work.</li>
 * </ul>
 *
 * <p>All calls are direct API calls (the scheduler interfaces are part of the
 * Paper API), so there is no reflection on the hot path.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class FoliaSchedulerAdapter implements SchedulerAdapter {

    private final Plugin plugin;

    /** Repeating tasks we own (for shutdown cancellation). One-shots are never retained. */
    private final Set<ScheduledTask> owned = ConcurrentHashMap.newKeySet();

    public FoliaSchedulerAdapter(@NotNull final Plugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Async
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAsync(@NotNull final Runnable task) {
        return handle(Bukkit.getAsyncScheduler().runNow(plugin, wrap(task)), false);
    }

    @Override
    public @NotNull TaskHandle runAsyncDelayed(@NotNull final Runnable task, final long delayTicks) {
        if (delayTicks <= 0L) {
            return runAsync(task);
        }
        return handle(Bukkit.getAsyncScheduler().runDelayed(plugin, wrap(task),
                SchedulerAdapter.ticksToMillis(delayTicks), TimeUnit.MILLISECONDS), false);
    }

    @Override
    public @NotNull TaskHandle runAsyncRepeating(@NotNull final Runnable task,
                                                 final long delayTicks, final long periodTicks) {
        final long delayMs = Math.max(1L, SchedulerAdapter.ticksToMillis(delayTicks));
        final long periodMs = Math.max(50L, SchedulerAdapter.ticksToMillis(periodTicks));
        return handle(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, wrap(task),
                delayMs, periodMs, TimeUnit.MILLISECONDS), true);
    }

    // -----------------------------------------------------------------------
    // Region / entity
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location,
                                             @NotNull final Runnable task,
                                             @Nullable final Runnable onScheduleFailure) {
        final World world = location.getWorld();
        if (world == null) {
            plugin.getSLF4JLogger().error("runAtLocation called with a world-less location: {}", location);
            fail(onScheduleFailure);
            return TaskHandle.NOOP;
        }
        try {
            return handle(Bukkit.getRegionScheduler().run(plugin, world,
                    location.getBlockX() >> 4, location.getBlockZ() >> 4, wrap(task)), false);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Could not schedule region task at {},{} in {}",
                    location.getBlockX() >> 4, location.getBlockZ() >> 4, world.getName(), t);
            fail(onScheduleFailure);
            return TaskHandle.NOOP;
        }
    }

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task,
                                           @Nullable final Runnable retired) {
        try {
            final ScheduledTask scheduled = entity.getScheduler().run(plugin, wrap(task),
                    retired == null ? null : safe(retired));
            if (scheduled == null) {
                // Entity already removed — the scheduler will never run anything.
                if (retired != null) {
                    safe(retired).run();
                }
                return TaskHandle.NOOP;
            }
            return handle(scheduled, false);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("Could not schedule entity task for {}", entity, t);
            if (retired != null) {
                safe(retired).run();
            }
            return TaskHandle.NOOP;
        }
    }

    // -----------------------------------------------------------------------
    // Global region
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TaskHandle runGlobal(@NotNull final Runnable task) {
        return handle(Bukkit.getGlobalRegionScheduler().run(plugin, wrap(task)), false);
    }

    @Override
    public @NotNull TaskHandle runGlobalDelayed(@NotNull final Runnable task, final long delayTicks) {
        if (delayTicks <= 0L) {
            return runGlobal(task);
        }
        return handle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, wrap(task), delayTicks), false);
    }

    @Override
    public @NotNull TaskHandle runGlobalRepeating(@NotNull final Runnable task,
                                                  final long delayTicks, final long periodTicks) {
        // Folia rejects an initial delay or period below one tick.
        return handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, wrap(task),
                Math.max(1L, delayTicks), Math.max(1L, periodTicks)), true);
    }

    // -----------------------------------------------------------------------
    // Ownership / chunks
    // -----------------------------------------------------------------------

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull final World world, final int chunkX, final int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    @Override
    public void ensureChunkLoaded(@NotNull final World world, final int chunkX, final int chunkZ,
                                  @NotNull final Runnable callback) {
        final Location at = new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
        world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, err) -> {
            if (err != null) {
                plugin.getSLF4JLogger().warn("Async chunk load failed at {},{}", chunkX, chunkZ, err);
            }
            runAtLocation(at, callback, callback);
        });
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
                // best effort during shutdown
            }
        }
        owned.clear();
        try {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } catch (final Throwable ignored) {
            // server may already be tearing down schedulers
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
            return TaskHandle.NOOP;
        }
        if (keepUntilCancel) {
            owned.add(task);
        }
        return new TaskHandle() {
            @Override
            public void cancel() {
                owned.remove(task);
                try {
                    task.cancel();
                } catch (final Throwable ignored) {
                    // already cancelled / finished
                }
            }

            @Override
            public @NotNull Object raw() {
                return task;
            }
        };
    }

    private void fail(@Nullable final Runnable onScheduleFailure) {
        if (onScheduleFailure == null) {
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, wrap(onScheduleFailure));
        } catch (final Throwable t) {
            // Plugin is disabling: the failure hook only releases flags, so run it here.
            safe(onScheduleFailure).run();
        }
    }

    private @NotNull Consumer<ScheduledTask> wrap(@NotNull final Runnable r) {
        final Runnable safe = safe(r);
        return ignored -> safe.run();
    }

    private @NotNull Runnable safe(@NotNull final Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().error("Unhandled exception in scheduled task", t);
            }
        };
    }
}
