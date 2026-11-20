/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.snapshot;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.config.Settings;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.scheduler.TaskHandle;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Captures a cuboid into a {@link Snapshot} with minimal server-thread time.
 *
 * <p>Per chunk, the owning region thread only (1) loads the chunk
 * asynchronously, (2) copies a {@link ChunkSnapshot} and (3) serializes the
 * few tile entities / decorative entities that need live objects. Reading
 * every voxel and interning its {@link BlockData} happens off-thread on the
 * immutable chunk snapshot, so saving a huge warzone no longer stalls ticks.</p>
 *
 * @author DravoraX
 */
public final class RegionScanner {

    private final ArenaRegenDXPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final Supplier<Settings.PerformanceSettings> perfSupplier;

    public RegionScanner(@NotNull final ArenaRegenDXPlugin plugin,
                         @NotNull final SchedulerAdapter scheduler,
                         @NotNull final Supplier<Settings.PerformanceSettings> perfSupplier) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.perfSupplier = perfSupplier;
    }

    public @NotNull CompletableFuture<Snapshot> scan(@NotNull final World world,
                                                     @NotNull final Location min,
                                                     @NotNull final Location max) {
        final CompletableFuture<Snapshot> future = new CompletableFuture<>();

        final int minX = Math.min(min.getBlockX(), max.getBlockX());
        final int minY = Math.min(min.getBlockY(), max.getBlockY());
        final int minZ = Math.min(min.getBlockZ(), max.getBlockZ());
        final int maxX = Math.max(min.getBlockX(), max.getBlockX());
        final int maxY = Math.max(min.getBlockY(), max.getBlockY());
        final int maxZ = Math.max(min.getBlockZ(), max.getBlockZ());
        final int sizeX = maxX - minX + 1;
        final int sizeY = maxY - minY + 1;
        final int sizeZ = maxZ - minZ + 1;
        final long totalBlocks = (long) sizeX * sizeY * sizeZ;
        if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) {
            future.completeExceptionally(new IllegalArgumentException("Region Y " + minY + ".." + maxY
                    + " is outside the world height " + world.getMinHeight() + ".." + (world.getMaxHeight() - 1) + "."));
            return future;
        }
        if (totalBlocks <= 0L || totalBlocks > SnapshotSerializer.MAX_VOLUME) {
            future.completeExceptionally(new IllegalArgumentException(
                    "Region must hold 1.." + SnapshotSerializer.MAX_VOLUME + " blocks (got "
                            + sizeX + "x" + sizeY + "x" + sizeZ + ")."));
            return future;
        }

        final ScanState state = new ScanState(world, minX, minY, minZ, sizeX, sizeY, sizeZ, (int) totalBlocks);

        final int minChunkX = minX >> 4;
        final int maxChunkX = maxX >> 4;
        final int minChunkZ = minZ >> 4;
        final int maxChunkZ = maxZ >> 4;
        final List<long[]> chunkQueue = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunkQueue.add(new long[]{cx, cz});
            }
        }
        final int totalChunks = chunkQueue.size();
        final AtomicInteger remaining = new AtomicInteger(totalChunks);
        final AtomicInteger cursor = new AtomicInteger(0);
        final int batchSize = Math.max(1, perfSupplier.get().chunkBatchSize);
        final TaskHandle[] driver = new TaskHandle[1];

        final Runnable onChunkDone = () -> {
            if (remaining.decrementAndGet() != 0) {
                return;
            }
            cancel(driver);
            if (state.failed.get()) {
                future.completeExceptionally(new IllegalStateException(
                        "Region scan failed - one or more chunks could not be captured (see console)."));
                return;
            }
            try {
                future.complete(state.build());
            } catch (final Throwable t) {
                future.completeExceptionally(t);
            }
        };

        driver[0] = scheduler.runGlobalRepeating(() -> {
            if (future.isDone()) {
                cancel(driver);
                return;
            }
            final int start = cursor.getAndAdd(batchSize);
            if (start >= totalChunks) {
                return;
            }
            final int end = Math.min(totalChunks, start + batchSize);
            for (int i = start; i < end; i++) {
                final int cx = (int) chunkQueue.get(i)[0];
                final int cz = (int) chunkQueue.get(i)[1];
                scheduler.ensureChunkLoaded(world, cx, cz, () -> captureChunk(state, cx, cz, onChunkDone));
            }
        }, 1L, 1L);

        return future;
    }

    /** Runs on the region thread owning the chunk. */
    private void captureChunk(@NotNull final ScanState s, final int chunkX, final int chunkZ,
                              @NotNull final Runnable onChunkDone) {
        if (s.failed.get()) {
            onChunkDone.run();
            return;
        }
        final ChunkSnapshot snapshot;
        try {
            final Chunk chunk = s.world.getChunkAt(chunkX, chunkZ);
            snapshot = chunk.getChunkSnapshot(false, false, false);
            captureTilesAndEntities(s, chunk);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().error("Chunk capture failed at {},{}", chunkX, chunkZ, t);
            s.failed.set(true);
            onChunkDone.run();
            return;
        }
        // Voxel reads happen off-thread on the immutable copy.
        scheduler.runAsync(() -> {
            try {
                readVoxels(s, snapshot, chunkX, chunkZ);
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().error("Chunk voxel read failed at {},{}", chunkX, chunkZ, t);
                s.failed.set(true);
            } finally {
                onChunkDone.run();
            }
        });
    }

    private void readVoxels(@NotNull final ScanState s, @NotNull final ChunkSnapshot cs,
                            final int chunkX, final int chunkZ) {
        final int x0 = Math.max(s.originX, chunkX << 4);
        final int x1 = Math.min(s.originX + s.sizeX - 1, (chunkX << 4) + 15);
        final int z0 = Math.max(s.originZ, chunkZ << 4);
        final int z1 = Math.min(s.originZ + s.sizeZ - 1, (chunkZ << 4) + 15);
        final int y1 = s.originY + s.sizeY - 1;
        // Tiny per-chunk cache: consecutive voxels are usually the same block.
        BlockData last = null;
        int lastId = -1;
        for (int y = s.originY; y <= y1; y++) {
            final int ry = y - s.originY;
            for (int z = z0; z <= z1; z++) {
                final int rowBase = s.sizeX * ((z - s.originZ) + s.sizeZ * ry);
                for (int x = x0; x <= x1; x++) {
                    final BlockData data = cs.getBlockData(x & 15, y, z & 15);
                    final int id;
                    if (data.equals(last)) {
                        id = lastId;
                    } else {
                        id = s.intern(data);
                        last = data;
                        lastId = id;
                    }
                    s.ids[(x - s.originX) + rowBase] = id;
                }
            }
        }
    }

    private void captureTilesAndEntities(@NotNull final ScanState s, @NotNull final Chunk chunk) {
        try {
            for (final BlockState state : chunk.getTileEntities()) {
                if (!(state instanceof TileState)) {
                    continue;
                }
                final int x = state.getX();
                final int y = state.getY();
                final int z = state.getZ();
                if (!s.contains(x, y, z)) {
                    continue;
                }
                try {
                    final byte[] payload = TileEntitySerializer.capture(state);
                    if (payload != null) {
                        final int flat = (x - s.originX) + s.sizeX * ((z - s.originZ) + s.sizeZ * (y - s.originY));
                        synchronized (s.tiles) {
                            s.tiles.put(flat, payload);
                        }
                    }
                } catch (final Throwable t) {
                    plugin.getSLF4JLogger().warn("Tile entity capture failed at {},{},{}", x, y, z, t);
                }
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Tile entity scan failed in chunk {},{}", chunk.getX(), chunk.getZ(), t);
        }

        for (final Entity entity : chunk.getEntities()) {
            final Location loc = entity.getLocation();
            if (!s.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                continue;
            }
            try {
                final Snapshot.EntityPayload payload = EntitySerializer.capture(entity);
                if (payload != null) {
                    synchronized (s.entities) {
                        s.entities.add(payload);
                    }
                }
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().warn("Entity capture failed for {}", entity.getType(), t);
            }
        }
    }

    private static void cancel(final TaskHandle[] handle) {
        final TaskHandle h = handle[0];
        if (h != null) {
            h.cancel();
            handle[0] = null;
        }
    }

    /** Mutable state shared by every chunk of one scan. */
    private static final class ScanState {
        final World world;
        final int originX, originY, originZ, sizeX, sizeY, sizeZ;
        final int[] ids;
        final Map<BlockData, Integer> dedup = new ConcurrentHashMap<>(256);
        final List<BlockData> palette = new ArrayList<>(256);
        final Int2ObjectOpenHashMap<byte[]> tiles = new Int2ObjectOpenHashMap<>();
        final List<Snapshot.EntityPayload> entities = new ArrayList<>();
        final AtomicBoolean failed = new AtomicBoolean(false);
        final long createdAt = System.currentTimeMillis();

        ScanState(final World world, final int originX, final int originY, final int originZ,
                  final int sizeX, final int sizeY, final int sizeZ, final int volume) {
            this.world = world;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.ids = new int[volume];
        }

        boolean contains(final int x, final int y, final int z) {
            return x >= originX && x < originX + sizeX
                    && y >= originY && y < originY + sizeY
                    && z >= originZ && z < originZ + sizeZ;
        }

        int intern(@NotNull final BlockData data) {
            final Integer existing = dedup.get(data);
            if (existing != null) {
                return existing;
            }
            synchronized (palette) {
                final Integer cached = dedup.get(data);
                if (cached != null) {
                    return cached;
                }
                final int id = palette.size();
                palette.add(data);
                dedup.put(data, id);
                return id;
            }
        }

        @NotNull Snapshot build() {
            final String[] unique;
            synchronized (palette) {
                unique = new String[palette.size()];
                for (int i = 0; i < unique.length; i++) {
                    unique[i] = palette.get(i).getAsString();
                }
            }
            final Snapshot.EntityPayload[] ents;
            synchronized (entities) {
                ents = entities.toArray(new Snapshot.EntityPayload[0]);
            }
            final Snapshot snapshot = new Snapshot(sizeX, sizeY, sizeZ, createdAt, unique,
                    PaletteIds.compact(ids, unique.length), tiles, ents);
            snapshot.resolvePalette();
            return snapshot;
        }
    }
}
