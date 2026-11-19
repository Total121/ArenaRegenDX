/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.regen;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.ChangeTracker;
import com.dravorax.arenaregendx.arena.ExclusionIndex;
import com.dravorax.arenaregendx.nms.NmsAccess;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.snapshot.EntitySerializer;
import com.dravorax.arenaregendx.snapshot.Snapshot;
import com.dravorax.arenaregendx.snapshot.TileEntitySerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Painting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Time-sliced restore job. Work is dispatched one region at a time so Paper
 * keeps a hot chunk in cache and Folia stays on the correct region thread.
 *
 * <p>Lag controls:</p>
 * <ul>
 *   <li>Pre-parsed {@link BlockData} palette and pre-resolved NMS states — no
 *       string parse or map lookup per voxel.</li>
 *   <li>Delta skip — identical blocks (and air-to-air) are not written.</li>
 *   <li>Exclusion volumes are skipped entirely (protected spawn, etc.).</li>
 *   <li>Millisecond + write budget per batch, checked per Y-layer / row.</li>
 *   <li>A batch keeps going into the next chunk only while that chunk is owned
 *       by the current thread's region, so one dispatch can cover many chunks
 *       on Paper (and on a single Folia region) without ever crossing a region
 *       boundary.</li>
 *   <li>Physics off. Tile entities restored only where the snapshot has them.</li>
 * </ul>
 *
 * <p>Threading: batches never overlap (the manager waits for {@code onBatchDone}),
 * so the cursor fields have a single writer at any time; they are volatile for
 * cross-thread visibility on Folia.</p>
 *
 * @author DravoraX
 */
public final class RegenTask {

    private final ArenaRegenDXPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final Snapshot snapshot;
    private final World world;
    private final int originX, originY, originZ;
    private final RegenMode mode;
    private final boolean safeMode;
    private final ExclusionIndex exclusions;
    private final boolean deltaSkip;
    private final long @Nullable [] dirty;
    private final int parallelism;
    private final @Nullable Consumer<RegenTask> onComplete;
    private final @Nullable Consumer<Float> onProgress;

    private final int sizeX, sizeY, sizeZ;
    private final int minChunkX, minChunkZ, chunksX, chunksZ, totalChunks;
    private final int[] ids;
    private final BlockData[] palette;
    private final boolean[] airFlags;
    private final Map<Integer, byte[]> tiles;
    private final @Nullable BitSet tileMask;
    /** Lazily resolved NMS block states per palette id (benign races: same value). */
    private final Object[] nmsPalette;
    private final @Nullable Object nmsLevel;
    /** Wave centre and the largest Chebyshev radius that still touches the cuboid. */
    private final int midX, midY, midZ, maxWaveRadius;

    /** Chunk cursor (row-major over the arena's chunk rectangle). */
    private volatile int chunkCursor = 0;
    /** Relative Y start for the current chunk (INSTANT / CHUNK_BY_CHUNK). */
    private volatile int ySlice = 0;
    /** Current relative Y layer (LAYER mode). */
    private volatile int layerY = 0;
    /** Chebyshev radius for ANIMATED (no O(n) sort). */
    private volatile int waveRadius = 0;
    /** Resume point inside the current wave chunk (absolute y / z row), or MIN_VALUE. */
    private volatile int waveResumeY = Integer.MIN_VALUE;
    private volatile int waveResumeZ = Integer.MIN_VALUE;
    private volatile int dirtyCursor;

    /** Ensures {@link #onComplete} runs at most once (cancel / fail / success). */
    private final AtomicBoolean completionFired = new AtomicBoolean(false);
    private final AtomicInteger cursorCount = new AtomicInteger();
    private final AtomicInteger writtenCount = new AtomicInteger();
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final AtomicInteger excludedCount = new AtomicInteger();

    private final int totalBlocks;
    private volatile boolean done;
    private volatile boolean failed;
    private volatile boolean finishing;
    private volatile boolean cancelled;

