/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
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
 * <p>On Paper, there is a single main thread, so {@code runAtLocation},
 * {@code runAtEntity} and {@code runGlobal} all dispatch to the main thread via
 * {@code BukkitScheduler}. Async tasks use {@code runTaskAsynchronously}.</p>
 *
 * <p>While the plugin is disabled (i.e. during {@code onDisable}) the Bukkit
 * scheduler throws {@code IllegalPluginAccessException}; cleanup work is then
 * run inline instead (we are already on the main thread).</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class PaperSchedulerAdapter implements SchedulerAdapter {

    private static final TaskHandle NOOP = new TaskHandle() {
        @Override public void cancel() { }
        @Override public @Nullable Object raw() { return null; }
    };

    private final Plugin plugin;
    /** Tracks repeating tasks for targeted cancel; one-shots rely on cancelTasks(plugin). */
    private final Set<BukkitTask> owned = ConcurrentHashMap.newKeySet();

    public PaperSchedulerAdapter(@NotNull final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull TaskHandle runAsync(@NotNull final Runnable task) {
        if (!plugin.isEnabled()) return runInline(task);
        return handle(Bukkit.getScheduler().runTaskAsynchronously(plugin, safe(task)), false);
    }

    @Override
    public @NotNull TaskHandle runAsyncDelayed(@NotNull final Runnable task, final long delayTicks) {
        if (!plugin.isEnabled()) return NOOP;
        return handle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, safe(task), Math.max(0L, delayTicks)), false);
    }

    @Override
    public @NotNull TaskHandle runAsyncRepeating(@NotNull final Runnable task, final long delayTicks, final long periodTicks) {
        if (!plugin.isEnabled()) return NOOP;
        return handle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, safe(task),
                Math.max(0L, delayTicks), Math.max(1L, periodTicks)), true);
    }

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location, @NotNull final Runnable task) {
        return runSync(task);
    }

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location, @NotNull final Runnable task,
                                             @Nullable final Runnable onScheduleFailure) {
        if (!plugin.isEnabled()) {
            if (onScheduleFailure != null) safe(onScheduleFailure).run();
            return NOOP;
        }
        return runSync(task);
    }

    @Override
    public @NotNull TaskHandle runAtChunk(@NotNull final World world, final int chunkX, final int chunkZ,
                                          @NotNull final Runnable task,
                                          @Nullable final Runnable onScheduleFailure) {
        if (!plugin.isEnabled()) {
            if (onScheduleFailure != null) safe(onScheduleFailure).run();
            return NOOP;
        }
        return runSync(task);
    }

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task) {
        return runSync(task);
    }

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task,
                                           @Nullable final Runnable retired) {
        if (!plugin.isEnabled()) {
            return NOOP;
        }
        return runSync(task);
    }

    @Override
    public @NotNull TaskHandle runGlobal(@NotNull final Runnable task) {
        return runSync(task);
    }

    @Override
    public @NotNull TaskHandle runGlobalDelayed(@NotNull final Runnable task, final long delayTicks) {
        if (!plugin.isEnabled()) return NOOP;
        return handle(Bukkit.getScheduler().runTaskLater(plugin, safe(task), Math.max(0L, delayTicks)), false);
    }

    @Override
    public @NotNull TaskHandle runGlobalRepeating(@NotNull final Runnable task, final long delayTicks, final long periodTicks) {
        if (!plugin.isEnabled()) return NOOP;
        // Paper allows delay=0, but clamp to 1 for consistency with Folia.
        return handle(Bukkit.getScheduler().runTaskTimer(plugin, safe(task),
                Math.max(1L, delayTicks), Math.max(1L, periodTicks)), true);
    }

    @Override
    public boolean isChunkLoaded(@NotNull final World world, final int x, final int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public void ensureChunkLoaded(@NotNull final World world, final int chunkX, final int chunkZ,
                                  @NotNull final Runnable callback) {
        // whenComplete (not thenAccept) so a failed load still releases the caller.
        world.getChunkAtAsync(chunkX, chunkZ).whenComplete((ignored, err) -> {
            if (err != null) {
                plugin.getSLF4JLogger().warn("Async chunk load failed at {},{}", chunkX, chunkZ, err);
            }
            runSync(callback);
        });
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull final World world, final int chunkX, final int chunkZ) {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void cancelOwnedTasks() {
        for (final BukkitTask t : owned) {
            try {
                t.cancel();
            } catch (final IllegalStateException ignored) {
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

    // ---- helpers ----

    /** Main-thread dispatch; runs inline if we are already disabled (onDisable is on the main thread). */
    private @NotNull TaskHandle runSync(@NotNull final Runnable task) {
        if (!plugin.isEnabled()) {
            // Only safe inline on the main thread; an async completion racing
            // shutdown must not touch the world from its pool thread.
            return Bukkit.isPrimaryThread() ? runInline(task) : NOOP;
        }
        return handle(Bukkit.getScheduler().runTask(plugin, safe(task)), false);
    }

    private @NotNull TaskHandle runInline(@NotNull final Runnable task) {
        safe(task).run();
        return NOOP;
    }

    private @NotNull TaskHandle handle(@NotNull final BukkitTask task, final boolean keepUntilCancel) {
        if (keepUntilCancel) {
            owned.add(task);
        }
        return new TaskHandle() {
            @Override public void cancel() {
                try { task.cancel(); } catch (final IllegalStateException ignored) {}
                owned.remove(task);
            }
            @Override public @Nullable Object raw() { return task; }
        };
    }

    /** Wrap a runnable so exceptions never propagate to the scheduler (which would spam). */
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
