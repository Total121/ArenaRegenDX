/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.arena;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Hot-path lookup for exclusion tests during capture/restore.
 *
 * <p>Zero allocations after construction. Empty arenas take the fast
 * {@code empty} singleton so the restore loop pays a single null-free branch.</p>
 *
 * @author DravoraX
 */
public final class ExclusionIndex {

    private static final ExclusionIndex EMPTY = new ExclusionIndex(new ExclusionZone[0]);

    private final ExclusionZone[] zones;
    private final int unionMinX, unionMinY, unionMinZ, unionMaxX, unionMaxY, unionMaxZ;

    private ExclusionIndex(@NotNull final ExclusionZone[] zones) {
        this.zones = zones;
        if (zones.length == 0) {
            this.unionMinX = this.unionMinY = this.unionMinZ = Integer.MAX_VALUE;
            this.unionMaxX = this.unionMaxY = this.unionMaxZ = Integer.MIN_VALUE;
            return;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final ExclusionZone z : zones) {
            if (z.minX() < minX) minX = z.minX();
            if (z.minY() < minY) minY = z.minY();
            if (z.minZ() < minZ) minZ = z.minZ();
            if (z.maxX() > maxX) maxX = z.maxX();
            if (z.maxY() > maxY) maxY = z.maxY();
            if (z.maxZ() > maxZ) maxZ = z.maxZ();
        }
        this.unionMinX = minX;
        this.unionMinY = minY;
        this.unionMinZ = minZ;
        this.unionMaxX = maxX;
        this.unionMaxY = maxY;
        this.unionMaxZ = maxZ;
    }

    public static @NotNull ExclusionIndex empty() {
        return EMPTY;
    }

    public static @NotNull ExclusionIndex of(@NotNull final List<ExclusionZone> zones) {
        if (zones.isEmpty()) {
            return EMPTY;
        }
        return new ExclusionIndex(zones.toArray(new ExclusionZone[0]));
    }

    public boolean isEmpty() {
        return zones.length == 0;
    }

    public int size() {
        return zones.length;
    }

    public @NotNull ExclusionZone[] zones() {
        return zones;
    }

    /**
     * @return true if (x,y,z) sits inside any exclusion and must not be restored.
     */
    public boolean contains(final int x, final int y, final int z) {
        if (zones.length == 0) {
            return false;
        }
        if (x < unionMinX || x > unionMaxX || y < unionMinY || y > unionMaxY || z < unionMinZ || z > unionMaxZ) {
            return false;
        }
        for (final ExclusionZone zone : zones) {
            if (zone.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