    public RegenTask(@NotNull final ArenaRegenDXPlugin plugin,
                     @NotNull final SchedulerAdapter scheduler,
                     @NotNull final Snapshot snapshot,
                     @NotNull final World world,
                     final int originX, final int originY, final int originZ,
                     @NotNull final RegenMode mode,
                     final boolean safeMode,
                     @NotNull final ExclusionIndex exclusions,
                     final boolean deltaSkip,
                     final long @Nullable [] dirtyBlocks,
                     final int parallelism,
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
        this.deltaSkip = deltaSkip;
        if (dirtyBlocks == null || dirtyBlocks.length == 0) {
            this.dirty = null;
        } else {
            final long[] sorted = dirtyBlocks.clone();
            ChangeTracker.sortByChunk(sorted);
            this.dirty = sorted;
        }
        this.parallelism = Math.max(1, parallelism);
        this.onComplete = onComplete;
        this.onProgress = onProgress;
        snapshot.resolvePalette();
        this.sizeX = snapshot.sizeX();
        this.sizeY = snapshot.sizeY();
        this.sizeZ = snapshot.sizeZ();
        this.ids = snapshot.blockDataIds();
        this.palette = snapshot.palette();
        this.airFlags = snapshot.airFlags();
        this.tiles = snapshot.tileEntities();
        this.tileMask = snapshot.tileMask();
        this.nmsPalette = new Object[palette.length];
        this.nmsLevel = FastBlockWriter.nmsEnabled() ? NmsAccess.levelHandle(world) : null;
        this.totalBlocks = snapshot.blockCount();
        this.minChunkX = originX >> 4;
        this.minChunkZ = originZ >> 4;
        final int maxChunkX = (originX + sizeX - 1) >> 4;
        final int maxChunkZ = (originZ + sizeZ - 1) >> 4;
        this.chunksX = maxChunkX - minChunkX + 1;
        this.chunksZ = maxChunkZ - minChunkZ + 1;
        this.totalChunks = chunksX * chunksZ;
        this.midX = originX + (sizeX >> 1);
        this.midY = originY + (sizeY >> 1);
        this.midZ = originZ + (sizeZ >> 1);
        // Largest |coord - mid| inside the cuboid on each axis. The previous
        // max(size) bound ran roughly twice as many (empty) wave passes.
        final int rx = Math.max(midX - originX, originX + sizeX - 1 - midX);
        final int ry = Math.max(midY - originY, originY + sizeY - 1 - midY);
        final int rz = Math.max(midZ - originZ, originZ + sizeZ - 1 - midZ);
        this.maxWaveRadius = Math.max(rx, Math.max(ry, rz));
    }

    public boolean isDone() { return done; }
    public boolean isFailed() { return failed; }

    public float progress() {
        final long[] d = dirty;
        if (d != null) {
            return Math.min(1f, (float) dirtyCursor / (float) d.length);
        }
        if (totalBlocks == 0) return 1f;
        return Math.min(1f, (float) cursorCount.get() / (float) totalBlocks);
    }

    public @NotNull RegenMode mode() { return mode; }
    public int blocksApplied() { return writtenCount.get(); }
    public int blocksSkipped() { return skippedCount.get(); }
    public int blocksExcluded() { return excludedCount.get(); }
    public int totalBlocks() { return totalBlocks; }

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

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean dirtyMode() {
        return dirty != null;
    }

    private boolean aborted() {
        return cancelled || failed;
    }

    private void fireComplete() {
        if (!completionFired.compareAndSet(false, true)) {
            return;
        }
        if (onComplete == null) {
            return;
        }
        try {
            onComplete.accept(this);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Regen onComplete handler failed", t);
        }
    }

    // -----------------------------------------------------------------------
    // Batch dispatch
    // -----------------------------------------------------------------------

    /**
     * Apply work until the write budget OR {@code nanoBudget} is used up.
     * The manager must not call this again until {@code onBatchDone} runs.
     */
    public void applyBatch(final int blockBudget, final long nanoBudget,
                           @NotNull final Runnable onBatchDone) {
        if (done || aborted()) {
            onBatchDone.run();
            return;
        }
        if (totalBlocks == 0 || totalChunks <= 0) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        if (dirty != null) {
            applyDirtyBatch(blockBudget, nanoBudget, onBatchDone);
            return;
        }
        switch (mode) {
            case LAYER_BY_LAYER -> applyLayerBatch(blockBudget, nanoBudget, onBatchDone);
            case ANIMATED -> applyWaveBatch(blockBudget, nanoBudget, onBatchDone);
            case CHUNK_BY_CHUNK -> applyChunkBatch(Math.max(64, blockBudget / 2), nanoBudget, true, onBatchDone);
            default -> applyChunkBatch(blockBudget, nanoBudget, false, onBatchDone);
        }
    }

