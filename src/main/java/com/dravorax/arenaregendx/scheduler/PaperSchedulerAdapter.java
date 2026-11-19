/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CopyOnWriteArraySet;
import java.util.Set;

/**
 * Paper implementation of {@link SchedulerAdapter}.
 *
 * <p>On Paper, there is a single main thread, so {@code runAtLocation},
 * {@code runAtEntity} and {@code runGlobal} all dispatch to the main thread via
 * {@code BukkitScheduler}. Async tasks use {@code runTaskAsynchronously}.</p>
 *
 * <p>Chunk loading uses the modern Paper {@code World#getChunkAtAsync} when
 * available; otherwise falls back to sync loading.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class PaperSchedulerAdapter implements SchedulerAdapter {

    private final Plugin plugin;
    /** Tracks repeating tasks for targeted cancel; one-shots rely on cancelTasks(plugin). */
    private final Set<BukkitTask> owned = new CopyOnWriteArraySet<>();

    public PaperSchedulerAdapter(@NotNull final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull TaskHandle runAsync(@NotNull final Runnable task) {
        final BukkitTask t = Bukkit.getScheduler().runTaskAsynchronously(plugin, safe(task));
        return handle(t, false);
    }

    @Override
    public @NotNull TaskHandle runAsyncDelayed(@NotNull final Runnable task, final long delayTicks) {
        final BukkitTask t = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, safe(task), delayTicks);
        return handle(t, false);
    }

    @Override
    public @NotNull TaskHandle runAsyncRepeating(@NotNull final Runnable task, final long delayTicks, final long periodTicks) {
        final BukkitTask t = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, safe(task), delayTicks, periodTicks);
        return handle(t, true);
    }

    @Override
    public @NotNull TaskHandle runAtLocation(@NotNull final Location location, @NotNull final Runnable task) {
        final BukkitTask t = Bukkit.getScheduler().runTask(plugin, safe(task));
        return handle(t, false);
    }

    @Override
    public @NotNull TaskHandle runAtEntity(@NotNull final Entity entity, @NotNull final Runnable task) {
        // Paper: main thread.
        final BukkitTask t = Bukkit.getScheduler().runTask(plugin, safe(task));
        return handle(t, false);
    }

    @Override
    public @NotNull TaskHandle runGlobal(@NotNull final Runnable task) {
        final BukkitTask t = Bukkit.getScheduler().runTask(plugin, safe(task));
        return handle(t, false);
    }

    @Override
    public @NotNull TaskHandle runGlobalDelayed(@NotNull final Runnable task, final long delayTicks) {
        final BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, safe(task), delayTicks);
        return handle(t, false);
    }

    @Override
    public @NotNull TaskHandle runGlobalRepeating(@NotNull final Runnable task, final long delayTicks, final long periodTicks) {
        // Paper allows delay=0, but clamp to 1 for consistency with Folia.
        final long safeDelay = Math.max(1L, delayTicks);
        final long safePeriod = Math.max(1L, periodTicks);
        final BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, safe(task), safeDelay, safePeriod);
        return handle(t, true);
    }

    @Override
    public boolean isChunkLoaded(@NotNull final World world, final int x, final int z) {
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public void ensureChunkLoaded(@NotNull final World world, final int chunkX, final int chunkZ,
                                  @NotNull final Runnable callback) {
        // Paper: prefer the modern async chunk load if present.
        try {
            // World#getChunkAtAsync(int, int) returns a CompletableFuture on Paper 1.20+.
            final var method = world.getClass().getMethod("getChunkAtAsync", int.class, int.class);
            final Object future = method.invoke(world, chunkX, chunkZ);
            if (future instanceof java.util.concurrent.CompletableFuture<?> cf) {
                cf.thenAccept(ignored -> runGlobal(safe(callback)));
                return;
            }
        } catch (final Throwable ignored) {
            // fall through to sync load
        }
        // Fallback: load synchronously on main thread, then callback.
        runGlobal(() -> {
            world.getChunkAt(chunkX, chunkZ);
            callback.run();
        });
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

    private @NotNull TaskHandle handle(@NotNull final BukkitTask task, final boolean keepUntilCancel) {
        if (keepUntilCancel) {
            owned.add(task);
        }
        return new TaskHandle() {
            private volatile boolean cancelled;
            @Override public void cancel() {
                if (cancelled) return;
                cancelled = true;
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

    // Unused import guard for Chunk (referenced for documentation clarity).
    @SuppressWarnings("unused")
    private static final Class<?> CHUNK_TYPE = Chunk.class;
}
