/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.arena;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which voxels actually changed since the last snapshot so restore can
 * skip the untouched 90%+ of a warzone.
 *
 * <p>Empty dirty set means "unknown" (plugin just started, or tracking disabled)
 * and forces a full restore. After a successful save or regen the set is cleared
 * and subsequent match damage is recorded.</p>
 *
 * @author DravoraX
 */
public final class ChangeTracker {

    private final ConcurrentHashMap<String, Set<Long>> dirty = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile double fullThreshold = 0.35;

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setFullThreshold(final double fullThreshold) {
        this.fullThreshold = Math.max(0.05, Math.min(1.0, fullThreshold));
    }

    public void mark(@Nullable final Location loc) {
        if (!enabled || loc == null || loc.getWorld() == null) return;
        mark(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public void mark(@NotNull final String world, final int x, final int y, final int z) {
        if (!enabled) return;
        final Arena arena = findArena(world, x, y, z);
        if (arena == null || arena.isExcluded(x, y, z)) return;
        dirty.computeIfAbsent(arena.name(), k -> ConcurrentHashMap.newKeySet()).add(pack(x, y, z));
    }

    public void clear(@NotNull final String arenaName) {
        dirty.remove(arenaName);
    }

    /**
     * @return packed dirty coords, or {@code null} to force a full restore.
     */
    public @Nullable List<Long> snapshotForRestore(@NotNull final Arena arena) {
        if (!enabled) return null;
        final Set<Long> set = dirty.get(arena.name());
        if (set == null || set.isEmpty()) {
            return null;
        }
        final int n = set.size();
        if (n >= Math.max(512, (int) (arena.blockCount() * fullThreshold))) {
            return null;
        }
        return new ArrayList<>(set);
    }

    public int dirtyCount(@NotNull final String arenaName) {
        final Set<Long> set = dirty.get(arenaName);
        return set == null ? 0 : set.size();
    }

    public static long pack(final int x, final int y, final int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    public static int unpackX(final long p) {
        int x = (int) (p >>> 38);
        if ((x & 0x2000000) != 0) x |= ~0x3FFFFFF;
        return x;
    }

    public static int unpackZ(final long p) {
        int z = (int) ((p >>> 12) & 0x3FFFFFF);
        if ((z & 0x2000000) != 0) z |= ~0x3FFFFFF;
        return z;
    }

    public static int unpackY(final long p) {
        return ((int) (p & 0xFFF)) - 2048;
    }

    private volatile ArenaLookup lookup;

    public void bind(@NotNull final ArenaLookup lookup) {
        this.lookup = lookup;
    }

    private @Nullable Arena findArena(@NotNull final String world, final int x, final int y, final int z) {
        final ArenaLookup l = lookup;
        return l == null ? null : l.find(world, x, y, z);
    }

    @FunctionalInterface
    public interface ArenaLookup {
        @Nullable Arena find(@NotNull String world, int x, int y, int z);
    }
}