    /** Run {@code work} on the region owning the chunk, then always release the batch. */
    private void scheduleRegion(final int chunkX, final int chunkZ,
                                @NotNull final Runnable work,
                                @NotNull final Runnable onBatchDone) {
        scheduler.runAtChunk(world, chunkX, chunkZ, () -> {
            try {
                if (!aborted()) {
                    work.run();
                }
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().error("Region restore batch failed", t);
                failed = true;
            } finally {
                finishIfDone();
                onBatchDone.run();
            }
        }, () -> {
            plugin.getSLF4JLogger().error("Region schedule failed — aborting regen batch");
            failed = true;
            finishIfDone();
            onBatchDone.run();
        });
    }

    private int chunkXAt(final int local) {
        return minChunkX + (local % chunksX);
    }

    private int chunkZAt(final int local) {
        return minChunkZ + (local / chunksX);
    }

    private boolean ownsChunk(final int local) {
        return scheduler.isOwnedByCurrentRegion(world, chunkXAt(local), chunkZAt(local));
    }

    // ---- dirty voxels ----

    private void applyDirtyBatch(final int blockBudget, final long nanoBudget,
                                 @NotNull final Runnable onBatchDone) {
        final long[] d = dirty;
        final int start = dirtyCursor;
        if (d == null || start >= d.length) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        final int chunkX = ChangeTracker.unpackX(d[start]) >> 4;
        final int chunkZ = ChangeTracker.unpackZ(d[start]) >> 4;
        scheduleRegion(chunkX, chunkZ, () -> {
            final Budget b = new Budget(Math.max(64, blockBudget), nanoBudget);
            int i = start;
            int curCx = chunkX;
            int curCz = chunkZ;
            while (i < d.length && !aborted()) {
                final long packed = d[i];
                final int x = ChangeTracker.unpackX(packed);
                final int z = ChangeTracker.unpackZ(packed);
                if ((x >> 4) != curCx || (z >> 4) != curCz) {
                    // Next chunk: only continue if budget remains and this thread owns it.
                    if (b.exhausted() || !scheduler.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) {
                        break;
                    }
                    curCx = x >> 4;
                    curCz = z >> 4;
                }
                restorePacked(packed, b);
                i++;
                if ((i & 15) == 0 && b.exhausted()) {
                    // Finish nothing partial: a single voxel is atomic anyway.
                    break;
                }
            }
            if (i == start && i < d.length) {
                i++; // guarantee forward progress
            }
            dirtyCursor = i;
            b.flush();
        }, onBatchDone);
    }

    private void restorePacked(final long packed, @NotNull final Budget b) {
        final int x = ChangeTracker.unpackX(packed);
        final int y = ChangeTracker.unpackY(packed);
        final int z = ChangeTracker.unpackZ(packed);
        b.visited++;
        if (!exclusions.isEmpty() && exclusions.contains(x, y, z)) {
            b.excluded++;
            return;
        }
        final int flat = snapshot.flatIndex(x, y, z, originX, originY, originZ);
        if (flat < 0) return;
        restoreVoxel(world.getBlockAt(x, y, z), flat, x, y, z, b);
    }

    // ---- INSTANT / CHUNK_BY_CHUNK ----

