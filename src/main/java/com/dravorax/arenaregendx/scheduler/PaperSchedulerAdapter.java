/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper implementation of {@link SchedulerAdapter}.
 *
 * <p>Paper has a single main thread, so region, entity and global work all go
 * through {@code BukkitScheduler}. Async work uses the Bukkit async pool.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class PaperSchedulerAdapter implements SchedulerAdapter {

    private final Plugin plugin;
    /** Repeating tasks for targeted cancel; one-shots rely on {@code cancelTasks(plugin)}. */
    private final Set<BukkitTask> owned = ConcurrentHashMap.newKeySet();

    public PaperSchedulerAdapter(@NotNull final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull TaskHandle runAsync(@NotNull final Runnable task) {
        return handle(Bukkit.getScheduler().runTaskAsynchronously(plugin, safe(task)), false);
    }

    @Override
    public @NotNull TaskHandle runAsyncDelayed(@NotNull final Runnable task, final long delayTicks) {
        return handle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, safe(task),
                Math.max(0L, delayTicks)), false);
    }

    @Override
    public @NotNull TaskHandle runAsyncRepeating(@NotNull final Runnable task, final long delayTicks,
                                                 final long periodTicks) {
        return handle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, safe(task),
                Math.max(0L, delayTicks), Math.max(1L, periodTicks)), true);
    }

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location, @NotNull final Runnable task,
                                             @Nullable final Runnable onScheduleFailure) {
        try {
            return handle(Bukkit.getScheduler().runTask(plugin, safe(task)), false);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Could not schedule task at {}", location, t);
            if (onScheduleFailure != null) {
                safe(onScheduleFailure).run();
            }
            return TaskHandle.NOOP;
        }
    }

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task,
                                           @Nullable final Runnable retired) {
        final Runnable body = safe(task);
        final Runnable onRetired = retired == null ? null : safe(retired);
        try {
            return handle(Bukkit.getScheduler().runTask(plugin, () -> {
                // Dead players are not "valid" but still receive messages / UI.
                final boolean alive = entity instanceof org.bukkit.entity.Player p ? p.isOnline() : entity.isValid();
                if (alive) {
                    body.run();
                } else if (onRetired != null) {
                    onRetired.run();
                }
            }), false);
        } catch (final Throwable t) {
            if (onRetired != null) {
                onRetired.run();
            }
            return TaskHandle.NOOP;
        }
    }

    @Override
    public @NotNull TaskHandle runGlobal(@NotNull final Runnable task) {
        return handle(Bukkit.getScheduler().runTask(plugin, safe(task)), false);
    }

    @Override
    public @NotNull TaskHandle runGlobalDelayed(@NotNull final Runnable task, final long delayTicks) {
        return handle(Bukkit.getScheduler().runTaskLater(plugin, safe(task), Math.max(0L, delayTicks)), false);
    }

    @Override
    public @NotNull TaskHandle runGlobalRepeating(@NotNull final Runnable task, final long delayTicks,
                                                  final long periodTicks) {
        // Clamp to one tick for parity with Folia.
        return handle(Bukkit.getScheduler().runTaskTimer(plugin, safe(task),
                Math.max(1L, delayTicks), Math.max(1L, periodTicks)), true);
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull final World world, final int chunkX, final int chunkZ) {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void ensureChunkLoaded(@NotNull final World world, final int chunkX, final int chunkZ,
                                  @NotNull final Runnable callback) {
        // Paper completes getChunkAtAsync on the main thread; always run the callback,
        // even when the load failed, so callers never hang.
        world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, err) -> {
            if (err != null) {
                plugin.getSLF4JLogger().warn("Async chunk load failed at {},{}", chunkX, chunkZ, err);
            }
            if (Bukkit.isPrimaryThread()) {
                safe(callback).run();
            } else {
                runGlobal(callback);
            }
        });
    }

    @Override
    public void cancelOwnedTasks() {
        for (final BukkitTask t : owned.toArray(new BukkitTask[0])) {
            try {
                t.cancel();
            } catch (final Throwable ignored) {
                // already cancelled
            }
        }
        owned.clear();
        Bukkit.getScheduler().cancelTasks(plugin);
    }

    @Override
    public @NotNull String implementationName() {
        return "Paper";
    }

    private @NotNull TaskHandle handle(@NotNull final BukkitTask task, final boolean keepUntilCancel) {
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
                    // already cancelled
                }
            }

            @Override
            public @NotNull Object raw() {
                return task;
            }
        };
    }

    /** Wrap a runnable so exceptions are logged instead of reaching the scheduler. */
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
