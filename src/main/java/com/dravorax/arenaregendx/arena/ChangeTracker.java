/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.arena;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which voxels changed since an arena was last known to match its
 * snapshot, so restore can skip the untouched 90%+ of a warzone.
 *
 * <h2>Trust model</h2>
 * <p>A dirty set is only meaningful if tracking has been running continuously
 * since the arena last matched its snapshot. An arena becomes <i>trusted</i>
 * when a snapshot capture or a full restore starts; it loses trust when a
 * restore fails or is cancelled, when its exclusions change, when another
 * snapshot is selected, when tracking is toggled off, or when its dirty set
 * overflows. Untrusted arenas always get a full restore. This fixes the old
 * behaviour where damage done before a server restart was silently skipped.</p>
 *
 * <h2>Memory</h2>
 * <p>Coordinates are packed into primitive {@code long}s inside a fastutil
 * {@link LongOpenHashSet} (about 4x smaller than boxed {@code Long}s in a
 * concurrent set). Each set is capped: once it exceeds the configured
 * full-restore threshold it is dropped and the arena is flagged for a full
 * restore, so a never-regenerated arena can no longer grow without bound.</p>
 *
 * @author DravoraX
 */
public final class ChangeTracker {

    /** Hard ceiling per arena regardless of arena size (≈ 8 MB of longs). */
    private static final int ABSOLUTE_CAP = 1_000_000;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile double fullThreshold = 0.35;
    private volatile ArenaLookup lookup;

    /** Per-arena tracking state. All access to {@link #set} is synchronized on the state. */
    private static final class State {
        final LongOpenHashSet set = new LongOpenHashSet();
        int cap;
        boolean trusted;
        boolean overflow;
    }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    public void bind(@NotNull final ArenaLookup lookup) {
        this.lookup = lookup;
    }

    public void setEnabled(final boolean enabled) {
        final boolean was = this.enabled;
        this.enabled = enabled;
        if (was != enabled) {
            // Changes made while tracking was off are unknown: drop every set.
            states.clear();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void setFullThreshold(final double fullThreshold) {
        this.fullThreshold = Math.max(0.05, Math.min(1.0, fullThreshold));
    }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    public void mark(@Nullable final Location loc) {
        if (!enabled || loc == null || loc.getWorld() == null) return;
        mark(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public void mark(@NotNull final String world, final int x, final int y, final int z) {
        if (!enabled) return;
        final ArenaLookup l = lookup;
        final Arena arena = l == null ? null : l.find(world, x, y, z);
        if (arena == null) return;
        final State state = states.get(key(arena.name()));
        if (state == null) return; // untrusted: nothing to record, full restore follows anyway
        synchronized (state) {
            if (!state.trusted || state.overflow) return;
            if (arena.isExcluded(x, y, z)) return;
            state.set.add(pack(x, y, z));
            if (state.set.size() > state.cap) {
                state.overflow = true;
                state.set.clear();
                state.set.trim();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle hooks
    // -----------------------------------------------------------------------

    /**
     * Called when the arena starts matching its snapshot from scratch (snapshot
     * capture or full restore starting). Clears the set and starts trusting it.
     */
    public void reset(@NotNull final Arena arena) {
        if (!enabled) return;
        final State state = new State();
        state.trusted = true;
        state.cap = capFor(arena);
        states.put(key(arena.name()), state);
    }

    /** Drop all knowledge about an arena (deleted, renamed, exclusions or snapshot changed). */
    public void forget(@NotNull final String arenaName) {
        states.remove(key(arenaName));
    }

    /** Back-compat alias for {@link #forget(String)}. */
    public void clear(@NotNull final String arenaName) {
        forget(arenaName);
    }

    /**
     * Decide what a restore should write and atomically hand the dirty set over to it.
     *
     * @param allowDirty whether the caller accepts a partial (dirty-only) restore.
     * @return the packed voxels to restore, or {@code null} for a full restore.
     */
    public long @Nullable [] beginRestore(@NotNull final Arena arena, final boolean allowDirty) {
        final String key = key(arena.name());
        final State state = states.get(key);
        if (allowDirty && enabled && state != null) {
            synchronized (state) {
                final int n = state.set.size();
                if (state.trusted && !state.overflow && n > 0 && n < state.cap) {
                    final long[] out = state.set.toLongArray();
                    state.set.clear();
                    if (n > 4096) {
                        state.set.trim();
                    }
                    return out;
                }
            }
        }
        // Full restore: the arena will match the snapshot once it finishes.
        reset(arena);
        return null;
    }

    /** Called when a restore ends. Failed or cancelled restores lose trust. */
    public void endRestore(@NotNull final String arenaName, final boolean success) {
        if (!success) {
            forget(arenaName);
        }
    }

    // -----------------------------------------------------------------------
    // Introspection
    // -----------------------------------------------------------------------

    public int dirtyCount(@NotNull final String arenaName) {
        final State state = states.get(key(arenaName));
        if (state == null) return 0;
        synchronized (state) {
            return state.set.size();
        }
    }

    /** @return true if the next auto restore of this arena can use the dirty set. */
    public boolean trusted(@NotNull final String arenaName) {
        final State state = states.get(key(arenaName));
        if (state == null) return false;
        synchronized (state) {
            return state.trusted && !state.overflow;
        }
    }

    private int capFor(@NotNull final Arena arena) {
        final long threshold = (long) (arena.blockCount() * fullThreshold);
        return (int) Math.min(ABSOLUTE_CAP, Math.max(512L, threshold));
    }

    private static @NotNull String key(@NotNull final String arenaName) {
        return arenaName.toLowerCase(Locale.ROOT);
    }

    // -----------------------------------------------------------------------
    // Packing (26 bits X, 26 bits Z, 12 bits Y offset by 2048)
    // -----------------------------------------------------------------------

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

    @FunctionalInterface
    public interface ArenaLookup {
        @Nullable Arena find(@NotNull String world, int x, int y, int z);
    }
}