    private void applyChunkBatch(final int blockBudget, final long nanoBudget,
                                 final boolean serialChunks,
                                 @NotNull final Runnable onBatchDone) {
        if (chunkCursor >= totalChunks) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        final int start = chunkCursor;
        // Cap parallel width under tight budgets so INSTANT cannot spike the tick.
        int n = serialChunks ? 1 : Math.max(1, Math.min(parallelism, totalChunks - start));
        if (blockBudget < 256 || nanoBudget < 500_000L) {
            n = 1;
        } else if (blockBudget < 1024 || nanoBudget < 2_000_000L) {
            n = Math.min(n, 2);
        }
        if (n == 1) {
            dispatchSerialChunks(start, blockBudget, nanoBudget, !serialChunks, onBatchDone);
            return;
        }
        dispatchParallelChunks(start, n, blockBudget, nanoBudget, onBatchDone);
    }

    /**
     * Restore from the cursor onwards on one region: resumes the current chunk's
     * Y position, and (when {@code continueAcross}) moves on to following chunks
     * that the same region owns while budget remains.
     */
    private void dispatchSerialChunks(final int start, final int blockBudget, final long nanoBudget,
                                      final boolean continueAcross,
                                      @NotNull final Runnable onBatchDone) {
        scheduleRegion(chunkXAt(start), chunkZAt(start), () -> {
            final Budget b = new Budget(blockBudget, nanoBudget);
            int local = start;
            int relY = ySlice;
            try {
                while (local < totalChunks && !aborted()) {
                    final int nextY = restoreChunk(chunkXAt(local), chunkZAt(local), relY, sizeY - 1, b, false);
                    if (nextY < sizeY) {
                        relY = nextY; // budget ran out mid-chunk
                        break;
                    }
                    local++;
                    relY = 0;
                    if (!continueAcross || b.exhausted() || local >= totalChunks || !ownsChunk(local)) {
                        break;
                    }
                }
            } finally {
                chunkCursor = local;
                ySlice = relY;
                b.flush();
            }
        }, onBatchDone);
    }

    /**
     * Parallel Folia path: each sibling chunk finishes the same 16-high Y slice so
     * the shared {@code ySlice} cursor can never skip unfinished voxels.
     */
    private void dispatchParallelChunks(final int start, final int n, final int blockBudget, final long nanoBudget,
                                        @NotNull final Runnable onBatchDone) {
        final int slice = ySlice;
        final int relY1 = Math.min(sizeY - 1, slice + 15);
        final AtomicInteger remaining = new AtomicInteger(n);
        final Runnable finishSibling = () -> {
            if (remaining.decrementAndGet() == 0) {
                final int nextSlice = relY1 + 1;
                if (nextSlice >= sizeY) {
                    ySlice = 0;
                    chunkCursor = start + n;
                } else {
                    ySlice = nextSlice;
                }
                finishIfDone();
                onBatchDone.run();
            }
        };
        for (int i = 0; i < n; i++) {
            final int cx = chunkXAt(start + i);
            final int cz = chunkZAt(start + i);
            scheduler.runAtChunk(world, cx, cz, () -> {
                final Budget b = new Budget(Math.max(64, blockBudget / n), nanoBudget);
                try {
                    if (!aborted()) {
                        restoreChunk(cx, cz, slice, relY1, b, true);
                    }
                } catch (final Throwable t) {
                    plugin.getSLF4JLogger().error("Restore failed in chunk {},{}", cx, cz, t);
                    failed = true;
                } finally {
                    b.flush();
                    finishSibling.run();
                }
            }, () -> {
                failed = true;
                finishSibling.run();
            });
        }
    }

    // ---- LAYER_BY_LAYER ----

    private void applyLayerBatch(final int blockBudget, final long nanoBudget,
                                 @NotNull final Runnable onBatchDone) {
        if (layerY >= sizeY) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        final int start = chunkCursor;
        scheduleRegion(chunkXAt(start), chunkZAt(start), () -> {
            final Budget b = new Budget(Math.max(64, blockBudget), nanoBudget);
            int local = start;
            int y = layerY;
            try {
                while (y < sizeY && !aborted()) {
                    restoreChunk(chunkXAt(local), chunkZAt(local), y, y, b, true);
                    local++;
                    if (local >= totalChunks) {
                        local = 0;
                        y++;
                    }
                    if (y >= sizeY || b.exhausted() || !ownsChunk(local)) {
                        break;
                    }
                }
            } finally {
                chunkCursor = local;
                layerY = y;
                b.flush();
            }
        }, onBatchDone);
    }

