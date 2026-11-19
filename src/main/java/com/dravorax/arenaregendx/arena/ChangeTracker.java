/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.arena;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which voxels actually changed since a known-good baseline so restore
 * can skip the untouched 90%+ of a warzone.
 *
 * <h2>States per arena</h2>
 * <ul>
 *   <li><b>Unarmed</b> (no entry): the world may differ from the snapshot in ways
 *       we never saw (plugin just started, tracking was off, a restore was
 *       cancelled half-way). Marks are ignored and the next restore is full.</li>
 *   <li><b>Armed</b>: every change since the baseline is recorded. A baseline is
 *       established when a snapshot capture starts or a restore starts (the
 *       restore rewrites everything it was asked to, so only later marks
 *       matter).</li>
 *   <li><b>Overflow</b>: so many voxels changed that a full restore is cheaper.
 *       Recording stops (bounded memory) and the next restore is full.</li>
 * </ul>
 *
 * <p>Voxels are stored in a primitive open-addressing set (8 bytes/voxel instead
 * of ~60 for a boxed {@code Set<Long>}), and every set is hard-capped.</p>
 *
 * @author DravoraX
 */
public final class ChangeTracker {

    /** Absolute upper bound per arena regardless of arena size (~32 MB worst case). */
    private static final int HARD_CAP = 4_000_000;

    private final ConcurrentHashMap<String, LongSet> dirty = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile double fullThreshold = 0.35;
    private volatile ArenaLookup lookup;

