/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.regen;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.api.events.ArenaRegenCompleteEvent;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.arena.ArenaStatus;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.config.Settings;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.scheduler.SchedulerProvider;
import com.dravorax.arenaregendx.scheduler.TaskHandle;
import com.dravorax.arenaregendx.snapshot.Snapshot;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import com.dravorax.arenaregendx.util.RegistryUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Serial regeneration queue with adaptive, TPS-aware throttling.
 *
 * <h2>Queue model</h2>
 * <p>One global repeating task (period = 1 tick) drives the queue. Each tick it
 * reads the adaptive block / time budget from {@link TPSMonitor}, and if no
 * batch is in flight dispatches the next batch of the head job. When the head
 * job finishes the next one starts. At most one batch is in flight at a time,
 * so restore work can never pile up on the server thread.</p>
 *
 * <h2>Robustness</h2>
 * <p>Every batch carries a generation number. A watchdog fails a job whose
 * batch has been in flight for longer than {@value #STALL_TIMEOUT_SECONDS}
 * seconds (for example a region task lost during a Folia region shutdown), so
 * the queue never stalls forever; a late callback from the abandoned batch
 * cannot release a newer batch's slot.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class RegenManager {

    private static final long STALL_TIMEOUT_SECONDS = 60L;
    /** Never spend more than half a tick restoring, whatever the speed multiplier. */
    private static final long MAX_NANOS_PER_TICK = 25_000_000L;

    private final ArenaRegenDXPlugin plugin;
    private final SnapshotManager snapshotManager;
    private final ArenaManager arenaManager;
    private final TPSMonitor tpsMonitor;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;

    private final ConcurrentLinkedQueue<ActiveJob> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<UUID, ActiveJob> allJobs = new ConcurrentHashMap<>();
    /** Arenas with a restore in flight or being prepared; value = generation token. */
    private final ConcurrentMap<String, UUID> starting = new ConcurrentHashMap<>();

    private volatile ActiveJob current;
    private volatile boolean batchInFlight;
    private volatile long batchGeneration;
    private volatile long batchStartedNanos;
    private TaskHandle driver;

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

    /** Start the queue driver. Called once during plugin enable. */
    public void start() {
        if (driver != null) {
            return;
        }
        final Settings.PerformanceSettings p = configManager.performance();
        tpsMonitor.configure(p.maxBlockBudget, p.tpsFull, p.tpsThrottle, p.tpsCrawl, p.maxTickMillis);
        driver = scheduler.runGlobalRepeating(this::tick, 1L, 1L);
        plugin.getSLF4JLogger().info("Restore driver started ({} blocks/tick, {}ms budget at 20 TPS).",
                p.maxBlockBudget, p.maxTickMillis);
    }

    /** Per-tick driver: dispatch the next batch when idle. */
    private void tick() {
        if (batchInFlight) {
            final ActiveJob stalled = current;
            if (stalled != null && System.nanoTime() - batchStartedNanos > TimeUnit.SECONDS.toNanos(STALL_TIMEOUT_SECONDS)) {
                plugin.getSLF4JLogger().error("Restore of '{}' made no progress for {}s - aborting it.",
                        stalled.arenaName, STALL_TIMEOUT_SECONDS);
                batchGeneration++;          // ignore the abandoned batch's callback
                batchInFlight = false;
                stalled.task.markFailed();
                current = null;
            }
            return;
        }
        ActiveJob job = current;
        if (job != null && job.task.isDone()) {
            job = null;
            current = null;
        }
        if (job == null) {
            job = queue.poll();
            if (job == null) {
                return;
            }
            current = job;
        }
        final double speed = Math.max(RegenSpeed.MIN, Math.min(RegenSpeed.MAX, job.speedPercent)) / 100.0;
        final int blockBudget = (int) Math.max(1L, Math.round(Math.max(1, tpsMonitor.adaptiveBlockBudget()) * speed));
        final long nanoBudget = Math.min(MAX_NANOS_PER_TICK,
                Math.max(50_000L, (long) (tpsMonitor.adaptiveNanosBudget() * speed)));

        final long generation = ++batchGeneration;
        batchInFlight = true;
        batchStartedNanos = System.nanoTime();
        final ActiveJob dispatched = job;
        try {
            dispatched.task.applyBatch(blockBudget, nanoBudget, () -> {
                if (batchGeneration == generation) {
                    batchInFlight = false;
                }
            });
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Regen batch dispatch failed for '{}'", dispatched.arenaName, t);
            if (batchGeneration == generation) {
                batchInFlight = false;
            }
            dispatched.task.markFailed();
            current = null;
        }
    }

    // -----------------------------------------------------------------------
    // Submission
    // -----------------------------------------------------------------------

    /** Regenerate an arena from its active snapshot (full restore). */
    public @NotNull CompletableFuture<RegenTask> regen(@NotNull final String arenaName,
                                                       @NotNull final RegenMode mode,
                                                       final boolean safeMode) {
        return regen(arenaName, mode, safeMode, false);
    }

    /**
     * @param allowDirty if true, restore only tracked dirty voxels when the
     *                   tracker trusts its record for this arena. Manual / GUI
     *                   restores pass {@code false} so the full snapshot is applied.
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
        final String key = key(arena.name());
        final UUID token = UUID.randomUUID();
        if (isRegenerating(arena.name()) || starting.putIfAbsent(key, token) != null) {
            future.completeExceptionally(new IllegalStateException("Arena " + arena.name() + " is already regenerating."));
            return future;
        }
        final String rejection = arena.latestSnapshotId() <= 0
                ? "Arena " + arena.name() + " has no snapshot. Run /ardx save " + arena.name() + " first."
                : arena.status() == ArenaStatus.SAVING
                ? "Arena " + arena.name() + " is saving a snapshot; try again shortly."
                : Bukkit.getWorld(arena.worldName()) == null
                ? "World not loaded: " + arena.worldName()
                : null;
        if (rejection != null) {
            starting.remove(key, token);
            future.completeExceptionally(new IllegalStateException(rejection));
            return future;
        }
        final World world = Bukkit.getWorld(arena.worldName());
        snapshotManager.loadSnapshot(arena.name(), arena.latestSnapshotId()).whenComplete((snapshot, err) -> {
            if (!token.equals(starting.get(key))) {
                future.completeExceptionally(new IllegalStateException("Regen cancelled"));
                return;
            }
            if (err != null || snapshot == null) {
                starting.remove(key, token);
                future.completeExceptionally(err != null ? unwrap(err) : new IllegalStateException("Snapshot load returned null"));
                return;
            }
            if (snapshot.sizeX() != arena.sizeX() || snapshot.sizeY() != arena.sizeY() || snapshot.sizeZ() != arena.sizeZ()) {
                starting.remove(key, token);
                future.completeExceptionally(new IllegalStateException("Snapshot #" + arena.latestSnapshotId()
                        + " does not match the arena size; recapture it with /ardx save " + arena.name()));
                return;
            }
            final Location origin = new Location(world, arena.originX(), arena.originY(), arena.originZ());
            scheduler.runAtLocation(origin, () -> {
                if (!token.equals(starting.get(key)) || arenaManager.getArena(arena.name()) != arena) {
                    starting.remove(key, token);
                    future.completeExceptionally(new IllegalStateException("Regen cancelled"));
                    return;
                }
                enqueue(arena, world, snapshot, mode, safeMode, allowDirty, key, token, future);
            }, () -> {
                starting.remove(key, token);
                future.completeExceptionally(new IllegalStateException("Could not schedule the restore (server shutting down?)"));
            });
        });
        return future;
    }

    private void enqueue(@NotNull final Arena arena, @NotNull final World world, @NotNull final Snapshot snapshot,
                         @NotNull final RegenMode mode, final boolean safeMode, final boolean allowDirty,
                         @NotNull final String key, @NotNull final UUID token,
                         @NotNull final CompletableFuture<RegenTask> future) {
        try {
            final Settings.PerformanceSettings perf = configManager.performance();
            teleportPlayersToSpawn(arena, world);
            final long[] dirty = plugin.changeTracker().beginRestore(arena, allowDirty && perf.dirtyTracking);
            final boolean folia = SchedulerProvider.isFolia();
            final RegenProgressBar bar = perf.progressBar ? new RegenProgressBar(arena.name(), scheduler) : null;
            if (bar != null) {
                arenaManager.forEachPlayerInArena(arena, p -> bar.show(List.of(p)));
            }
            arena.setStatus(ArenaStatus.REGENERATING);
            playArenaSound(arena, configManager.sounds().regenStart);
            final int speedPercent = RegenSpeed.resolve(arena.regenSpeedPercent(), perf.regenSpeedPercent);
            final long startedAt = System.currentTimeMillis();
            final ActiveJob[] holder = new ActiveJob[1];
            final RegenTask task = new RegenTask(plugin, scheduler, snapshot, world,
                    arena.originX(), arena.originY(), arena.originZ(),
                    mode, safeMode, arena.exclusionIndex(),
                    perf.deltaSkip, perf.skipUnchangedTiles, dirty,
                    folia ? perf.parallelChunks : 1, !folia,
                    t -> completeJob(holder[0], arena, startedAt, t),
                    progress -> {
                        if (bar != null) bar.progress(progress);
                    });
            final ActiveJob job = new ActiveJob(UUID.randomUUID(), arena.name(), task, future, bar, token, speedPercent);
            holder[0] = job;
            allJobs.put(job.id, job);
            queue.add(job);
            // cancel() may have run between the token check and here.
            if (!token.equals(starting.get(key)) || arenaManager.getArena(arena.name()) != arena) {
                queue.remove(job);
                task.cancel();
                return;
            }
            plugin.getSLF4JLogger().info("Queued restore of '{}' (mode={}, speed={}%, {}).", arena.name(), mode,
                    speedPercent, dirty == null ? "full" : dirty.length + " dirty voxels");
        } catch (final Throwable t) {
            starting.remove(key, token);
            plugin.changeTracker().endRestore(arena.name(), false);
            arena.compareAndSetStatus(ArenaStatus.REGENERATING, ArenaStatus.ERROR);
            future.completeExceptionally(t);
        }
    }

    private void completeJob(@Nullable final ActiveJob job, @NotNull final Arena arena,
                             final long startedAt, @NotNull final RegenTask task) {
        if (job == null) {
            return;
        }
        if (job.bar != null) {
            job.bar.hideRemaining();
        }
        starting.remove(key(arena.name()), job.token);
        allJobs.remove(job.id);
        queue.remove(job);
        final boolean stillExists = arenaManager.getArena(arena.name()) == arena;
        final boolean success = !task.isCancelled() && !task.isFailed();
        plugin.changeTracker().endRestore(arena.name(), success);

        if (task.isCancelled()) {
            if (stillExists) {
                arena.compareAndSetStatus(ArenaStatus.REGENERATING, ArenaStatus.READY);
            }
            job.future.completeExceptionally(new IllegalStateException("Regen cancelled"));
            return;
        }
        if (task.isFailed()) {
            if (stillExists) {
                arena.compareAndSetStatus(ArenaStatus.REGENERATING, ArenaStatus.ERROR);
                arenaManager.saveArena(arena);
            }
            playArenaSound(arena, configManager.sounds().error);
            job.future.completeExceptionally(new IllegalStateException("Regen failed (see console)"));
            return;
        }
        if (stillExists) {
            arena.markRegenerated();
            arenaManager.saveArena(arena);
            playArenaSound(arena, configManager.sounds().regenComplete);
            final long duration = System.currentTimeMillis() - startedAt;
            final int applied = task.blocksApplied();
            scheduler.runGlobal(() -> Bukkit.getPluginManager().callEvent(
                    new ArenaRegenCompleteEvent(arena, applied, duration)));
            plugin.getSLF4JLogger().info("Restored '{}' in {} ms ({} written, {} unchanged, {} excluded).",
                    arena.name(), duration, applied, task.blocksSkipped(), task.blocksExcluded());
        }
        job.future.complete(task);
    }

    private void playArenaSound(@NotNull final Arena arena, @NotNull final String soundName) {
        final Settings.SoundSettings sounds = configManager.sounds();
        if (!sounds.enabled) return;
        final Sound sound = RegistryUtil.soundFromString(soundName);
        if (sound == null) return;
        arenaManager.forEachPlayerInArena(arena, player ->
                player.playSound(player.getLocation(), sound, sounds.volume, sounds.pitch));
    }

    /** Move players inside the arena to its spawn (if set) before blocks change. Folia-safe. */
    private void teleportPlayersToSpawn(@NotNull final Arena arena, @NotNull final World world) {
        if (!arena.hasSpawn()) {
            return;
        }
        final Location spawn = new Location(world, arena.spawnX(), arena.spawnY(), arena.spawnZ(),
                arena.spawnYaw(), arena.spawnPitch());
        arenaManager.forEachPlayerInArena(arena, player ->
                player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    // -----------------------------------------------------------------------
    // Queries / cancellation
    // -----------------------------------------------------------------------

    /** @return jobs waiting behind the running one. */
    public int queueSize() {
        return queue.size();
    }

    /** @return queued + running jobs. */
    public int activeCount() {
        return allJobs.size();
    }

    /** @return true if any restore is running. */
    public boolean isRegenerating() {
        final ActiveJob job = current;
        return job != null && !job.task.isDone();
    }

    public boolean isRegenerating(@NotNull final String arenaName) {
        if (starting.containsKey(key(arenaName))) {
            return true;
        }
        for (final ActiveJob job : allJobs.values()) {
            if (job.arenaName.equalsIgnoreCase(arenaName) && !job.task.isDone()) {
                return true;
            }
        }
        return false;
    }

    /** @return progress 0..1 of the arena's running restore, or -1 when idle. */
    public float progress(@NotNull final String arenaName) {
        for (final ActiveJob job : allJobs.values()) {
            if (job.arenaName.equalsIgnoreCase(arenaName)) {
                return job.task.progress();
            }
        }
        return -1f;
    }

    /** Cancel the running or queued restore of one arena. */
    public boolean cancel(@NotNull final String arenaName) {
        boolean any = starting.remove(key(arenaName)) != null;
        for (final ActiveJob job : List.copyOf(allJobs.values())) {
            if (job.arenaName.equalsIgnoreCase(arenaName)) {
                queue.remove(job);
                job.task.cancel(); // completes the future and cleans up via completeJob
                if (current == job) {
                    // The in-flight batch (if any) still owns batchInFlight and releases it.
                    current = null;
                }
                any = true;
            }
        }
        return any;
    }

    /** Cancel every queued and running restore. */
    public void cancelAll() {
        starting.clear();
        for (final ActiveJob job : List.copyOf(allJobs.values())) {
            queue.remove(job);
            job.task.cancel();
        }
        queue.clear();
        current = null;
    }

    /** Shutdown: stop the driver and abort in-flight work. */
    public void shutdown() {
        if (driver != null) {
            driver.cancel();
            driver = null;
        }
        cancelAll();
        allJobs.clear();
        batchInFlight = false;
        plugin.getSLF4JLogger().info("Regeneration queue shut down.");
    }

    private static @NotNull String key(@NotNull final String arenaName) {
        return arenaName.toLowerCase(Locale.ROOT);
    }

    private static @NotNull Throwable unwrap(@NotNull final Throwable t) {
        return t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    /** Active job record. */
    private static final class ActiveJob {
        final UUID id;
        final String arenaName;
        final RegenTask task;
        final CompletableFuture<RegenTask> future;
        final @Nullable RegenProgressBar bar;
        final UUID token;
        final int speedPercent;

        ActiveJob(final UUID id, final String arenaName, final RegenTask task,
                  final CompletableFuture<RegenTask> future, @Nullable final RegenProgressBar bar,
                  final UUID token, final int speedPercent) {
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