    // ---- ANIMATED (expanding Chebyshev shell) ----

    private void applyWaveBatch(final int blockBudget, final long nanoBudget,
                                @NotNull final Runnable onBatchDone) {
        // Skip chunks the current shell does not touch without dispatching anything.
        if (!advanceWaveCursor()) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        final int start = chunkCursor;
        scheduleRegion(chunkXAt(start), chunkZAt(start), () -> {
            final Budget b = new Budget(Math.max(64, blockBudget), nanoBudget);
            try {
                while (!aborted()) {
                    final int local = chunkCursor;
                    if (!restoreChunkWave(chunkXAt(local), chunkZAt(local), waveRadius, b)) {
                        break; // budget exhausted; resume point saved
                    }
                    chunkCursor = local + 1;
                    if (!advanceWaveCursor() || b.exhausted() || !ownsChunk(chunkCursor)) {
                        break;
                    }
                }
            } finally {
                b.flush();
            }
        }, onBatchDone);
    }

    /**
     * Move the wave cursor to the next chunk that intersects the current shell,
     * rolling over to the next radius as needed.
     *
     * @return false once every radius is done.
     */
    private boolean advanceWaveCursor() {
        while (waveRadius <= maxWaveRadius) {
            int local = chunkCursor;
            while (local < totalChunks && !waveTouches(local, waveRadius)) {
                local++;
            }
            if (local < totalChunks) {
                if (local != chunkCursor) {
                    chunkCursor = local;
                    waveResumeY = Integer.MIN_VALUE;
                }
                return true;
            }
            chunkCursor = 0;
            waveResumeY = Integer.MIN_VALUE;
            waveRadius = waveRadius + 1;
        }
        return false;
    }

    private boolean waveTouches(final int local, final int radius) {
        final int cx = chunkXAt(local);
        final int cz = chunkZAt(local);
        final int x0 = Math.max(originX, cx << 4);
        final int x1 = Math.min(originX + sizeX - 1, (cx << 4) + 15);
        final int z0 = Math.max(originZ, cz << 4);
        final int z1 = Math.min(originZ + sizeZ - 1, (cz << 4) + 15);
        if (x0 > x1 || z0 > z1) return false;
        final int minDx = (midX >= x0 && midX <= x1) ? 0 : Math.min(Math.abs(x0 - midX), Math.abs(x1 - midX));
        final int minDz = (midZ >= z0 && midZ <= z1) ? 0 : Math.min(Math.abs(z0 - midZ), Math.abs(z1 - midZ));
        final int maxDx = Math.max(Math.abs(x0 - midX), Math.abs(x1 - midX));
        final int maxDz = Math.max(Math.abs(z0 - midZ), Math.abs(z1 - midZ));
        final int maxDy = Math.max(midY - originY, originY + sizeY - 1 - midY);
        final int minCheb = Math.max(minDx, minDz);
        final int maxCheb = Math.max(maxDx, Math.max(maxDz, maxDy));
        return radius >= minCheb && radius <= maxCheb;
    }

