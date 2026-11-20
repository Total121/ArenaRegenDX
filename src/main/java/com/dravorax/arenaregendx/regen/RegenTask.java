/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.regen;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.ChangeTracker;
import com.dravorax.arenaregendx.arena.ExclusionIndex;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.snapshot.EntitySerializer;
import com.dravorax.arenaregendx.snapshot.PaletteIds;
import com.dravorax.arenaregendx.snapshot.Snapshot;
import com.dravorax.arenaregendx.snapshot.TileEntitySerializer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Painting;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Time-sliced restore job driven by {@link RegenManager}.
 *
 * <p>The manager calls {@link #applyBatch} at most once per tick and never
 * while a previous batch is still running, so cursor fields are only ever
 * touched by one batch at a time.</p>
 *
 * <h2>Lag controls</h2>
 * <ul>
 *   <li>Pre-resolved palette (Bukkit and NMS) — no string parsing per voxel.</li>
 *   <li>Delta skip by NMS state identity — unchanged voxels cost one lookup.</li>
 *   <li>Exclusion volumes skipped with an AABB pre-check.</li>
 *   <li>Nanosecond + block budget per tick; work stops at the first boundary past it.</li>
 *   <li>Paper: batches run inline on the main thread and may span many chunks;
 *       unloaded chunks are loaded asynchronously instead of stalling the tick.</li>
 *   <li>Folia: up to {@code parallel-chunks} chunks per tick, each on its own region thread.</li>
 *   <li>Physics off; block entities restored from snapshot NBT.</li>
 * </ul>
 *
 * @author DravoraX
 */
public final class RegenTask {

    private static final int RESUME_START = Integer.MIN_VALUE;
    /** Batches to wait for an async chunk load before falling back to a sync load. */
    private static final int MAX_LOAD_WAIT_BATCHES = 40;

    private final ArenaRegenDXPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final Snapshot snapshot;
    private final World world;
    private final int originX, originY, originZ;
    private final RegenMode mode;
    private final boolean safeMode;
    private final ExclusionIndex exclusions;
    private final boolean hasExclusions;
    private final boolean deltaSkip;
    private final boolean skipUnchangedTiles;
    private final long @Nullable [] dirty;
    private final int parallelism;
    private final boolean singleThread;
    private final @Nullable Consumer<RegenTask> onComplete;
    private final @Nullable Consumer<Float> onProgress;

    private final int sizeX, sizeY, sizeZ;
    private final int minChunkX, minChunkZ, chunksX, totalChunks;
    private final PaletteIds ids;
    private final BlockData[] palette;
    private final boolean[] airFlags;
    private final Object @Nullable [] nmsPalette;
    private final boolean hasTiles;
    private final int totalBlocks;
    private final int midX, midY, midZ, maxRadius;

    // ---- Cursors (single writer: the batch in flight) ----
    private volatile int chunkCursor;
    private volatile int ySlice;
    private volatile int layerY;
    private volatile int dirtyCursor;
    private volatile int waveRadius;
    private volatile int waveChunk;
    private volatile int waveY = RESUME_START;
    private volatile long pendingChunk = Long.MIN_VALUE;
    private volatile int loadWaits;

    // ---- Counters (added once per batch, not per voxel) ----
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong excluded = new AtomicLong();

    private volatile boolean done;
    private volatile boolean failed;
    private volatile boolean cancelled;
    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private final AtomicBoolean completionFired = new AtomicBoolean(false);

    public RegenTask(@NotNull final ArenaRegenDXPlugin plugin,
                     @NotNull final SchedulerAdapter scheduler,
                     @NotNull final Snapshot snapshot,
                     @NotNull final World world,
                     final int originX, final int originY, final int originZ,
                     @NotNull final RegenMode mode,
                     final boolean safeMode,
                     @NotNull final ExclusionIndex exclusions,
                     final boolean deltaSkip,
                     final boolean skipUnchangedTiles,
                     final long @Nullable [] dirtyBlocks,
                     final int parallelism,
                     final boolean singleThread,
                     @Nullable final Consumer<RegenTask> onComplete,
                     @Nullable final Consumer<Float> onProgress) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.snapshot = snapshot;
        this.world = world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.mode = mode;
        this.safeMode = safeMode;
        this.exclusions = exclusions;
        this.hasExclusions = !exclusions.isEmpty();
        this.deltaSkip = deltaSkip;
        this.skipUnchangedTiles = skipUnchangedTiles;
        this.parallelism = Math.max(1, parallelism);
        this.singleThread = singleThread;
        this.onComplete = onComplete;
        this.onProgress = onProgress;

        this.sizeX = snapshot.sizeX();
        this.sizeY = snapshot.sizeY();
        this.sizeZ = snapshot.sizeZ();
        this.ids = snapshot.ids();
        this.palette = snapshot.palette();
        this.airFlags = snapshot.airFlags();
        this.nmsPalette = FastBlockWriter.resolveNms(palette);
        this.hasTiles = snapshot.hasTiles();
        this.totalBlocks = snapshot.blockCount();

        this.minChunkX = originX >> 4;
        this.minChunkZ = originZ >> 4;
        this.chunksX = ((originX + sizeX - 1) >> 4) - minChunkX + 1;
        final int chunksZ = ((originZ + sizeZ - 1) >> 4) - minChunkZ + 1;
        this.totalChunks = chunksX * chunksZ;

        this.midX = originX + ((sizeX - 1) >> 1);
        this.midY = originY + ((sizeY - 1) >> 1);
        this.midZ = originZ + ((sizeZ - 1) >> 1);
        this.maxRadius = Math.max(Math.max(midX - originX, originX + sizeX - 1 - midX),
                Math.max(Math.max(midY - originY, originY + sizeY - 1 - midY),
                        Math.max(midZ - originZ, originZ + sizeZ - 1 - midZ)));

        this.dirty = dirtyBlocks == null || dirtyBlocks.length == 0 ? null : sortByChunk(dirtyBlocks);
    }

    /** Order dirty voxels chunk by chunk (then by packed value) without boxing. */
    private static long @NotNull [] sortByChunk(final long @NotNull [] voxels) {
        final long[] sorted = voxels.clone();
        LongArrays.quickSort(sorted, (final long a, final long b) -> {
            int c = Integer.compare(ChangeTracker.unpackX(a) >> 4, ChangeTracker.unpackX(b) >> 4);
            if (c != 0) return c;
            c = Integer.compare(ChangeTracker.unpackZ(a) >> 4, ChangeTracker.unpackZ(b) >> 4);
            return c != 0 ? c : Long.compare(a, b);
        });
        return sorted;
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    public boolean isDone() { return done; }
    public boolean isFailed() { return failed; }
    public boolean isCancelled() { return cancelled; }
    public boolean dirtyMode() { return dirty != null; }
    public @NotNull RegenMode mode() { return mode; }
    public int totalBlocks() { return totalBlocks; }
    public int blocksApplied() { return (int) Math.min(Integer.MAX_VALUE, written.get()); }
    public int blocksSkipped() { return (int) Math.min(Integer.MAX_VALUE, skipped.get()); }
    public int blocksExcluded() { return (int) Math.min(Integer.MAX_VALUE, excluded.get()); }

    public float progress() {
        final long[] d = dirty;
        if (d != null) {
            return Math.min(1f, (float) dirtyCursor / (float) d.length);
        }
        return totalBlocks == 0 ? 1f : Math.min(1f, (float) processed.get() / (float) totalBlocks);
    }

    public void markFailed() {
        this.failed = true;
        this.done = true;
        fireComplete();
    }

    public void cancel() {
        this.cancelled = true;
        this.done = true;
        fireComplete();
    }

    private void fireComplete() {
        if (!completionFired.compareAndSet(false, true) || onComplete == null) {
            return;
        }
        try {
            onComplete.accept(this);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Regen completion handler failed", t);
        }
    }

    // -----------------------------------------------------------------------
    // Batch dispatch
    // -----------------------------------------------------------------------

    /**
     * Apply work until {@code blockBudget} writes or {@code nanoBudget} elapse.
     * {@code onBatchDone} always runs exactly once; the manager must not call
     * this again before it does.
     */
    public void applyBatch(final int blockBudget, final long nanoBudget, @NotNull final Runnable onBatchDone) {
        if (done || cancelled || failed) {
            onBatchDone.run();
            return;
        }
        try {
            if (dirty != null) {
                applyDirtyBatch(blockBudget, nanoBudget, onBatchDone);
            } else {
                switch (mode) {
                    case LAYER_BY_LAYER -> applyLayerBatch(blockBudget, nanoBudget, onBatchDone);
                    case ANIMATED -> applyWaveBatch(blockBudget, nanoBudget, onBatchDone);
                    default -> applyChunkBatch(blockBudget, nanoBudget, mode == RegenMode.CHUNK_BY_CHUNK, onBatchDone);
                }
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Regen batch dispatch failed", t);
            failed = true;
            endOfBatch(onBatchDone).run();
        }
    }

    private @NotNull Runnable endOfBatch(@NotNull final Runnable onBatchDone) {
        return () -> {
            try {
                finishIfDone();
            } finally {
                onBatchDone.run();
            }
        };
    }

    /**
     * Run {@code work} on the thread owning the chunk: inline when already there
     * (Paper main thread), otherwise via the region scheduler. {@code after}
     * always runs exactly once, even if scheduling fails.
     */
    private void runOnRegion(final int chunkX, final int chunkZ,
                             @NotNull final Runnable work, @NotNull final Runnable after) {
        final Runnable body = () -> {
            try {
                if (!cancelled && !failed) {
                    work.run();
                }
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().error("Restore failed in chunk {},{}", chunkX, chunkZ, t);
                failed = true;
            } finally {
                after.run();
            }
        };
        if (scheduler.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            body.run();
            return;
        }
        scheduler.runAtLocation(new Location(world, (chunkX << 4) + 8, originY, (chunkZ << 4) + 8), body, () -> {
            plugin.getSLF4JLogger().error("Could not schedule restore of chunk {},{} - aborting", chunkX, chunkZ);
            failed = true;
            after.run();
        });
    }

    /** Folia: run one unit per chunk in parallel; {@code advance} runs once all finished. */
    private void dispatchParallel(final int @NotNull [] locals, @NotNull final Unit unit,
                                  @NotNull final Runnable advance, @NotNull final Runnable onBatchDone) {
        if (locals.length == 0) {
            advance.run();
            endOfBatch(onBatchDone).run();
            return;
        }
        final AtomicInteger remaining = new AtomicInteger(locals.length);
        final Runnable siblingDone = () -> {
            if (remaining.decrementAndGet() == 0) {
                if (!failed && !cancelled) {
                    advance.run();
                }
                endOfBatch(onBatchDone).run();
            }
        };
        for (final int local : locals) {
            final int cx = chunkXOf(local);
            final int cz = chunkZOf(local);
            runOnRegion(cx, cz, () -> {
                final Stats st = new Stats();
                try {
                    unit.run(cx, cz, writer(), st);
                } finally {
                    st.finish();
                }
            }, siblingDone);
        }
    }

    @FunctionalInterface
    private interface Unit {
        void run(int chunkX, int chunkZ, @NotNull FastBlockWriter.Writer writer, @NotNull Stats stats);
    }

    private int parallelWidth(final int blockBudget, final long nanoBudget, final int available) {
        int n = Math.max(1, Math.min(parallelism, available));
        if (blockBudget < 256 || nanoBudget < 500_000L) {
            n = 1;
        } else if (blockBudget < 1024 || nanoBudget < 2_000_000L) {
            n = Math.min(n, 2);
        }
        return n;
    }

    private @NotNull FastBlockWriter.Writer writer() {
        return FastBlockWriter.writer(world, palette, airFlags, nmsPalette);
    }

    private int chunkXOf(final int local) {
        return minChunkX + (local % chunksX);
    }

    private int chunkZOf(final int local) {
        return minChunkZ + (local / chunksX);
    }

    /**
     * Paper only: avoid synchronous chunk loads on the main thread. Requests an
     * async load and returns false until the chunk is ready (bounded wait).
     */
    private boolean chunkReady(final int chunkX, final int chunkZ) {
        if (!singleThread || world.isChunkLoaded(chunkX, chunkZ)) {
            loadWaits = 0;
            return true;
        }
        final long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        if (pendingChunk != key) {
            pendingChunk = key;
            loadWaits = 0;
            world.getChunkAtAsync(chunkX, chunkZ);
        }
        if (++loadWaits > MAX_LOAD_WAIT_BATCHES) {
            loadWaits = 0;
            return true; // give up waiting; the next access loads synchronously
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // INSTANT / CHUNK_BY_CHUNK
    // -----------------------------------------------------------------------

    private void applyChunkBatch(final int blockBudget, final long nanoBudget, final boolean serialChunks,
                                 @NotNull final Runnable onBatchDone) {
        if (chunkCursor >= totalChunks) {
            endOfBatch(onBatchDone).run();
            return;
        }
        final int sliceHeight = serialChunks ? 8 : 16;
        if (singleThread) {
            runOnRegion(chunkXOf(chunkCursor), chunkZOf(chunkCursor), () -> {
                final long deadline = System.nanoTime() + nanoBudget;
                final Stats st = new Stats();
                final FastBlockWriter.Writer w = writer();
                try {
                    while (chunkCursor < totalChunks && !cancelled && !failed) {
                        final int local = chunkCursor;
                        final int cx = chunkXOf(local);
                        final int cz = chunkZOf(local);
                        if (!chunkReady(cx, cz)) {
                            break;
                        }
                        final int slice = ySlice;
                        final int nextY = restoreColumn(w, st, cx, cz, slice,
                                Math.min(sizeY - 1, slice + sliceHeight - 1), deadline, blockBudget, false);
                        if (nextY >= sizeY) {
                            ySlice = 0;
                            chunkCursor = local + 1;
                        } else {
                            ySlice = nextY;
                        }
                        if (System.nanoTime() >= deadline || st.written >= blockBudget) {
                            break;
                        }
                    }
                } finally {
                    st.finish();
                }
            }, endOfBatch(onBatchDone));
            return;
        }

        // Folia.
        final int start = chunkCursor;
        final int slice = ySlice;
        final int relY1 = Math.min(sizeY - 1, slice + sliceHeight - 1);
        final int n = serialChunks ? 1 : parallelWidth(blockBudget, nanoBudget, totalChunks - start);
        if (n == 1) {
            final int cx = chunkXOf(start);
            final int cz = chunkZOf(start);
            runOnRegion(cx, cz, () -> {
                final Stats st = new Stats();
                try {
                    final int nextY = restoreColumn(writer(), st, cx, cz, slice, relY1,
                            System.nanoTime() + nanoBudget, blockBudget, false);
                    if (nextY >= sizeY) {
                        ySlice = 0;
                        chunkCursor = start + 1;
                    } else {
                        ySlice = nextY;
                    }
                } finally {
                    st.finish();
                }
            }, endOfBatch(onBatchDone));
            return;
        }
        // Parallel: every sibling finishes the same Y slice so the shared cursor stays exact.
        final int[] locals = new int[n];
        for (int i = 0; i < n; i++) {
            locals[i] = start + i;
        }
        dispatchParallel(locals,
                (cx, cz, w, st) -> restoreColumn(w, st, cx, cz, slice, relY1, Long.MAX_VALUE, Integer.MAX_VALUE, true),
                () -> {
                    if (relY1 + 1 >= sizeY) {
                        ySlice = 0;
                        chunkCursor = start + n;
                    } else {
                        ySlice = relY1 + 1;
                    }
                }, onBatchDone);
    }

    /**
     * Restore relative Y {@code relY0..relY1} of one chunk column. Always finishes
     * whole Y layers; stops early (unless {@code mustFinish}) once the deadline or
     * block budget is reached.
     *
     * @return the next relative Y still to do ({@code sizeY} when the column is done).
     */
    private int restoreColumn(@NotNull final FastBlockWriter.Writer w, @NotNull final Stats st,
                              final int chunkX, final int chunkZ, final int relY0, final int relY1,
                              final long deadline, final int blockBudget, final boolean mustFinish) {
        final int x0 = Math.max(originX, chunkX << 4);
        final int x1 = Math.min(originX + sizeX - 1, (chunkX << 4) + 15);
        final int z0 = Math.max(originZ, chunkZ << 4);
        final int z1 = Math.min(originZ + sizeZ - 1, (chunkZ << 4) + 15);
        final int y0 = originY + Math.max(0, relY0);
        final int y1 = originY + Math.min(sizeY - 1, relY1);
        if (x0 > x1 || z0 > z1 || y0 > y1) {
            return Math.min(sizeY, relY1 + 1);
        }
        for (int y = y0; y <= y1; y++) {
            if (cancelled || failed) {
                return y - originY;
            }
            final int ry = y - originY;
            for (int z = z0; z <= z1; z++) {
                final int rowBase = sizeX * ((z - originZ) + sizeZ * ry);
                for (int x = x0; x <= x1; x++) {
                    restoreVoxel(w, st, x, y, z, (x - originX) + rowBase);
                }
            }
            st.include(x0, y0, z0, x1, y, z1);
            if (!mustFinish && y < y1 && (System.nanoTime() >= deadline || st.written >= blockBudget)) {
                return ry + 1;
            }
        }
        return relY1 + 1;
    }

    // -----------------------------------------------------------------------
    // LAYER_BY_LAYER
    // -----------------------------------------------------------------------

    private void applyLayerBatch(final int blockBudget, final long nanoBudget, @NotNull final Runnable onBatchDone) {
        if (layerY >= sizeY) {
            endOfBatch(onBatchDone).run();
            return;
        }
        if (singleThread) {
            runOnRegion(chunkXOf(chunkCursor), chunkZOf(chunkCursor), () -> {
                final long deadline = System.nanoTime() + nanoBudget;
                final Stats st = new Stats();
                final FastBlockWriter.Writer w = writer();
                try {
                    while (layerY < sizeY && !cancelled && !failed) {
                        final int local = chunkCursor;
                        final int cx = chunkXOf(local);
                        final int cz = chunkZOf(local);
                        if (!chunkReady(cx, cz)) {
                            break;
                        }
                        final int y = layerY;
                        restoreColumn(w, st, cx, cz, y, y, Long.MAX_VALUE, Integer.MAX_VALUE, true);
                        advanceLayer(local + 1);
                        if (System.nanoTime() >= deadline || st.written >= blockBudget) {
                            break;
                        }
                    }
                } finally {
                    st.finish();
                }
            }, endOfBatch(onBatchDone));
            return;
        }
        // Folia: several chunks of the current layer at once.
        final int y = layerY;
        final int start = chunkCursor;
        final int n = parallelWidth(blockBudget, nanoBudget, totalChunks - start);
        final int[] locals = new int[n];
        for (int i = 0; i < n; i++) {
            locals[i] = start + i;
        }
        dispatchParallel(locals,
                (cx, cz, w, st) -> restoreColumn(w, st, cx, cz, y, y, Long.MAX_VALUE, Integer.MAX_VALUE, true),
                () -> advanceLayer(start + n), onBatchDone);
    }

    private void advanceLayer(final int nextChunk) {
        if (nextChunk >= totalChunks) {
            chunkCursor = 0;
            layerY = layerY + 1;
        } else {
            chunkCursor = nextChunk;
        }
    }

    // -----------------------------------------------------------------------
    // ANIMATED (expanding Chebyshev shells from the centre)
    // -----------------------------------------------------------------------

    private void applyWaveBatch(final int blockBudget, final long nanoBudget, @NotNull final Runnable onBatchDone) {
        advanceWave();
        if (waveRadius > maxRadius) {
            endOfBatch(onBatchDone).run();
            return;
        }
        if (singleThread) {
            runOnRegion(chunkXOf(waveChunk), chunkZOf(waveChunk), () -> {
                final long deadline = System.nanoTime() + nanoBudget;
                final Stats st = new Stats();
                final FastBlockWriter.Writer w = writer();
                final int radius = waveRadius;
                try {
                    // One shell per tick keeps the growth animation visible.
                    while (waveRadius == radius && !cancelled && !failed) {
                        final int local = waveChunk;
                        final int cx = chunkXOf(local);
                        final int cz = chunkZOf(local);
                        if (!chunkReady(cx, cz)) {
                            break;
                        }
                        final int next = restoreShell(w, st, cx, cz, radius, waveY, deadline, blockBudget, false);
                        if (next != RESUME_START) {
                            waveY = next;
                            break;
                        }
                        waveChunk = local + 1;
                        waveY = RESUME_START;
                        advanceWave();
                        if (System.nanoTime() >= deadline || st.written >= blockBudget) {
                            break;
                        }
                    }
                } finally {
                    st.finish();
                }
            }, endOfBatch(onBatchDone));
            return;
        }
        // Folia: the next few intersecting chunks of the current shell, in parallel.
        final int radius = waveRadius;
        final int n = parallelWidth(blockBudget, nanoBudget, totalChunks - waveChunk);
        final int[] locals = new int[n];
        int count = 0;
        int local = waveChunk;
        while (count < n && local < totalChunks) {
            if (shellIntersects(local, radius)) {
                locals[count++] = local;
            }
            local++;
        }
        final int resumeAt = local;
        dispatchParallel(Arrays.copyOf(locals, count),
                (cx, cz, w, st) -> restoreShell(w, st, cx, cz, radius, RESUME_START, Long.MAX_VALUE, Integer.MAX_VALUE, true),
                () -> {
                    waveChunk = resumeAt;
                    waveY = RESUME_START;
                    advanceWave();
                }, onBatchDone);
    }

    /** Move the wave cursor to the next chunk that intersects a shell (cheap math, no I/O). */
    private void advanceWave() {
        while (waveRadius <= maxRadius) {
            if (waveChunk >= totalChunks) {
                waveRadius = waveRadius + 1;
                waveChunk = 0;
                waveY = RESUME_START;
                continue;
            }
            if (waveY != RESUME_START || shellIntersects(waveChunk, waveRadius)) {
                return;
            }
            waveChunk = waveChunk + 1;
        }
    }

    /** @return true if shell {@code r} has at least one voxel inside chunk {@code local} ∩ arena. */
    private boolean shellIntersects(final int local, final int r) {
        final int cx = chunkXOf(local);
        final int cz = chunkZOf(local);
        final int x0 = Math.max(originX, cx << 4);
        final int x1 = Math.min(originX + sizeX - 1, (cx << 4) + 15);
        final int z0 = Math.max(originZ, cz << 4);
        final int z1 = Math.min(originZ + sizeZ - 1, (cz << 4) + 15);
        final int minD = Math.max(distanceToRange(midX, x0, x1), distanceToRange(midZ, z0, z1));
        final int maxD = Math.max(Math.max(Math.abs(x0 - midX), Math.abs(x1 - midX)),
                Math.max(Math.max(Math.abs(z0 - midZ), Math.abs(z1 - midZ)),
                        Math.max(midY - originY, originY + sizeY - 1 - midY)));
        return r >= minD && r <= maxD;
    }

    private static int distanceToRange(final int m, final int lo, final int hi) {
        return m < lo ? lo - m : (m > hi ? m - hi : 0);
    }

    /**
     * Restore the part of shell {@code r} that lies in one chunk, visiting each
     * shell voxel exactly once (faces of the cube, not the whole volume).
     *
     * @return {@link #RESUME_START} when this chunk's part is done, else the absolute Y to resume at.
     */
    private int restoreShell(@NotNull final FastBlockWriter.Writer w, @NotNull final Stats st,
                             final int chunkX, final int chunkZ, final int r, final int resumeY,
                             final long deadline, final int blockBudget, final boolean mustFinish) {
        final int sx0 = Math.max(Math.max(originX, chunkX << 4), midX - r);
        final int sx1 = Math.min(Math.min(originX + sizeX - 1, (chunkX << 4) + 15), midX + r);
        final int sz0 = Math.max(Math.max(originZ, chunkZ << 4), midZ - r);
        final int sz1 = Math.min(Math.min(originZ + sizeZ - 1, (chunkZ << 4) + 15), midZ + r);
        final int yLo = Math.max(originY, midY - r);
        final int yHi = Math.min(originY + sizeY - 1, midY + r);
        if (sx0 > sx1 || sz0 > sz1 || yLo > yHi) {
            return RESUME_START;
        }
        final int startY = resumeY == RESUME_START ? yLo : Math.max(yLo, resumeY);
        final int left = midX - r;
        final int right = midX + r;
        final boolean leftIn = left >= sx0 && left <= sx1;
        final boolean rightIn = right != left && right >= sx0 && right <= sx1;
        for (int y = startY; y <= yHi; y++) {
            if (cancelled || failed) {
                return RESUME_START;
            }
            final int ry = y - originY;
            final boolean fullLayer = Math.abs(y - midY) == r;
            for (int z = sz0; z <= sz1; z++) {
                final int rowBase = sizeX * ((z - originZ) + sizeZ * ry);
                if (fullLayer || Math.abs(z - midZ) == r) {
                    for (int x = sx0; x <= sx1; x++) {
                        restoreVoxel(w, st, x, y, z, (x - originX) + rowBase);
                    }
                } else {
                    if (leftIn) {
                        restoreVoxel(w, st, left, y, z, (left - originX) + rowBase);
                    }
                    if (rightIn) {
                        restoreVoxel(w, st, right, y, z, (right - originX) + rowBase);
                    }
                }
            }
            st.include(sx0, startY, sz0, sx1, y, sz1);
            if (!mustFinish && y < yHi && (System.nanoTime() >= deadline || st.written >= blockBudget)) {
                return y + 1;
            }
        }
        return RESUME_START;
    }

    // -----------------------------------------------------------------------
    // Dirty voxels
    // -----------------------------------------------------------------------

    private void applyDirtyBatch(final int blockBudget, final long nanoBudget, @NotNull final Runnable onBatchDone) {
        final long[] d = dirty;
        final int start = dirtyCursor;
        if (d == null || start >= d.length) {
            endOfBatch(onBatchDone).run();
            return;
        }
        runOnRegion(ChangeTracker.unpackX(d[start]) >> 4, ChangeTracker.unpackZ(d[start]) >> 4, () -> {
            final long deadline = System.nanoTime() + nanoBudget;
            final Stats st = new Stats();
            final FastBlockWriter.Writer w = writer();
            int i = start;
            int curCx = Integer.MIN_VALUE;
            int curCz = Integer.MIN_VALUE;
            try {
                while (i < d.length && !cancelled && !failed) {
                    final long packed = d[i];
                    final int x = ChangeTracker.unpackX(packed);
                    final int y = ChangeTracker.unpackY(packed);
                    final int z = ChangeTracker.unpackZ(packed);
                    if ((x >> 4) != curCx || (z >> 4) != curCz) {
                        // Folia: one chunk per batch (region ownership). Paper: keep going within budget.
                        if (i != start && (!singleThread || System.nanoTime() >= deadline
                                || st.written >= blockBudget)) {
                            break;
                        }
                        if (!chunkReady(x >> 4, z >> 4)) {
                            break;
                        }
                        curCx = x >> 4;
                        curCz = z >> 4;
                    }
                    final int flat = snapshot.flatIndex(x, y, z, originX, originY, originZ);
                    if (flat >= 0) {
                        restoreVoxel(w, st, x, y, z, flat);
                        st.include(x, y, z, x, y, z);
                    }
                    i++;
                    if ((i & 63) == 0 && (System.nanoTime() >= deadline || st.written >= blockBudget)) {
                        break;
                    }
                }
            } finally {
                dirtyCursor = i;
                st.finish();
            }
        }, endOfBatch(onBatchDone));
    }

    // -----------------------------------------------------------------------
    // Voxel + tile
    // -----------------------------------------------------------------------

    private void restoreVoxel(@NotNull final FastBlockWriter.Writer w, @NotNull final Stats st,
                              final int x, final int y, final int z, final int flat) {
        st.processed++;
        if (hasExclusions && exclusions.contains(x, y, z)) {
            st.excluded++;
            return;
        }
        final int palId = ids.get(flat);
        final byte[] tile = hasTiles ? snapshot.tile(flat) : null;
        final int result = w.apply(x, y, z, palId, deltaSkip && (tile == null || skipUnchangedTiles));
        if (result == FastBlockWriter.WRITTEN) {
            st.written++;
            if (tile != null) {
                restoreTile(x, y, z, tile);
            }
        } else if (result == FastBlockWriter.SKIPPED) {
            st.skipped++;
        }
    }

    private void restoreTile(final int x, final int y, final int z, final byte @NotNull [] tile) {
        try {
            final BlockState state = world.getBlockAt(x, y, z).getState();
            if (TileEntitySerializer.restore(state, tile)) {
                state.update(true, false);
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Tile restore failed at {},{},{}", x, y, z, t);
        }
    }

    /**
     * Safe mode: after blocks are placed, move living entities whose feet or head
     * ended up inside a solid block to the first two-block gap above them.
     */
    private void liftStuckEntities(@NotNull final BoundingBox box) {
        try {
            final Collection<Entity> nearby = world.getNearbyEntities(box,
                    e -> e instanceof LivingEntity && !(e instanceof ArmorStand));
            for (final Entity e : nearby) {
                final Location loc = e.getLocation();
                final Block feet = loc.getBlock();
                if (feet.isPassable() && feet.getRelative(BlockFace.UP).isPassable()) {
                    continue;
                }
                final int top = world.getMaxHeight() - 2;
                int y = loc.getBlockY() + 1;
                while (y <= top && !(world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ()).isPassable()
                        && world.getBlockAt(loc.getBlockX(), y + 1, loc.getBlockZ()).isPassable())) {
                    y++;
                }
                final Location target = loc.clone();
                target.setY(y);
                e.teleportAsync(target);
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("Safe-mode entity lift failed", t);
        }
    }

    /** Per-batch (or per-unit) counters, flushed into the shared atomics once. */
    private final class Stats {
        long processed;
        long written;
        long skipped;
        long excluded;
        private int minX = Integer.MAX_VALUE, minY, minZ, maxX, maxY, maxZ;

        void include(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
            if (minX == Integer.MAX_VALUE) {
                minX = x0; minY = y0; minZ = z0; maxX = x1; maxY = y1; maxZ = z1;
                return;
            }
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            minZ = Math.min(minZ, z0);
            maxX = Math.max(maxX, x1);
            maxY = Math.max(maxY, y1);
            maxZ = Math.max(maxZ, z1);
        }

        void finish() {
            if (processed != 0) RegenTask.this.processed.addAndGet(processed);
            if (written != 0) RegenTask.this.written.addAndGet(written);
            if (skipped != 0) RegenTask.this.skipped.addAndGet(skipped);
            if (excluded != 0) RegenTask.this.excluded.addAndGet(excluded);
            if (safeMode && written > 0 && minX != Integer.MAX_VALUE) {
                liftStuckEntities(new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Completion
    // -----------------------------------------------------------------------

    private boolean isComplete() {
        final long[] d = dirty;
        if (d != null) {
            return dirtyCursor >= d.length;
        }
        return switch (mode) {
            case LAYER_BY_LAYER -> layerY >= sizeY;
            case ANIMATED -> waveRadius > maxRadius;
            default -> chunkCursor >= totalChunks;
        };
    }

    private void finishIfDone() {
        if (failed || cancelled) {
            done = true;
            fireComplete();
            return;
        }
        reportProgress(progress());
        if (done || !isComplete() || !finishing.compareAndSet(false, true)) {
            return;
        }
        restoreEntitiesThenComplete();
    }

    private void reportProgress(final float value) {
        if (onProgress == null) return;
        try {
            onProgress.accept(value);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("Regen progress callback failed", t);
        }
    }

    /**
     * Replace decorative entities (armor stands, frames, paintings, displays).
     * Paper: one pass over the arena box. Folia: one task per chunk on its own
     * region, so multi-region arenas never touch entities across threads.
     */
    private void restoreEntitiesThenComplete() {
        final Long2ObjectOpenHashMap<List<Snapshot.EntityPayload>> byChunk = new Long2ObjectOpenHashMap<>();
        for (final Snapshot.EntityPayload ep : snapshot.entities()) {
            final int bx = (int) Math.floor(ep.x());
            final int by = (int) Math.floor(ep.y());
            final int bz = (int) Math.floor(ep.z());
            if (exclusions.contains(bx, by, bz)) {
                continue;
            }
            byChunk.computeIfAbsent(chunkKey(bx >> 4, bz >> 4), k -> new ArrayList<>()).add(ep);
        }
        final Runnable complete = () -> {
            done = true;
            reportProgress(1f);
            fireComplete();
        };
        if (singleThread) {
            runOnRegion(minChunkX, minChunkZ, () -> {
                final BoundingBox box = new BoundingBox(originX, originY, originZ,
                        originX + sizeX, originY + sizeY, originZ + sizeZ);
                clearDecorative(world.getNearbyEntities(box));
                for (final List<Snapshot.EntityPayload> list : byChunk.values()) {
                    spawnAll(list);
                }
            }, complete);
            return;
        }
        final AtomicInteger remaining = new AtomicInteger(totalChunks);
        final Runnable chunkDone = () -> {
            if (remaining.decrementAndGet() == 0) {
                complete.run();
            }
        };
        for (int local = 0; local < totalChunks; local++) {
            final int cx = chunkXOf(local);
            final int cz = chunkZOf(local);
            final List<Snapshot.EntityPayload> spawns = byChunk.get(chunkKey(cx, cz));
            runOnRegion(cx, cz, () -> {
                clearDecorative(Arrays.asList(world.getChunkAt(cx, cz).getEntities()));
                if (spawns != null) {
                    spawnAll(spawns);
                }
            }, chunkDone);
        }
    }

    private void clearDecorative(@NotNull final Collection<Entity> entities) {
        for (final Entity e : entities) {
            try {
                if (!(e instanceof ArmorStand || e instanceof ItemFrame || e instanceof Painting || e instanceof Display)) {
                    continue;
                }
                final Location loc = e.getLocation();
                final int x = loc.getBlockX();
                final int y = loc.getBlockY();
                final int z = loc.getBlockZ();
                if (x < originX || x >= originX + sizeX || y < originY || y >= originY + sizeY
                        || z < originZ || z >= originZ + sizeZ || exclusions.contains(x, y, z)) {
                    continue;
                }
                e.remove();
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("Decorative entity cleanup failed for {}", e, t);
            }
        }
    }

    private void spawnAll(@NotNull final List<Snapshot.EntityPayload> payloads) {
        for (final Snapshot.EntityPayload ep : payloads) {
            try {
                EntitySerializer.restore(ep, world);
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().warn("Entity restore failed for {}", ep.type(), t);
            }
        }
    }

    private static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
