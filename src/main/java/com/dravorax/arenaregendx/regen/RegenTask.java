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
import org.bukkit.entity.Painting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Time-sliced restore job. One chunk is owned at a time so Paper keeps a hot
 * chunk in cache and Folia stays on the correct region thread.
 *
 * <p>Lag controls:</p>
 * <ul>
 *   <li>Pre-parsed {@link BlockData} palette — no string parse per voxel.</li>
 *   <li>Delta skip — identical blocks (and air-to-air) are not written.</li>
 *   <li>Exclusion volumes are skipped entirely (protected spawn, etc.).</li>
 *   <li>Millisecond budget so a tick never runs longer than configured.</li>
 *   <li>Physics off. Tile entities restored only where the snapshot has them.</li>
 * </ul>
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

    /** Chunk cursor (row-major over the arena's chunk rectangle). */
    private volatile int chunkCursor = 0;
    /** Relative Y start for the current chunk slice (INSTANT / CHUNK_BY_CHUNK). */
    private volatile int ySlice = 0;
    /** Next relative Y to process inside the current layer (LAYER mode). */
    private volatile int layerY = 0;
    /** Chebyshev radius for WAVE/ANIMATED (no O(n) sort). */
    private volatile int waveRadius = 0;

    private volatile int dirtyCursor;
    /** Ensures {@link #onComplete} runs at most once (cancel / fail / success). */
    private final AtomicBoolean completionFired = new AtomicBoolean(false);
    private final AtomicInteger cursorCount = new AtomicInteger();
    private final AtomicInteger writtenCount = new AtomicInteger();
    private final AtomicInteger skippedCount = new AtomicInteger();
    private final AtomicInteger excludedCount = new AtomicInteger();

    // Legacy volatile mirrors kept in sync for progress reads during migration-safe access.
    private volatile int cursor;
    private volatile int written;
    private volatile int skipped;
    private volatile int excluded;
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
                     @Nullable final List<Long> dirtyBlocks,
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
        if (dirtyBlocks == null || dirtyBlocks.isEmpty()) {
            this.dirty = null;
        } else {
            final java.util.ArrayList<Long> sorted = new java.util.ArrayList<>(dirtyBlocks);
            sorted.sort((a, b) -> {
                final int ax = ChangeTracker.unpackX(a) >> 4;
                final int az = ChangeTracker.unpackZ(a) >> 4;
                final int bx = ChangeTracker.unpackX(b) >> 4;
                final int bz = ChangeTracker.unpackZ(b) >> 4;
                if (ax != bx) return Integer.compare(ax, bx);
                if (az != bz) return Integer.compare(az, bz);
                return Long.compare(a, b);
            });
            this.dirty = sorted.stream().mapToLong(Long::longValue).toArray();
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
        this.totalBlocks = snapshot.blockCount();
        this.minChunkX = originX >> 4;
        this.minChunkZ = originZ >> 4;
        final int maxChunkX = (originX + sizeX - 1) >> 4;
        final int maxChunkZ = (originZ + sizeZ - 1) >> 4;
        this.chunksX = maxChunkX - minChunkX + 1;
        this.chunksZ = maxChunkZ - minChunkZ + 1;
        this.totalChunks = chunksX * chunksZ;
    }

    public boolean isDone() { return done; }
    public boolean isFailed() { return failed; }

    public float progress() {
        if (dirty != null) {
            if (dirty.length == 0) return 1f;
            return Math.min(1f, (float) dirtyCursor / (float) dirty.length);
        }
        if (totalBlocks == 0) return 1f;
        return Math.min(1f, (float) cursorCount.get() / (float) totalBlocks);
    }

    public @NotNull RegenMode mode() { return mode; }
    public int blocksApplied() { return writtenCount.get(); }
    public int blocksSkipped() { return skippedCount.get(); }
    public int blocksExcluded() { return excludedCount.get(); }
    public int totalBlocks() { return totalBlocks; }

    private void bumpCursor() {
        cursor = cursorCount.incrementAndGet();
    }

    private void bumpWritten() {
        written = writtenCount.incrementAndGet();
    }

    private void bumpSkipped() {
        skipped = skippedCount.incrementAndGet();
    }

    private void bumpExcluded() {
        excluded = excludedCount.incrementAndGet();
        bumpSkipped();
    }

    private void scheduleRegion(@NotNull final Location at,
                                @NotNull final Runnable work,
                                @NotNull final Runnable onBatchDone) {
        scheduler.runAtLocation(at, () -> {
            try {
                if (!cancelled && !failed) {
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

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean dirtyMode() {
        return dirty != null;
    }

    /**
     * Apply work until {@code blockBudget} writes OR {@code nanoBudget} elapses.
     * The manager must not call this again until {@code onBatchDone} runs.
     */
    public void applyBatch(final int blockBudget, final long nanoBudget,
                           @NotNull final Runnable onBatchDone) {
        if (done || cancelled || failed) {
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

        if (mode == RegenMode.LAYER_BY_LAYER) {
            applyLayerBatch(blockBudget, nanoBudget, onBatchDone);
            return;
        }
        if (mode == RegenMode.ANIMATED) {
            applyWaveBatch(blockBudget, nanoBudget, onBatchDone);
            return;
        }
        applyChunkBatch(blockBudget, nanoBudget, mode == RegenMode.CHUNK_BY_CHUNK, onBatchDone);
    }

    private void applyDirtyBatch(final int blockBudget, final long nanoBudget,
                                 @NotNull final Runnable onBatchDone) {
        final int start = dirtyCursor;
        if (start >= dirty.length) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        // Process only voxels that share the same chunk as dirty[start] so Folia
        // region ownership is never violated.
        final int anchorX = ChangeTracker.unpackX(dirty[start]);
        final int anchorZ = ChangeTracker.unpackZ(dirty[start]);
        final int chunkX = anchorX >> 4;
        final int chunkZ = anchorZ >> 4;
        final Location at = new Location(world, (chunkX << 4) + 8,
                ChangeTracker.unpackY(dirty[start]), (chunkZ << 4) + 8);
        scheduleRegion(at, () -> {
            final long deadline = System.nanoTime() + Math.max(250_000L, nanoBudget);
            int i = start;
            int writes = 0;
            final int budget = Math.max(64, blockBudget);
            while (i < dirty.length && writes < budget && System.nanoTime() < deadline
                    && !cancelled && !failed) {
                final long packed = dirty[i];
                final int x = ChangeTracker.unpackX(packed);
                final int z = ChangeTracker.unpackZ(packed);
                if ((x >> 4) != chunkX || (z >> 4) != chunkZ) {
                    break;
                }
                restorePacked(packed);
                i++;
                writes++;
            }
            if (i == start && i < dirty.length) {
                i++;
            }
            dirtyCursor = i;
            cursor = i;
            cursorCount.set(i);
        }, onBatchDone);
    }

    private void restorePacked(final long packed) {
        final int x = ChangeTracker.unpackX(packed);
        final int y = ChangeTracker.unpackY(packed);
        final int z = ChangeTracker.unpackZ(packed);
        if (!exclusions.isEmpty() && exclusions.contains(x, y, z)) {
            bumpExcluded();
            return;
        }
        final int flat = snapshot.flatIndex(x, y, z, originX, originY, originZ);
        if (flat < 0) return;
        final int palId = ids[flat];
        if (palId < 0 || palId >= palette.length) return;
        final Block block = world.getBlockAt(x, y, z);
        final byte[] tile = tiles.get(flat);
        if (deltaSkip && tile == null) {
            if (airFlags[palId] && block.getType().isAir()) {
                bumpSkipped();
                return;
            }
            if (block.getBlockData().equals(palette[palId])) {
                bumpSkipped();
                return;
            }
        }
        if (!FastBlockWriter.set(world, x, y, z, block, palette[palId])) return;
        bumpWritten();
        restoreTile(block, tile, x, y, z);
    }

    private void applyChunkBatch(final int blockBudget, final long nanoBudget,
                                 final boolean serialChunks,
                                 @NotNull final Runnable onBatchDone) {
        if (chunkCursor >= totalChunks) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        final int start = chunkCursor;
        final int slice = ySlice;
        final int sliceHeight = serialChunks ? 8 : 16;
        final int relY1 = Math.min(sizeY - 1, slice + sliceHeight - 1);
        // Cap parallel width under tight budgets so INSTANT cannot spike the tick.
        int maxParallel = serialChunks
                ? 1
                : Math.max(1, Math.min(parallelism, totalChunks - start));
        if (blockBudget < 256 || nanoBudget < 500_000L) {
            maxParallel = 1;
        } else if (blockBudget < 1024 || nanoBudget < 2_000_000L) {
            maxParallel = Math.min(maxParallel, 2);
        }
        final int n = maxParallel;
        if (n == 1) {
            dispatchOneChunk(start, slice, relY1, blockBudget, nanoBudget, false, onBatchDone);
            return;
        }
        // Parallel Folia path: finish the requested Y slice fully so shared
        // ySlice cannot skip unfinished voxels in any of the sibling chunks.
        final AtomicInteger remaining = new AtomicInteger(n);
        for (int i = 0; i < n; i++) {
            final int local = start + i;
            final int cx = minChunkX + (local % chunksX);
            final int cz = minChunkZ + (local / chunksX);
            final Location at = new Location(world, (cx << 4) + 8, originY + slice, (cz << 4) + 8);
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
            scheduler.runAtLocation(at, () -> {
                try {
                    if (!cancelled && !failed) {
                        restoreChunk(cx, cz, Math.max(64, blockBudget / n), nanoBudget, slice, relY1, true);
                    }
                } catch (final Throwable t) {
                    plugin.getSLF4JLogger().error("Restore failed in chunk {},{}", cx, cz, t);
                    failed = true;
                } finally {
                    finishSibling.run();
                }
            }, () -> {
                failed = true;
                finishSibling.run();
            });
        }
    }

    private void dispatchOneChunk(final int local, final int slice, final int relY1,
                                  final int blockBudget, final long nanoBudget,
                                  final boolean mustFinishSlice,
                                  @NotNull final Runnable onBatchDone) {
        final int cx = minChunkX + (local % chunksX);
        final int cz = minChunkZ + (local / chunksX);
        final Location at = new Location(world, (cx << 4) + 8, originY + slice, (cz << 4) + 8);
        scheduleRegion(at, () -> {
            final int nextY = restoreChunk(cx, cz, blockBudget, nanoBudget, slice, relY1, mustFinishSlice);
            if (nextY >= sizeY) {
                ySlice = 0;
                chunkCursor = local + 1;
            } else {
                ySlice = nextY;
            }
        }, onBatchDone);
    }

    private void applyLayerBatch(final int blockBudget, final long nanoBudget,
                                 @NotNull final Runnable onBatchDone) {
        if (layerY >= sizeY) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        if (chunkCursor >= totalChunks) {
            chunkCursor = 0;
            layerY++;
            if (layerY >= sizeY) {
                finishIfDone();
                onBatchDone.run();
                return;
            }
        }
        final int y = layerY;
        final int local = chunkCursor;
        final int cx = minChunkX + (local % chunksX);
        final int cz = minChunkZ + (local / chunksX);
        final Location at = new Location(world, (cx << 4) + 8, originY + y, (cz << 4) + 8);
        scheduleRegion(at, () -> {
            restoreChunk(cx, cz, blockBudget, nanoBudget, y, y, true);
            chunkCursor = local + 1;
        }, onBatchDone);
    }

    private void applyWaveBatch(final int blockBudget, final long nanoBudget,
                                @NotNull final Runnable onBatchDone) {
        final int maxRadius = Math.max(sizeX, Math.max(sizeY, sizeZ));
        if (waveRadius > maxRadius) {
            finishIfDone();
            onBatchDone.run();
            return;
        }
        final int radius = waveRadius;
        if (chunkCursor >= totalChunks) {
            chunkCursor = 0;
            waveRadius = radius + 1;
            if (waveRadius > maxRadius) {
                finishIfDone();
                onBatchDone.run();
                return;
            }
        }
        final int local = chunkCursor;
        final int cx = minChunkX + (local % chunksX);
        final int cz = minChunkZ + (local / chunksX);
        final Location at = new Location(world, (cx << 4) + 8, originY, (cz << 4) + 8);
        scheduleRegion(at, () -> {
            if (restoreChunkWave(cx, cz, radius, blockBudget, nanoBudget)) {
                chunkCursor = local + 1;
            }
        }, onBatchDone);
    }

    /**
     * Restore one chunk Y-slice. Completes each Y row before considering a
     * budget abort so we never leave a half-written layer. Returns the next
     * relative Y that still needs work ({@code sizeY} when this chunk is done).
     */
    private int restoreChunk(final int chunkX, final int chunkZ,
                             final int blockBudget, final long nanoBudget,
                             final int relY0, final int relY1,
                             final boolean mustFinishSlice) {
        final Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        final int absX0 = Math.max(originX, chunkX << 4);
        final int absX1 = Math.min(originX + sizeX - 1, (chunkX << 4) + 15);
        final int absZ0 = Math.max(originZ, chunkZ << 4);
        final int absZ1 = Math.min(originZ + sizeZ - 1, (chunkZ << 4) + 15);
        final int absY0 = originY + Math.max(0, relY0);
        final int absY1 = originY + Math.min(sizeY - 1, relY1);
        if (absX0 > absX1 || absZ0 > absZ1 || absY0 > absY1) {
            return Math.min(sizeY, relY1 + 1);
        }

        if (safeMode) {
            liftLiving(absX0, absY0, absZ0, absX1 + 1, absY1 + 1, absZ1 + 1);
        }

        final long deadline = System.nanoTime() + Math.max(250_000L, nanoBudget);
        int writes = 0;
        final boolean hasExclusions = !exclusions.isEmpty();

        for (int y = absY0; y <= absY1; y++) {
            if (cancelled || failed) {
                return ryAbort(relY0, y);
            }
            final int ry = y - originY;
            for (int z = absZ0; z <= absZ1; z++) {
                final int rz = z - originZ;
                final int rowBase = sizeX * (rz + sizeZ * ry);
                for (int x = absX0; x <= absX1; x++) {
                    if (cancelled || failed) {
                        return ry;
                    }
                    bumpCursor();
                    if (hasExclusions && exclusions.contains(x, y, z)) {
                        bumpExcluded();
                        continue;
                    }
                    final int flat = (x - originX) + rowBase;
                    if (flat < 0 || flat >= ids.length) {
                        continue;
                    }
                    final int palId = ids[flat];
                    if (palId < 0 || palId >= palette.length) {
                        continue;
                    }
                    final Block block = chunk.getBlock(x & 15, y, z & 15);
                    final byte[] tile = tiles.get(flat);
                    if (deltaSkip && tile == null) {
                        if (airFlags[palId] && block.getType().isAir()) {
                            bumpSkipped();
                            continue;
                        }
                        final BlockData current = block.getBlockData();
                        if (current.equals(palette[palId])) {
                            bumpSkipped();
                            continue;
                        }
                    }
                    if (!FastBlockWriter.set(world, x, y, z, block, palette[palId])) {
                        continue;
                    }
                    bumpWritten();
                    writes++;
                    restoreTile(block, tile, x, y, z);
                }
            }
            if (!mustFinishSlice && y < absY1
                    && System.nanoTime() >= deadline
                    && writes >= Math.max(32, blockBudget / 4)) {
                return ry + 1;
            }
        }
        return relY1 + 1;
    }

    private int ryAbort(final int relY0, final int absY) {
        return Math.max(relY0, absY - originY);
    }

    private boolean restoreChunkWave(final int chunkX, final int chunkZ, final int radius,
                                     final int blockBudget, final long nanoBudget) {
        final int midX = originX + (sizeX >> 1);
        final int midY = originY + (sizeY >> 1);
        final int midZ = originZ + (sizeZ >> 1);
        final Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        final int absX0 = Math.max(originX, chunkX << 4);
        final int absX1 = Math.min(originX + sizeX - 1, (chunkX << 4) + 15);
        final int absZ0 = Math.max(originZ, chunkZ << 4);
        final int absZ1 = Math.min(originZ + sizeZ - 1, (chunkZ << 4) + 15);
        final boolean hasExclusions = !exclusions.isEmpty();
        final long deadline = System.nanoTime() + Math.max(250_000L, nanoBudget);
        final int budget = Math.max(64, blockBudget);
        int writes = 0;

        for (int y = originY; y < originY + sizeY; y++) {
            final int dy = Math.abs(y - midY);
            if (dy > radius) continue;
            final int ry = y - originY;
            for (int z = absZ0; z <= absZ1; z++) {
                final int dz = Math.abs(z - midZ);
                if (dz > radius) continue;
                final int rz = z - originZ;
                final int rowBase = sizeX * (rz + sizeZ * ry);
                for (int x = absX0; x <= absX1; x++) {
                    final int cheb = Math.max(Math.abs(x - midX), Math.max(dy, dz));
                    if (cheb != radius) continue;
                    bumpCursor();
                    if (hasExclusions && exclusions.contains(x, y, z)) {
                        bumpExcluded();
                        continue;
                    }
                    final int flat = (x - originX) + rowBase;
                    if (flat < 0 || flat >= ids.length) continue;
                    final int palId = ids[flat];
                    if (palId < 0 || palId >= palette.length) continue;
                    final Block block = chunk.getBlock(x & 15, y, z & 15);
                    final byte[] tile = tiles.get(flat);
                    if (deltaSkip && tile == null) {
                        if (airFlags[palId] && block.getType().isAir()) {
                            bumpSkipped();
                            continue;
                        }
                        if (block.getBlockData().equals(palette[palId])) {
                            bumpSkipped();
                            continue;
                        }
                    }
                    if (!FastBlockWriter.set(world, x, y, z, block, palette[palId])) {
                        continue;
                    }
                    bumpWritten();
                    writes++;
                    restoreTile(block, tile, x, y, z);
                    if (writes >= budget || System.nanoTime() >= deadline) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void liftLiving(final int minX, final int minY, final int minZ,
                            final int maxX, final int maxY, final int maxZ) {
        try {
            final var box = new org.bukkit.util.BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
            for (final Entity e : world.getNearbyEntities(box)) {
                if (e instanceof ItemFrame || e instanceof Painting || e instanceof ArmorStand) {
                    continue;
                }
                if (e instanceof org.bukkit.entity.Player || e instanceof org.bukkit.entity.LivingEntity) {
                    final Location above = e.getLocation();
                    above.setY(maxY + 0.2);
                    e.teleport(above);
                }
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("Safe-mode entity lift failed", t);
        }
    }

    private void finishIfDone() {
        if (failed || cancelled) {
            done = true;
            fireComplete();
            return;
        }
        final boolean complete = dirty != null
                ? dirtyCursor >= dirty.length
                : switch (mode) {
            case LAYER_BY_LAYER -> layerY >= sizeY;
            case ANIMATED -> {
                final int maxRadius = Math.max(sizeX, Math.max(sizeY, sizeZ));
                yield waveRadius > maxRadius;
            }
            default -> chunkCursor >= totalChunks;
        };
        if (onProgress != null) {
            try {
                onProgress.accept(progress());
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("Regen onProgress failed", t);
            }
        }
        if (!complete || done || finishing || completionFired.get()) {
            return;
        }
        finishing = true;
        final Location finishAt = new Location(world, originX, originY, originZ);
        scheduler.runAtLocation(finishAt, () -> {
            try {
                if (cancelled || failed) {
                    return;
                }
                restoreEntities();
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().error("Entity restore failed during regen finish", t);
                failed = true;
            } finally {
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
        }, () -> {
            plugin.getSLF4JLogger().error("Could not schedule entity restore — aborting regen finish");
            failed = true;
            done = true;
            fireComplete();
        });
    }

    private void restoreEntities() {
        final int maxX = originX + sizeX;
        final int maxY = originY + sizeY;
        final int maxZ = originZ + sizeZ;
        try {
            final var box = new org.bukkit.util.BoundingBox(originX, originY, originZ, maxX, maxY, maxZ);
            for (final Entity e : world.getNearbyEntities(box)) {
                if (!(e instanceof ArmorStand || e instanceof ItemFrame
                        || e instanceof Painting || e instanceof Display)) {
                    continue;
                }
                final Location loc = e.getLocation();
                if (exclusions.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                    continue;
                }
                e.remove();
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Decorative entity cleanup failed", t);
        }
        for (final Snapshot.EntityPayload ep : snapshot.entities()) {
            if (exclusions.contains((int) Math.floor(ep.x()), (int) Math.floor(ep.y()), (int) Math.floor(ep.z()))) {
                continue;
            }
            try {
                com.dravorax.arenaregendx.snapshot.EntitySerializer.restore(ep, world);
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().warn("Entity restore failed for {}", ep.type(), t);
            }
        }
    }

    private void restoreTile(@NotNull final Block block, final byte @Nullable [] tile,
                             final int x, final int y, final int z) {
        if (tile == null) return;
        try {
            final BlockState state = block.getState();
            TileEntitySerializer.restore(state, tile);
            state.update(true, false);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Tile restore failed at {},{},{}", x, y, z, t);
        }
    }
}