    /**
     * Restore the shell {@code cheb == radius} inside one chunk column. Visits
     * only shell cells (O(shell) instead of O(volume) per radius) and saves a
     * row-level resume point when the budget runs out, so a partially written
     * shell is never re-written (the old code restarted the chunk each batch and
     * could loop forever when delta-skip was off).
     *
     * @return true when the chunk's shell is complete.
     */
    private boolean restoreChunkWave(final int chunkX, final int chunkZ, final int radius,
                                     @NotNull final Budget b) {
        final Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        final int absX0 = Math.max(originX, chunkX << 4);
        final int absX1 = Math.min(originX + sizeX - 1, (chunkX << 4) + 15);
        final int absZ0 = Math.max(originZ, chunkZ << 4);
        final int absZ1 = Math.min(originZ + sizeZ - 1, (chunkZ << 4) + 15);
        final int yLo = Math.max(originY, midY - radius);
        final int yHi = Math.min(originY + sizeY - 1, midY + radius);
        final int zLo = Math.max(absZ0, midZ - radius);
        final int zHi = Math.min(absZ1, midZ + radius);
        final int xLo = Math.max(absX0, midX - radius);
        final int xHi = Math.min(absX1, midX + radius);
        if (yLo > yHi || zLo > zHi || xLo > xHi) {
            waveResumeY = Integer.MIN_VALUE;
            return true;
        }
        int y = yLo;
        int zStart = zLo;
        if (waveResumeY != Integer.MIN_VALUE) {
            y = waveResumeY;
            zStart = waveResumeZ;
        }
        for (; y <= yHi; y++, zStart = zLo) {
            final int dy = Math.abs(y - midY);
            final int ry = y - originY;
            for (int z = zStart; z <= zHi; z++) {
                if (aborted()) {
                    waveResumeY = y;
                    waveResumeZ = z;
                    return false;
                }
                final int dz = Math.abs(z - midZ);
                final int rowBase = sizeX * ((z - originZ) + sizeZ * ry);
                if (dy == radius || dz == radius) {
                    // Whole row of the shell face.
                    for (int x = xLo; x <= xHi; x++) {
                        waveVoxel(chunk, x, y, z, rowBase, b);
                    }
                } else {
                    // Interior row: only the two x faces.
                    final int xa = midX - radius;
                    final int xb = midX + radius;
                    if (xa >= xLo && xa <= xHi) waveVoxel(chunk, xa, y, z, rowBase, b);
                    if (xb != xa && xb >= xLo && xb <= xHi) waveVoxel(chunk, xb, y, z, rowBase, b);
                }
                if (b.exhausted()) {
                    if (z < zHi) {
                        waveResumeY = y;
                        waveResumeZ = z + 1;
                        return false;
                    }
                    if (y < yHi) {
                        waveResumeY = y + 1;
                        waveResumeZ = zLo;
                        return false;
                    }
                }
            }
        }
        waveResumeY = Integer.MIN_VALUE;
        return true;
    }

    private void waveVoxel(@NotNull final Chunk chunk, final int x, final int y, final int z,
                           final int rowBase, @NotNull final Budget b) {
        b.visited++;
        if (!exclusions.isEmpty() && exclusions.contains(x, y, z)) {
            b.excluded++;
            return;
        }
        restoreVoxel(chunk.getBlock(x & 15, y, z & 15), (x - originX) + rowBase, x, y, z, b);
    }

    // -----------------------------------------------------------------------
    // Voxel work
    // -----------------------------------------------------------------------

    /**
     * Restore relative Y {@code relY0..relY1} of one chunk column. Budget is
     * checked after each full Y layer so no layer is left half-written.
     *
     * @return the next relative Y that still needs work ({@code relY1 + 1} when done).
     */
    private int restoreChunk(final int chunkX, final int chunkZ,
                             final int relY0, final int relY1,
                             @NotNull final Budget b, final boolean mustFinish) {
        final int absX0 = Math.max(originX, chunkX << 4);
        final int absX1 = Math.min(originX + sizeX - 1, (chunkX << 4) + 15);
        final int absZ0 = Math.max(originZ, chunkZ << 4);
        final int absZ1 = Math.min(originZ + sizeZ - 1, (chunkZ << 4) + 15);
        final int absY0 = originY + Math.max(0, relY0);
        final int absY1 = originY + Math.min(sizeY - 1, relY1);
        if (absX0 > absX1 || absZ0 > absZ1 || absY0 > absY1) {
            return Math.min(sizeY, relY1 + 1);
        }
        final Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (safeMode) {
            liftLiving(chunk, absX0, absY0, absZ0, absX1 + 1, absY1 + 1, absZ1 + 1);
        }
        final boolean hasExclusions = !exclusions.isEmpty();
        for (int y = absY0; y <= absY1; y++) {
            final int ry = y - originY;
            if (aborted()) {
                return ry;
            }
            for (int z = absZ0; z <= absZ1; z++) {
                final int rowBase = sizeX * ((z - originZ) + sizeZ * ry);
                for (int x = absX0; x <= absX1; x++) {
                    b.visited++;
                    if (hasExclusions && exclusions.contains(x, y, z)) {
                        b.excluded++;
                        continue;
                    }
                    restoreVoxel(chunk.getBlock(x & 15, y, z & 15), (x - originX) + rowBase, x, y, z, b);
                }
            }
            if (!mustFinish && y < absY1 && b.exhausted()) {
                return ry + 1;
            }
        }
        return relY1 + 1;
    }

