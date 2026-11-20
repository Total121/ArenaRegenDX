/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.snapshot;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Immutable in-memory snapshot of a cuboid. Block types live in a palette so
 * restore never re-parses {@code BlockData} strings per voxel, and per-voxel
 * palette indices are stored in the narrowest array type that fits
 * ({@link PaletteIds}).
 *
 * @author DravoraX
 */
public final class Snapshot {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final long createdAt;

    private final String[] uniqueBlockData;
    private final PaletteIds ids;
    /** Tile payloads keyed by flat index. Primitive keys: no boxing on restore lookups. */
    private final Int2ObjectOpenHashMap<byte[]> tileEntities;
    private final EntityPayload[] entities;

    /** Resolved Bukkit palette. Built lazily, then reused for every restore. */
    private volatile BlockData[] palette;
    /** True when the matching palette entry is air (skip-air fast path). */
    private volatile boolean[] airFlags;
    /** Lazily computed heap estimate (snapshots are immutable). */
    private volatile long estimatedBytes = -1L;

    public Snapshot(final int sizeX, final int sizeY, final int sizeZ,
                    final long createdAt,
                    @NotNull final String[] uniqueBlockData,
                    @NotNull final PaletteIds ids,
                    @NotNull final Map<Integer, byte[]> tileEntities,
                    @NotNull final EntityPayload[] entities) {
        if (ids.length() != (long) sizeX * sizeY * sizeZ) {
            throw new IllegalArgumentException("Palette id count " + ids.length()
                    + " does not match volume " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.createdAt = createdAt;
        this.uniqueBlockData = uniqueBlockData;
        this.ids = ids;
        this.tileEntities = tileEntities instanceof Int2ObjectOpenHashMap<byte[]> m
                ? m : new Int2ObjectOpenHashMap<>(tileEntities);
        this.tileEntities.trim();
        this.entities = entities;
    }

    /** Convenience constructor for callers that still build a plain {@code int[]}. */
    public Snapshot(final int sizeX, final int sizeY, final int sizeZ,
                    final long createdAt,
                    @NotNull final String[] uniqueBlockData,
                    final int @NotNull [] blockDataIds,
                    @NotNull final Map<Integer, byte[]> tileEntities,
                    @NotNull final EntityPayload[] entities) {
        this(sizeX, sizeY, sizeZ, createdAt, uniqueBlockData,
                PaletteIds.compact(blockDataIds, uniqueBlockData.length), tileEntities, entities);
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public long createdAt() { return createdAt; }
    public @NotNull String[] uniqueBlockData() { return uniqueBlockData; }
    public @NotNull PaletteIds ids() { return ids; }
    public @NotNull Int2ObjectMap<byte[]> tileEntities() { return tileEntities; }
    public @NotNull EntityPayload[] entities() { return entities; }

    /** @return the palette index of the voxel at {@code flatIndex}. */
    public int paletteId(final int flatIndex) {
        return ids.get(flatIndex);
    }

    /** @return the tile payload at {@code flatIndex}, or null (no boxing). */
    public byte @Nullable [] tile(final int flatIndex) {
        return tileEntities.get(flatIndex);
    }

    public boolean hasTiles() {
        return !tileEntities.isEmpty();
    }

    public int blockCount() {
        return sizeX * sizeY * sizeZ;
    }

    /** @return a rough estimate of this snapshot's heap footprint, for cache budgeting. */
    public long estimatedBytes() {
        long cached = estimatedBytes;
        if (cached < 0L) {
            cached = computeEstimatedBytes();
            estimatedBytes = cached;
        }
        return cached;
    }

    private long computeEstimatedBytes() {
        long bytes = 128L + ids.memoryBytes();
        for (final String s : uniqueBlockData) {
            bytes += 48L + s.length() * 2L + 64L; // string + resolved BlockData
        }
        for (final byte[] tile : tileEntities.values()) {
            bytes += 32L + tile.length;
        }
        for (final EntityPayload e : entities) {
            bytes += 96L + e.payload().length;
        }
        return bytes;
    }

    public record EntityPayload(@NotNull String type, double x, double y, double z,
                                float yaw, float pitch, byte @NotNull [] payload) {
    }

    public int flatIndex(final int rx, final int ry, final int rz) {
        return rx + sizeX * (rz + sizeZ * ry);
    }

    public int flatIndex(final int absoluteX, final int absoluteY, final int absoluteZ,
                         final int originX, final int originY, final int originZ) {
        final int rx = absoluteX - originX;
        final int ry = absoluteY - originY;
        final int rz = absoluteZ - originZ;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= sizeX || ry >= sizeY || rz >= sizeZ) {
            return -1;
        }
        return flatIndex(rx, ry, rz);
    }

    public @Nullable String blockDataString(final int flatIndex) {
        if (flatIndex < 0 || flatIndex >= ids.length()) {
            return null;
        }
        final int id = ids.get(flatIndex);
        return id < uniqueBlockData.length ? uniqueBlockData[id] : null;
    }

    /**
     * Parse palette strings once. Safe to call from any thread; subsequent
     * calls are no-ops.
     */
    public void resolvePalette() {
        if (palette != null) {
            return;
        }
        synchronized (this) {
            if (palette != null) {
                return;
            }
            final BlockData[] pal = new BlockData[uniqueBlockData.length];
            final boolean[] air = new boolean[uniqueBlockData.length];
            final BlockData airData = Bukkit.createBlockData(Material.AIR);
            for (int i = 0; i < uniqueBlockData.length; i++) {
                try {
                    pal[i] = Bukkit.createBlockData(uniqueBlockData[i]);
                    air[i] = pal[i].getMaterial().isAir();
                } catch (final IllegalArgumentException e) {
                    // Unknown block (removed in a newer version / datapack missing).
                    pal[i] = airData;
                    air[i] = true;
                }
            }
            this.airFlags = air;
            this.palette = pal;
        }
    }

    public @NotNull BlockData[] palette() {
        resolvePalette();
        return palette;
    }

    public boolean @NotNull [] airFlags() {
        resolvePalette();
        return airFlags;
    }

    public @Nullable BlockData blockData(final int flatIndex) {
        if (flatIndex < 0 || flatIndex >= ids.length()) {
            return null;
        }
        final BlockData[] pal = palette();
        final int id = ids.get(flatIndex);
        return id < pal.length ? pal[id] : null;
    }
}