    public void setEnabled(final boolean enabled) {
        if (!enabled) {
            // Anything changed while disabled is invisible to us: drop all baselines.
            dirty.clear();
        }
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setFullThreshold(final double fullThreshold) {
        this.fullThreshold = Math.max(0.05, Math.min(1.0, fullThreshold));
    }

    public void bind(@NotNull final ArenaLookup lookup) {
        this.lookup = lookup;
    }

    public void mark(@Nullable final Location loc) {
        if (!enabled || loc == null || loc.getWorld() == null) return;
        mark(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public void mark(@NotNull final String world, final int x, final int y, final int z) {
        if (!enabled || dirty.isEmpty()) return;
        final ArenaLookup l = lookup;
        final Arena arena = l == null ? null : l.find(world, x, y, z);
        if (arena == null) return;
        final LongSet set = dirty.get(arena.name());
        if (set == null || set.overflow) return; // unarmed or already full-restore
        if (arena.isExcluded(x, y, z)) return;
        final int cap = capFor(arena);
        if (!set.add(pack(x, y, z), cap)) {
            // Over the cap: drop the voxels and remember "full restore needed".
            set.overflow();
        }
    }

    /**
     * Start a fresh baseline for an arena (snapshot capture started). Any marks
     * from before are discarded.
     */
    public void arm(@NotNull final String arenaName) {
        if (!enabled) return;
        dirty.put(arenaName, new LongSet());
    }

    /**
     * Forget the baseline: the next restore will be a full one.
     * Use after a failed/cancelled restore or a failed snapshot.
     */
    public void invalidate(@NotNull final String arenaName) {
        dirty.remove(arenaName);
    }

    /** Remove all state for an arena (deleted / renamed). */
    public void clear(@NotNull final String arenaName) {
        dirty.remove(arenaName);
    }

    /**
     * Atomically take the recorded voxels for a restore and arm a new baseline,
     * so changes made while the restore runs are recorded for the next one.
     *
     * @param useDirty when false the caller wants a full restore regardless.
     * @return packed coordinates to restore, or {@code null} for a full restore.
     */
    public long @Nullable [] detachForRestore(@NotNull final Arena arena, final boolean useDirty) {
        if (!enabled) {
            dirty.remove(arena.name());
            return null;
        }
        final LongSet previous = dirty.put(arena.name(), new LongSet());
        if (!useDirty || previous == null) {
            return null;
        }
        final long[] values = previous.toArray();
        if (values == null || values.length == 0) {
            // Overflowed, or nothing recorded: be conservative and restore everything
            // (some world changes, e.g. plugin edits, never fire events).
            return null;
        }
        if (values.length >= Math.max(512, (int) (arena.blockCount() * fullThreshold))) {
            return null;
        }
        return values;
    }

    /** @return recorded voxel count, or -1 when overflowed / unarmed. */
    public int dirtyCount(@NotNull final String arenaName) {
        final LongSet set = dirty.get(arenaName);
        if (set == null) return -1;
        return set.size();
    }

    private int capFor(@NotNull final Arena arena) {
        final long threshold = Math.max(512L, (long) (arena.blockCount() * fullThreshold));
        return (int) Math.min(HARD_CAP, threshold);
    }

    // -----------------------------------------------------------------------
    // Packing
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

    /**
     * Sort packed voxels so every chunk's voxels are contiguous (restore walks
     * one chunk per region task). Done with a bit-rearranged primitive key so no
     * boxing or comparator is involved.
     */
    public static void sortByChunk(final long @NotNull [] packed) {
        for (int i = 0; i < packed.length; i++) {
            final long p = packed[i];
            final int x = unpackX(p);
            final int z = unpackZ(p);
            packed[i] = ((long) ((x >> 4) & 0x3FFFFF) << 42)
                    | ((long) ((z >> 4) & 0x3FFFFF) << 20)
                    | ((long) (x & 15) << 16)
                    | ((long) (z & 15) << 12)
                    | (p & 0xFFF);
        }
        Arrays.sort(packed);
        for (int i = 0; i < packed.length; i++) {
            final long k = packed[i];
            int x = (int) (((k >>> 42) & 0x3FFFFF) << 4 | ((k >>> 16) & 15));
            if ((x & 0x2000000) != 0) x |= ~0x3FFFFFF;
            int z = (int) (((k >>> 20) & 0x3FFFFF) << 4 | ((k >>> 12) & 15));
            if ((z & 0x2000000) != 0) z |= ~0x3FFFFFF;
            packed[i] = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (k & 0xFFF);
        }
    }

    @FunctionalInterface
    public interface ArenaLookup {
        @Nullable Arena find(@NotNull String world, int x, int y, int z);
    }

    // -----------------------------------------------------------------------
    // Primitive long set (open addressing, linear probing). Guarded by `this`.
    // -----------------------------------------------------------------------

    private static final class LongSet {
        /** 0 is a valid packed value, so a separate flag tracks it. */
        private long[] table = new long[64];
        private boolean hasZero;
        private int size;
        private volatile boolean overflow;

        synchronized boolean add(final long v, final int cap) {
            if (overflow) return true;
            if (v == 0L) {
                if (!hasZero) {
                    if (size + 1 > cap) return false;
                    hasZero = true;
                    size++;
                }
                return true;
            }
            int mask = table.length - 1;
            int i = mix(v) & mask;
            while (true) {
                final long cur = table[i];
                if (cur == 0L) break;
                if (cur == v) return true;
                i = (i + 1) & mask;
            }
            if (size + 1 > cap) return false;
            table[i] = v;
            size++;
            if (size * 2 > table.length) {
                rehash(table.length << 1);
            }
            return true;
        }

        synchronized void overflow() {
            overflow = true;
            table = new long[0];
            hasZero = false;
            size = 0;
        }

        synchronized int size() {
            return overflow ? -1 : size;
        }

        /** @return the values, or {@code null} if this set overflowed. */
        synchronized long @Nullable [] toArray() {
            if (overflow) return null;
            final long[] out = new long[size];
            int n = 0;
            if (hasZero) out[n++] = 0L;
            for (final long v : table) {
                if (v != 0L) out[n++] = v;
            }
            return out;
        }

        private void rehash(final int newCap) {
            final long[] old = table;
            final long[] nt = new long[newCap];
            final int mask = newCap - 1;
            for (final long v : old) {
                if (v == 0L) continue;
                int i = mix(v) & mask;
                while (nt[i] != 0L) i = (i + 1) & mask;
                nt[i] = v;
            }
            table = nt;
        }

        private static int mix(final long v) {
            long h = v * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
    }
}