    private void restoreVoxel(@NotNull final Block block, final int flat,
                              final int x, final int y, final int z,
                              @NotNull final Budget b) {
        if (flat < 0 || flat >= ids.length) {
            return;
        }
        final int palId = ids[flat];
        if (palId < 0 || palId >= palette.length) {
            return;
        }
        final BitSet mask = tileMask;
        final byte[] tile = (mask != null && mask.get(flat)) ? tiles.get(flat) : null;
        final BlockData target = palette[palId];
        if (deltaSkip && tile == null) {
            if (airFlags[palId]) {
                if (block.getType().isAir()) {
                    b.skipped++;
                    return;
                }
            } else if (block.getBlockData().equals(target)) {
                b.skipped++;
                return;
            }
        }
        Object nms = null;
        if (nmsLevel != null) {
            nms = nmsPalette[palId];
            if (nms == null) {
                nms = FastBlockWriter.resolveNms(target);
                nmsPalette[palId] = nms;
            }
        }
        if (!FastBlockWriter.set(nmsLevel, nms, x, y, z, block, target)) {
            return;
        }
        b.written++;
        if (tile != null) {
            restoreTile(block, tile, x, y, z);
        }
    }

    /**
     * Safe mode: lift living entities standing in the slice being rebuilt.
     * Only the chunk's own entity list is read (always region-owned on Folia),
     * and teleports are async (sync {@code teleport} throws on Folia).
     */
    private void liftLiving(@NotNull final Chunk chunk,
                            final int minX, final int minY, final int minZ,
                            final int maxX, final int maxY, final int maxZ) {
        try {
            for (final Entity e : chunk.getEntities()) {
                if (!(e instanceof LivingEntity) || e instanceof ArmorStand) {
                    continue;
                }
                final Location loc = e.getLocation();
                if (loc.getX() < minX || loc.getX() >= maxX
                        || loc.getY() < minY || loc.getY() >= maxY
                        || loc.getZ() < minZ || loc.getZ() >= maxZ) {
                    continue;
                }
                if (exclusions.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                    continue;
                }
                loc.setY(maxY + 0.2);
                e.teleportAsync(loc);
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("Safe-mode entity lift failed", t);
        }
    }

    private void restoreTile(@NotNull final Block block, final byte @NotNull [] tile,
                             final int x, final int y, final int z) {
        try {
            final BlockState state = block.getState();
            TileEntitySerializer.restore(state, tile);
            state.update(true, false);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Tile restore failed at {},{},{}", x, y, z, t);
        }
    }

    // -----------------------------------------------------------------------
    // Completion
    // -----------------------------------------------------------------------

    private boolean blocksComplete() {
        final long[] d = dirty;
        if (d != null) {
            return dirtyCursor >= d.length;
        }
        return switch (mode) {
            case LAYER_BY_LAYER -> layerY >= sizeY;
            case ANIMATED -> waveRadius > maxWaveRadius;
            default -> chunkCursor >= totalChunks;
        };
    }

    private void finishIfDone() {
        if (aborted()) {
            done = true;
            fireComplete();
            return;
        }
        if (onProgress != null) {
            try {
                onProgress.accept(progress());
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("Regen onProgress failed", t);
            }
        }
        if (!blocksComplete() || done || finishing || completionFired.get()) {
            return;
        }
        finishing = true;
        final Map<Long, List<Snapshot.EntityPayload>> byChunk = groupEntitiesByChunk();
        restoreEntitiesFrom(0, byChunk);
    }

    private @NotNull Map<Long, List<Snapshot.EntityPayload>> groupEntitiesByChunk() {
        final Map<Long, List<Snapshot.EntityPayload>> byChunk = new HashMap<>();
        for (final Snapshot.EntityPayload ep : snapshot.entities()) {
            final int bx = (int) Math.floor(ep.x());
            final int by = (int) Math.floor(ep.y());
            final int bz = (int) Math.floor(ep.z());
            if (exclusions.contains(bx, by, bz)) {
                continue;
            }
            byChunk.computeIfAbsent(chunkKey(bx >> 4, bz >> 4), k -> new ArrayList<>()).add(ep);
        }
        return byChunk;
    }

    private static long chunkKey(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * Decorative entities are swapped back chunk by chunk on the region that owns
     * each chunk (Folia forbids touching entities of another region; the old
     * single whole-arena {@code getNearbyEntities} call ran on one region only).
     * Consecutive chunks owned by the same region are handled in one task.
     */
    private void restoreEntitiesFrom(final int startLocal,
                                     @NotNull final Map<Long, List<Snapshot.EntityPayload>> byChunk) {
        if (startLocal >= totalChunks) {
            completeSuccessfully();
            return;
        }
        scheduler.runAtChunk(world, chunkXAt(startLocal), chunkZAt(startLocal), () -> {
            int local = startLocal;
            try {
                if (aborted()) {
                    done = true;
                    fireComplete();
                    return;
                }
                do {
                    restoreEntitiesInChunk(chunkXAt(local), chunkZAt(local), byChunk);
                    local++;
                } while (local < totalChunks && ownsChunk(local));
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().error("Entity restore failed during regen finish", t);
                failed = true;
                done = true;
                fireComplete();
                return;
            }
            restoreEntitiesFrom(local, byChunk);
        }, () -> {
            plugin.getSLF4JLogger().error("Could not schedule entity restore — aborting regen finish");
            failed = true;
            done = true;
            fireComplete();
        });
    }

    private void restoreEntitiesInChunk(final int chunkX, final int chunkZ,
                                        @NotNull final Map<Long, List<Snapshot.EntityPayload>> byChunk) {
        final List<Snapshot.EntityPayload> payloads = byChunk.get(chunkKey(chunkX, chunkZ));
        final Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        try {
            for (final Entity e : chunk.getEntities()) {
                if (!(e instanceof ArmorStand || e instanceof ItemFrame
                        || e instanceof Painting || e instanceof Display)) {
                    continue;
                }
                final Location loc = e.getLocation();
                final int bx = loc.getBlockX();
                final int by = loc.getBlockY();
                final int bz = loc.getBlockZ();
                if (bx < originX || bx >= originX + sizeX
                        || by < originY || by >= originY + sizeY
                        || bz < originZ || bz >= originZ + sizeZ) {
                    continue;
                }
                if (exclusions.contains(bx, by, bz)) {
                    continue;
                }
                e.remove();
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Decorative entity cleanup failed in chunk {},{}", chunkX, chunkZ, t);
        }
        if (payloads == null) {
            return;
        }
        for (final Snapshot.EntityPayload ep : payloads) {
            try {
                EntitySerializer.restore(ep, world);
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().warn("Entity restore failed for {}", ep.type(), t);
            }
        }
    }

    private void completeSuccessfully() {
        done = true;
        if (onProgress != null) {
            try {
                onProgress.accept(1f);
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("Regen onProgress(1) failed", t);
            }
        }
        fireComplete();
    }

    /**
     * Per-batch budget + local counters. Counters are folded into the shared
     * atomics once per batch instead of one atomic RMW per voxel.
     */
    private final class Budget {
        private final int maxWrites;
        private final long deadline;
        int visited;
        int written;
        int skipped;
        int excluded;

        Budget(final int maxWrites, final long nanoBudget) {
            this.maxWrites = Math.max(1, maxWrites);
            this.deadline = System.nanoTime() + Math.max(250_000L, nanoBudget);
        }

        boolean exhausted() {
            return written >= maxWrites || System.nanoTime() >= deadline;
        }

        void flush() {
            if (visited != 0) cursorCount.addAndGet(visited);
            if (written != 0) writtenCount.addAndGet(written);
            if (skipped + excluded != 0) skippedCount.addAndGet(skipped + excluded);
            if (excluded != 0) excludedCount.addAndGet(excluded);
            visited = written = skipped = excluded = 0;
        }
    }
}
