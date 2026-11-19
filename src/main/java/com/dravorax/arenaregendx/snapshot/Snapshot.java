/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.snapshot;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * In-memory snapshot of a cuboid. Block types live in a palette so restore
 * never re-parses {@code BlockData} strings per voxel.
 *
 * @author DravoraX
 */
public final class Snapshot {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final long createdAt;

    private final String[] uniqueBlockData;
    private final int[] blockDataIds;
    private final @NotNull Map<Integer, byte[]> tileEntities;
    private final @NotNull EntityPayload[] entities;

    /** Resolved Bukkit palette. Built lazily, then reused for the whole restore. */
    private volatile BlockData[] palette;
    /** True when the matching palette entry is air (skip-air fast path). */
    private volatile boolean[] airFlags;

    public Snapshot(final int sizeX, final int sizeY, final int sizeZ,
                    final long createdAt,
                    @NotNull final String[] uniqueBlockData,
                    @NotNull final int[] blockDataIds,
                    @NotNull final Map<Integer, byte[]> tileEntities,
                    @NotNull final EntityPayload[] entities) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.createdAt = createdAt;
        this.uniqueBlockData = uniqueBlockData;
        this.blockDataIds = blockDataIds;
        this.tileEntities = tileEntities;
        this.entities = entities;
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public long createdAt() { return createdAt; }
    public @NotNull String[] uniqueBlockData() { return uniqueBlockData; }
    public @NotNull int[] blockDataIds() { return blockDataIds; }
    public @NotNull Map<Integer, byte[]> tileEntities() { return tileEntities; }
    public @NotNull EntityPayload[] entities() { return entities; }

    public int blockCount() {
        return sizeX * sizeY * sizeZ;
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
        if (flatIndex < 0 || flatIndex >= blockDataIds.length) {
            return null;
        }
        final int id = blockDataIds[flatIndex];
        if (id < 0 || id >= uniqueBlockData.length) {
            return null;
        }
        return uniqueBlockData[id];
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
            BlockData airData;
            try {
                airData = Bukkit.createBlockData(Material.AIR);
            } catch (final Throwable t) {
                airData = Bukkit.createBlockData("minecraft:air");
            }
            for (int i = 0; i < uniqueBlockData.length; i++) {
                try {
                    pal[i] = Bukkit.createBlockData(uniqueBlockData[i]);
                    air[i] = pal[i].getMaterial().isAir();
                } catch (final IllegalArgumentException e) {
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
        if (flatIndex < 0 || flatIndex >= blockDataIds.length) {
            return null;
        }
        final BlockData[] pal = palette();
        final int id = blockDataIds[flatIndex];
        if (id < 0 || id >= pal.length) {
            return null;
        }
        return pal[id];
    }
}
