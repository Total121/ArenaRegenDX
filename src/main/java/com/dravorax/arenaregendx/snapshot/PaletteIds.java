/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.snapshot;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Compact per-voxel palette index storage.
 *
 * <p>Picks the narrowest element type that fits the palette: {@code byte} for
 * up to 256 entries (almost every arena), {@code short} for up to 65 536, and
 * {@code int} otherwise. Compared to a plain {@code int[]} this cuts snapshot
 * memory by 4x for typical arenas, which matters for cached snapshots of large
 * warzones.</p>
 *
 * <p>Exactly one backing array is non-null, so {@link #get(int)} is a single
 * well-predicted branch in the restore hot loop.</p>
 *
 * @author DravoraX
 */
public final class PaletteIds {

    private final byte[] bytes;
    private final short[] shorts;
    private final int[] ints;
    private final int length;

    private PaletteIds(final byte[] bytes, final short[] shorts, final int[] ints, final int length) {
        this.bytes = bytes;
        this.shorts = shorts;
        this.ints = ints;
        this.length = length;
    }

    /** Allocate zeroed storage able to hold indices {@code [0, paletteSize)}. */
    public static @NotNull PaletteIds allocate(final int length, final int paletteSize) {
        if (paletteSize <= 256) {
            return new PaletteIds(new byte[length], null, null, length);
        }
        if (paletteSize <= 65_536) {
            return new PaletteIds(null, new short[length], null, length);
        }
        return new PaletteIds(null, null, new int[length], length);
    }

    /** Copy a raw {@code int[]} into the narrowest storage for {@code paletteSize}. */
    public static @NotNull PaletteIds compact(final int @NotNull [] ids, final int paletteSize) {
        final PaletteIds out = allocate(ids.length, paletteSize);
        if (out.ints != null) {
            System.arraycopy(ids, 0, out.ints, 0, ids.length);
            return out;
        }
        for (int i = 0; i < ids.length; i++) {
            out.set(i, ids[i]);
        }
        return out;
    }

    public int get(final int index) {
        if (bytes != null) return bytes[index] & 0xFF;
        if (shorts != null) return shorts[index] & 0xFFFF;
        return ints[index];
    }

    public void set(final int index, final int value) {
        if (bytes != null) {
            bytes[index] = (byte) value;
        } else if (shorts != null) {
            shorts[index] = (short) value;
        } else {
            ints[index] = value;
        }
    }

    /** Fill {@code [from, to)} with {@code value}. */
    public void fill(final int from, final int to, final int value) {
        if (bytes != null) {
            Arrays.fill(bytes, from, to, (byte) value);
        } else if (shorts != null) {
            Arrays.fill(shorts, from, to, (short) value);
        } else {
            Arrays.fill(ints, from, to, value);
        }
    }

    public int length() {
        return length;
    }

    /** @return approximate heap footprint of the backing array in bytes. */
    public long memoryBytes() {
        if (bytes != null) return bytes.length;
        if (shorts != null) return shorts.length * 2L;
        return ints.length * 4L;
    }
}
