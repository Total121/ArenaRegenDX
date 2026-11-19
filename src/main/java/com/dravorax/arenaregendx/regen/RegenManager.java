/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.regen;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.regen.RegenSpeed;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.snapshot.Snapshot;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages the regeneration queue with adaptive TPS-based throttling.
 *
 * <h2>Queue model</h2>
 * <p>A single global repeating task (period = 1 tick) drives the queue. Each tick:</p>
 * <ol>
 *   <li>Read the adaptive block budget from {@link TPSMonitor#adaptiveBlockBudget()}.</li>
 *   <li>If a batch from the head task is in-flight, skip (wait for completion).</li>
 *   <li>Otherwise, dispatch a new batch of up to {@code budget} blocks from the head task.</li>
 *   <li>When the head task completes, pop it and start the next.</li>
 * </ol>
 *
 * <p>This guarantees the server thread is never overwhelmed: at most one batch
 * (≤ budget blocks) is in flight at any time, and the budget scales down
 * automatically as TPS drops.</p>
 *
 * <h2>Concurrency</h2>
 * <p>Only one task runs at a time (serial queue). Concurrent regeneration of
 * multiple arenas would compete for the same budget; serializing keeps TPS
 * stable. If parallelism is needed, multiple queues can be added later.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class RegenManager {

    private final ArenaRegenDXPlugin plugin;
    private final SnapshotManager snapshotManager;
    private final ArenaManager arenaManager;
    private final TPSMonitor tpsMonitor;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;

    /** The queue of pending tasks (FIFO). */
    private final ConcurrentLinkedQueue<ActiveJob> queue = new ConcurrentLinkedQueue<>();

    /** Currently-executing job (null if idle). */
    private volatile ActiveJob current;

    /** True if a batch from {@link #current} is in-flight (awaiting completion). */
    private volatile boolean batchInFlight = false;

    /** All known active jobs (queue + current), keyed by job id, for status queries. */
    private final ConcurrentMap<UUID, ActiveJob> allJobs = new ConcurrentHashMap<>();

    /** The repeating driver task. */
    private com.dravorax.arenaregendx.scheduler.TaskHandle driver;

    /**
     * Arenas that have a restore in-flight or about to be queued.
     * Value is a generation token invalidated by {@link #cancel(String)}.
     */
    private final ConcurrentMap<String, UUID> starting = new ConcurrentHashMap<>();

    public RegenManager(@NotNull final ArenaRegenDXPlugin plugin,
                        @NotNull final SnapshotManager snapshotManager,
                        @NotNull final ArenaManager arenaManager,
                        @NotNull final TPSMonitor tpsMonitor,
                        @NotNull final ConfigManager configManager,
                        @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.snapshotManager = snapshotManager;
        this.arenaManager = arenaManager;
        this.tpsMonitor = tpsMonitor;
        this.configManager = configManager;
        this.scheduler = scheduler;
    }

    /**
     * Start the queue driver. Called once during plugin enable.
     */
    public void start() {
        if (driver != null) {
            return;
        }
        // Apply TPS thresholds from config.
        final var p = configManager.performance();
        tpsMonitor.configure(p.maxBlockBudget, p.tpsFull, p.tpsThrottle, p.tpsCrawl, p.maxTickMillis);
        driver = scheduler.runGlobalRepeating(this::tick, 1L, 1L);
        plugin.getSLF4JLogger().info("Restore driver started ({} blocks/tick, {}ms budget @ 20 TPS).",
                p.maxBlockBudget, p.maxTickMillis);
    }

    /**
     * Per-tick driver: dispatch the next batch if idle.
     */
    private void tick() {
        if (batchInFlight) {
            return; // wait for the in-flight batch
        }
        // Pop the next job if the current is done.
        if (current != null && current.task.isDone()) {
            current = null;
        }
        if (current == null) {
            current = queue.poll();
            if (current == null) {
                return; // queue empty
            }
        }
        // Dispatch the next batch.
        batchInFlight = true;
        final int speedPercent = Math.max(10, Math.min(500, current.speedPercent));
        final double speedMul = speedPercent / 100.0;
        // Floor before multiply so slow presets actually reduce throughput.
        final int budget = Math.max(1, (int) Math.round(
                Math.max(1, tpsMonitor.adaptiveBlockBudget()) * speedMul));
        final long nanoBudget = Math.max(50_000L, (long) (
                Math.max(50_000L, tpsMonitor.adaptiveNanosBudget()) * speedMul));
        final ActiveJob job = current;
        try {
            job.task.applyBatch(budget, nanoBudget, () -> batchInFlight = false);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Regen batch dispatch failed for job {}", job.id, t);
            batchInFlight = false;
            job.task.markFailed();
            if (!job.future.isDone()) {
                job.future.completeExceptionally(t);
            }
            allJobs.remove(job.id);
            starting.remove(job.arenaName.toLowerCase(java.util.Locale.ROOT), job.token);
            current = null;
        }
    }

    /**
     * Submit a regeneration job for an arena using its latest snapshot.
     *
     * @param arenaName the arena name.
     * @param mode      the regeneration mode.
     * @param safeMode  if true, teleport entities out of voxels before placing solid blocks.
     * @return a future completed when regeneration finishes.
     */
    public @NotNull CompletableFuture<RegenTask> regen(@NotNull final String arenaName,
                                                       @NotNull final RegenMode mode,
                                                       final boolean safeMode) {
        return regen(arenaName, mode, safeMode, false);
    }

    /**
     * @param allowDirty if true, restore only tracked dirty voxels when that set
     *                   is warm. Manual / GUI regen must pass {@code false} so the
     *                   full snapshot is always written.
     */
    public @NotNull CompletableFuture<RegenTask> regen(@NotNull final String arenaName,
                                                       @NotNull final RegenMode mode,
                                                       final boolean safeMode,
                                                       final boolean allowDirty) {
        final CompletableFuture<RegenTask> future = new CompletableFuture<>();
        final Arena arena = arenaManager.getArena(arenaName);
        if (arena == null) {
            future.completeExceptionally(new IllegalArgumentException("Arena not found: " + arenaName));
            return future;
        }
        final String key = arena.name().toLowerCase(java.util.Locale.ROOT);
        final UUID token = UUID.randomUUID();
        if (isRegenerating(arena.name()) || starting.putIfAbsent(key, token) != null) {
            future.completeExceptionally(new IllegalStateException("Arena already regenerating: " + arenaName));
            return future;
        }
        if (arena.latestSnapshotId() <= 0) {
            starting.remove(key, token);
            future.completeExceptionally(new IllegalStateException(
                    "Arena has no snapshot: " + arenaName + ". Delete and recreate it to auto-save."));
            return future;
        }
        if (arena.status() == com.dravorax.arenaregendx.arena.ArenaStatus.SAVING) {
            starting.remove(key, token);
            future.completeExceptionally(new IllegalStateException(
                    "Arena is currently saving a snapshot: " + arenaName));
            return future;
        }
        final org.bukkit.World world = org.bukkit.Bukkit.getWorld(arena.worldName());
        if (world == null) {
            starting.remove(key, token);
            future.completeExceptionally(new IllegalStateException("World not loaded: " + arena.worldName()));
            return future;
        }
        snapshotManager.loadSnapshot(arenaName, arena.latestSnapshotId()).whenComplete((snapshot, err) -> {
            if (!token.equals(starting.get(key))) {
                if (!future.isDone()) {
                    future.completeExceptionally(new RuntimeException("Regen cancelled"));
                }
                return;
            }
            if (err != null || snapshot == null) {
                starting.remove(key, token);
                future.completeExceptionally(err != null ? err : new IllegalStateException("Snapshot load returned null"));
                return;
            }
            if (arenaManager.getArena(arena.name()) == null) {
                starting.remove(key, token);
                future.completeExceptionally(new IllegalStateException("Arena deleted during snapshot load: " + arenaName));
                return;
            }
            // Hop onto the arena's region before any Bukkit player/world API.
            final org.bukkit.Location origin = new org.bukkit.Location(
                    world, arena.originX(), arena.originY(), arena.originZ());
            scheduler.runAtLocation(origin, () -> {
                if (!token.equals(starting.get(key)) || arenaManager.getArena(arena.name()) == null) {
                    starting.remove(key, token);
                    if (!future.isDone()) {
                        future.completeExceptionally(new RuntimeException("Regen cancelled"));
                    }
                    return;
                }
                enqueueLoaded(arena, world, snapshot, mode, safeMode, allowDirty, key, token, future);
            }, () -> {
                starting.remove(key, token);
                if (!future.isDone()) {
                    future.completeExceptionally(new IllegalStateException(
                            "Could not schedule regen on arena region (Folia schedule failed)"));
                }
            });
        });
        return future;
    }

    private void enqueueLoaded(@NotNull final Arena arena,
                               @NotNull final org.bukkit.World world,
                               @NotNull final Snapshot snapshot,
                               @NotNull final RegenMode mode,
                               final boolean safeMode,
                               final boolean allowDirty,
                               @NotNull final String key,
                               @NotNull final UUID token,
                               @NotNull final CompletableFuture<RegenTask> future) {
        try {
            teleportPlayersToSpawn(arena);
            final long regenStart = System.currentTimeMillis();
            snapshot.resolvePalette();
            final var perf = configManager.performance();
            plugin.changeTracker().setEnabled(perf.dirtyTracking);
            plugin.changeTracker().setFullThreshold(perf.dirtyFullThreshold);
            final java.util.List<Long> dirty = (allowDirty && perf.dirtyTracking)
                    ? plugin.changeTracker().snapshotForRestore(arena)
                    : null;
            final int parallel = com.dravorax.arenaregendx.scheduler.SchedulerProvider.isFolia()
                    ? perf.parallelChunks : 1;
            final RegenProgressBar bar = perf.progressBar ? new RegenProgressBar(arena.name(), scheduler) : null;
            if (bar != null) {
                arenaManager.forEachPlayerInArena(arena, p -> bar.show(java.util.List.of(p)));
            }
            arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.REGENERATING);
            playArenaSound(arena, configManager.sounds().regenStart);
            final int speedPercent = RegenSpeed.resolve(
                    arena.regenSpeedPercent(), perf.regenSpeedPercent);
            final RegenTask task = new RegenTask(plugin, scheduler, snapshot, world,
                    arena.originX(), arena.originY(), arena.originZ(),
                    mode, safeMode, arena.exclusionIndex(),
                    perf.deltaSkip, dirty, parallel,
                    t -> completeJob(arena, key, token, bar, regenStart, t),
                    progress -> {
                        if (bar != null) bar.progress(progress);
                    });
            // Insert first, then re-validate. cancel() clears `starting` before
            // the job exists; without a post-insert check the job can survive.
            final ActiveJob job = new ActiveJob(UUID.randomUUID(), arena.name(), task, future, bar, token, speedPercent);
            allJobs.put(job.id, job);
            queue.add(job);
            if (!token.equals(starting.get(key)) || arenaManager.getArena(arena.name()) == null) {
                queue.remove(job);
                allJobs.remove(job.id);
                job.task.cancel();
                if (bar != null) {
                    bar.hideRemaining();
                }
                starting.remove(key, token);
                if (arenaManager.getArena(arena.name()) != null) {
                    arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.READY);
                }
                if (!future.isDone()) {
                    future.completeExceptionally(new RuntimeException("Regen cancelled"));
                }
                return;
            }
            plugin.getSLF4JLogger().info("Queued regen '{}' mode={} speed={}%", arena.name(), mode, speedPercent);
        } catch (final Throwable t) {
            starting.remove(key, token);
            arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.ERROR);
            future.completeExceptionally(t);
        }
    }

    private void completeJob(@NotNull final Arena arena,
                             @NotNull final String key,
                             @NotNull final UUID token,
                             @Nullable final RegenProgressBar bar,
                             final long regenStart,
                             @NotNull final RegenTask t) {
        if (bar != null) {
            bar.hideRemaining();
        }
        starting.remove(key, token);
        final ActiveJob j = findByTask(t);
        if (t.isCancelled()) {
            if (arenaManager.getArena(arena.name()) != null
                    && arena.status() == com.dravorax.arenaregendx.arena.ArenaStatus.REGENERATING) {
                arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.READY);
            }
            if (j != null) {
                if (!j.future.isDone()) {
                    j.future.completeExceptionally(new RuntimeException("Regen cancelled"));
                }
                allJobs.remove(j.id);
            }
            return;
        }
        if (t.isFailed()) {
            if (arenaManager.getArena(arena.name()) != null) {
                arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.ERROR);
                arenaManager.saveArena(arena);
            }
            if (j != null) {
                if (!j.future.isDone()) {
                    j.future.completeExceptionally(new RuntimeException("Regen failed"));
                }
                allJobs.remove(j.id);
            }
            return;
        }
        // Success — only mutate/persist if the arena still exists under this name.
        if (arenaManager.getArena(arena.name()) == null) {
            if (j != null) {
                if (!j.future.isDone()) {
                    j.future.complete(t);
                }
                allJobs.remove(j.id);
            }
            return;
        }
        plugin.changeTracker().clear(arena.name());
        arena.markRegenerated();
        arenaManager.saveArena(arena);
        playArenaSound(arena, configManager.sounds().regenComplete);
        final long duration = System.currentTimeMillis() - regenStart;
        final int applied = t.blocksApplied();
        scheduler.runGlobal(() -> {
            try {
                final var event = new com.dravorax.arenaregendx.api.events.ArenaRegenCompleteEvent(
                        arena, applied, duration);
                org.bukkit.Bukkit.getPluginManager().callEvent(event);
            } catch (final Throwable ex) {
                plugin.getSLF4JLogger().warn("ArenaRegenCompleteEvent dispatch failed", ex);
            }
        });
        if (j != null) {
            if (!j.future.isDone()) {
                j.future.complete(t);
            }
            allJobs.remove(j.id);
        }
    }

    private void playArenaSound(@NotNull final Arena arena, @NotNull final String soundName) {
        final var sounds = configManager.sounds();
        if (!sounds.enabled) return;
        final org.bukkit.Sound sound = com.dravorax.arenaregendx.util.RegistryUtil.soundFromString(soundName);
        if (sound == null) return;
        arenaManager.forEachPlayerInArena(arena, player -> {
            try {
                player.playSound(player.getLocation(), sound, sounds.volume, sounds.pitch);
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("playSound failed for {}", player.getName(), t);
            }
        });
    }

    private @Nullable ActiveJob findByTask(@NotNull final RegenTask task) {
        for (final ActiveJob j : allJobs.values()) {
            if (j.task == task) return j;
        }
        return null;
    }

    /**
     * Teleport all players inside the arena to the arena's spawn point (if set).
     * This prevents players from getting stuck inside blocks during regeneration.
     */
    private void teleportPlayersToSpawn(@NotNull final com.dravorax.arenaregendx.arena.Arena arena) {
        if (!arena.hasSpawn()) {
            return; // no spawn set — players stay where they are
        }
        final org.bukkit.World world = org.bukkit.Bukkit.getWorld(arena.worldName());
        if (world == null) return;
        final org.bukkit.Location spawn = new org.bukkit.Location(
                world, arena.spawnX(), arena.spawnY(), arena.spawnZ(),
                arena.spawnYaw(), arena.spawnPitch());
        arenaManager.forEachPlayerInArena(arena, player -> {
            try {
                player.teleport(spawn);
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().warn("Could not teleport {} to spawn", player.getName(), t);
            }
        });
    }

    /**
     * @return the number of jobs waiting in the queue (excluding the running one).
     */
    public int queueSize() {
        return queue.size();
    }

    /**
     * @return the number of active jobs (queue + running).
     */
    public int activeCount() {
        return allJobs.size();
    }

    /**
     * Cancel a running or queued job for an arena.
     */
    public boolean cancel(@NotNull final String arenaName) {
        final String key = arenaName.toLowerCase(java.util.Locale.ROOT);
        // Invalidate any in-flight snapshot-load / enqueue that still holds starting.
        boolean any = starting.remove(key) != null;
        final java.util.concurrent.atomic.AtomicBoolean cancelledQueued =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        queue.removeIf(job -> {
            if (!job.arenaName.equalsIgnoreCase(arenaName)) return false;
            job.task.cancel();
            if (job.bar != null) job.bar.hideRemaining();
            if (!job.future.isDone()) {
                job.future.completeExceptionally(new RuntimeException("Regen cancelled"));
            }
            allJobs.remove(job.id);
            cancelledQueued.set(true);
            return true;
        });
        any |= cancelledQueued.get();
        for (final ActiveJob job : List.copyOf(allJobs.values())) {
            if (job.arenaName.equalsIgnoreCase(arenaName)) {
                job.task.cancel();
                if (job.bar != null) job.bar.hideRemaining();
                if (!job.future.isDone()) {
                    job.future.completeExceptionally(new RuntimeException("Regen cancelled"));
                }
                allJobs.remove(job.id);
                any = true;
            }
        }
        if (current != null && current.arenaName.equalsIgnoreCase(arenaName)) {
            current.task.cancel();
            if (current.bar != null) current.bar.hideRemaining();
            // Do NOT clear batchInFlight here — the in-flight region callback
            // still owns it and will clear it in its finally block.
            current = null;
            any = true;
        }
        final Arena arena = arenaManager.getArena(arenaName);
        if (arena != null && arena.status() == com.dravorax.arenaregendx.arena.ArenaStatus.REGENERATING) {
            arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.READY);
            arenaManager.saveArena(arena);
        }
        return any;
    }

    /**
     * @return true if a regeneration is currently running.
     */
    public boolean isRegenerating() {
        return current != null && !current.task.isDone();
    }

    public boolean isRegenerating(@NotNull final String arenaName) {
        if (starting.containsKey(arenaName.toLowerCase(java.util.Locale.ROOT))) {
            return true;
        }
        for (final ActiveJob job : allJobs.values()) {
            if (job.arenaName.equalsIgnoreCase(arenaName) && !job.task.isDone()) {
                return true;
            }
        }
        return current != null
                && current.arenaName.equalsIgnoreCase(arenaName)
                && !current.task.isDone();
    }

    /**
     * Cancel all queued jobs and abort the running one (best-effort).
     */
    public void cancelAll() {
        starting.clear();
        for (final ActiveJob job : List.copyOf(allJobs.values())) {
            job.task.cancel();
            if (job.bar != null) job.bar.hideRemaining();
            if (!job.future.isDone()) {
                job.future.completeExceptionally(new RuntimeException("Regen cancelled"));
            }
            final Arena arena = arenaManager.getArena(job.arenaName);
            if (arena != null && arena.status() == com.dravorax.arenaregendx.arena.ArenaStatus.REGENERATING) {
                arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.READY);
            }
        }
        queue.clear();
        allJobs.clear();
        current = null;
        // Leave batchInFlight to the in-flight region finally; forcing false here
        // can let the driver start another job while a cancelled batch still runs.
    }

    /**
     * Shutdown: stop the driver and abort in-flight work cleanly.
     */
    public void shutdown() {
        if (driver != null) {
            driver.cancel();
            driver = null;
        }
        starting.clear();
        for (final ActiveJob job : List.copyOf(allJobs.values())) {
            try {
                job.task.cancel();
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("Error cancelling regen job during shutdown", t);
            }
            if (job.bar != null) job.bar.hideRemaining();
            if (!job.future.isDone()) {
                job.future.completeExceptionally(new RuntimeException("Plugin shutting down"));
            }
            final Arena arena = arenaManager.getArena(job.arenaName);
            if (arena != null && arena.status() == com.dravorax.arenaregendx.arena.ArenaStatus.REGENERATING) {
                arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.READY);
            }
        }
        allJobs.clear();
        queue.clear();
        current = null;
        batchInFlight = false;
        plugin.getSLF4JLogger().info("Regeneration queue shut down.");
    }

    /**
     * Active job record.
     */
    private static final class ActiveJob {
        final UUID id;
        final String arenaName;
        final RegenTask task;
        final CompletableFuture<RegenTask> future;
        final RegenProgressBar bar;
        final UUID token;
        final int speedPercent;

        ActiveJob(final UUID id, final String arenaName, final RegenTask task,
                  final CompletableFuture<RegenTask> future,
                  @Nullable final RegenProgressBar bar,
                  final UUID token,
                  final int speedPercent) {
            this.id = id;
            this.arenaName = arenaName;
            this.task = task;
            this.future = future;
            this.bar = bar;
            this.token = token;
            this.speedPercent = speedPercent;
        }
    }
}
