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
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures a cuboid into a {@link Snapshot} without holding a repeating task
 * after the last chunk finishes (the original scanner leaked that timer).
 *
 * @author DravoraX
 */
public final class RegionScanner {

    private final ArenaRegenDXPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final java.util.function.Supplier<Settings.PerformanceSettings> perfSupplier;

    public RegionScanner(@NotNull final ArenaRegenDXPlugin plugin,
                         @NotNull final SchedulerAdapter scheduler,
                         @NotNull final Settings.PerformanceSettings perf) {
        this(plugin, scheduler, () -> perf);
    }

    public RegionScanner(@NotNull final ArenaRegenDXPlugin plugin,
                         @NotNull final SchedulerAdapter scheduler,
                         @NotNull final java.util.function.Supplier<Settings.PerformanceSettings> perfSupplier) {
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
        if (totalBlocks <= 0L || totalBlocks > 100_000_000L) {
            future.completeExceptionally(new IllegalArgumentException(
                    "Region too large: " + totalBlocks + " blocks (max 100M)."));
            return future;
        }

        final int[] blockDataIds = new int[(int) totalBlocks];
        final Map<BlockData, Integer> dedup = new ConcurrentHashMap<>(256);
        final List<BlockData> palette = new ArrayList<>(256);
        final Object dedupLock = new Object();
        final Map<Integer, byte[]> tileEntities = new ConcurrentHashMap<>();
        final List<Snapshot.EntityPayload> entities = new ArrayList<>();

        final int minChunkX = minX >> 4;
        final int maxChunkX = maxX >> 4;
        final int minChunkZ = minZ >> 4;
        final int maxChunkZ = maxZ >> 4;
        final List<int[]> chunkQueue = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunkQueue.add(new int[]{cx, cz});
            }
        }
        final int totalChunks = chunkQueue.size();
        if (totalChunks == 0) {
            future.completeExceptionally(new IllegalArgumentException("Empty region."));
            return future;
        }
        final AtomicInteger remaining = new AtomicInteger(totalChunks);
        final AtomicInteger cursor = new AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean scanFailed = new java.util.concurrent.atomic.AtomicBoolean(false);
        final int batchSize = Math.max(1, perfSupplier.get().chunkBatchSize);
        final long createdAt = System.currentTimeMillis();
        final TaskHandle[] handle = new TaskHandle[1];

        handle[0] = scheduler.runGlobalRepeating(() -> {
            if (future.isDone()) {
                cancelDriver(handle);
                return;
            }
            final int start = cursor.getAndAdd(batchSize);
            if (start >= totalChunks) {
                return;
            }
            final int end = Math.min(totalChunks, start + batchSize);
            for (int i = start; i < end; i++) {
                final int[] chunk = chunkQueue.get(i);
                final int cx = chunk[0];
                final int cz = chunk[1];
                final Location chunkCenter = new Location(world, (cx << 4) + 8, minY, (cz << 4) + 8);
                scheduler.runAtLocation(chunkCenter, () -> {
                    try {
                        if (!scanFailed.get()) {
                            scanChunk(world, cx, cz, minX, minY, minZ, sizeX, sizeY, sizeZ,
                                    blockDataIds, dedup, palette, dedupLock, tileEntities, entities);
                        }
                    } catch (final Throwable t) {
                        plugin.getSLF4JLogger().error("Chunk scan failed at {},{}", cx, cz, t);
                        scanFailed.set(true);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            cancelDriver(handle);
                            if (scanFailed.get()) {
                                future.completeExceptionally(new IllegalStateException(
                                        "Region scan failed — one or more chunks could not be captured"));
                            } else {
                                finishScan(future, sizeX, sizeY, sizeZ, createdAt,
                                        palette, blockDataIds, tileEntities, entities, dedupLock);
                            }
                        }
                    }
                });
            }
        }, 1L, 1L);

        return future;
    }

    private static void cancelDriver(final TaskHandle[] handle) {
        final TaskHandle h = handle[0];
        if (h != null) {
            try {
                h.cancel();
            } catch (final Throwable ignored) {
            }
            handle[0] = null;
        }
    }

    private void finishScan(@NotNull final CompletableFuture<Snapshot> future,
                            final int sizeX, final int sizeY, final int sizeZ,
                            final long createdAt,
                            @NotNull final List<BlockData> palette,
                            @NotNull final int[] blockDataIds,
                            @NotNull final Map<Integer, byte[]> tileEntities,
                            @NotNull final List<Snapshot.EntityPayload> entities,
                            @NotNull final Object dedupLock) {
        final String[] uniqueBlockData;
        synchronized (dedupLock) {
            uniqueBlockData = new String[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                uniqueBlockData[i] = palette.get(i).getAsString();
            }
        }
        final Snapshot snapshot = new Snapshot(
                sizeX, sizeY, sizeZ, createdAt,
                uniqueBlockData, blockDataIds,
                new HashMap<>(tileEntities),
                entities.toArray(new Snapshot.EntityPayload[0])
        );
        snapshot.resolvePalette();
        future.complete(snapshot);
    }

    private void scanChunk(@NotNull final World world, final int chunkX, final int chunkZ,
                           final int originX, final int originY, final int originZ,
                           final int sizeX, final int sizeY, final int sizeZ,
                           final int[] blockDataIds,
                           @NotNull final Map<BlockData, Integer> dedup,
                           @NotNull final List<BlockData> palette,
                           @NotNull final Object dedupLock,
                           @NotNull final Map<Integer, byte[]> tileEntities,
                           @NotNull final List<Snapshot.EntityPayload> entities) {
        final Chunk chunk;
        try {
            chunk = world.getChunkAt(chunkX, chunkZ);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Could not load chunk {},{}", chunkX, chunkZ, t);
            throw new IllegalStateException("Chunk load failed at " + chunkX + "," + chunkZ, t);
        }

        final int blockStartX = Math.max(originX, chunkX << 4);
        final int blockEndX = Math.min(originX + sizeX - 1, (chunkX << 4) + 15);
        final int blockStartZ = Math.max(originZ, chunkZ << 4);
        final int blockEndZ = Math.min(originZ + sizeZ - 1, (chunkZ << 4) + 15);
        final int blockStartY = originY;
        final int blockEndY = originY + sizeY - 1;

        for (int y = blockStartY; y <= blockEndY; y++) {
            for (int z = blockStartZ; z <= blockEndZ; z++) {
                final int rz = z - originZ;
                final int rowBase = sizeX * (rz + sizeZ * (y - originY));
                for (int x = blockStartX; x <= blockEndX; x++) {
                    final Block block = chunk.getBlock(x & 15, y, z & 15);
                    final BlockData data = block.getBlockData();
                    final int dataId = intern(data, dedup, palette, dedupLock);
                    blockDataIds[(x - originX) + rowBase] = dataId;
                }
            }
        }

        // Tile entities: iterate the chunk's tile list instead of getState() per block.
        try {
            for (final BlockState state : chunk.getTileEntities()) {
                if (!(state instanceof TileState)) {
                    continue;
                }
                final int x = state.getX();
                final int y = state.getY();
                final int z = state.getZ();
                if (x < originX || x >= originX + sizeX
                        || y < originY || y >= originY + sizeY
                        || z < originZ || z >= originZ + sizeZ) {
                    continue;
                }
                try {
                    final byte[] payload = TileEntitySerializer.capture(state);
                    if (payload != null) {
                        final int flat = (x - originX) + sizeX * ((z - originZ) + sizeZ * (y - originY));
                        tileEntities.put(flat, payload);
                    }
                } catch (final Throwable t) {
                    plugin.getSLF4JLogger().warn("Tile entity capture failed at {},{},{}", x, y, z, t);
                }
            }
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Tile entity scan failed in chunk {},{}", chunkX, chunkZ, t);
        }

        for (final Entity entity : chunk.getEntities()) {
            final Location loc = entity.getLocation();
            if (loc.getBlockX() < originX || loc.getBlockX() >= originX + sizeX) continue;
            if (loc.getBlockY() < originY || loc.getBlockY() >= originY + sizeY) continue;
            if (loc.getBlockZ() < originZ || loc.getBlockZ() >= originZ + sizeZ) continue;
            try {
                final Snapshot.EntityPayload payload = EntitySerializer.capture(entity);
                if (payload != null) {
                    synchronized (entities) {
                        entities.add(payload);
                    }
                }
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().warn("Entity capture failed for {}", entity.getType(), t);
            }
        }
    }

    private int intern(@NotNull final BlockData data,
                       @NotNull final Map<BlockData, Integer> dedup,
                       @NotNull final List<BlockData> palette,
                       @NotNull final Object lock) {
        final Integer existing = dedup.get(data);
        if (existing != null) {
            return existing;
        }
        synchronized (lock) {
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
}
