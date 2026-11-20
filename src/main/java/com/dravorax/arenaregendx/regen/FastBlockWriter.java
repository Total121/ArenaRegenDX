/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.regen;

import com.dravorax.arenaregendx.nms.NmsAccess;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Places blocks with physics off.
 *
 * <p>When the NMS bridge is available, each voxel costs one {@code BlockPos}
 * allocation: the current state is read straight from the level and compared
 * by identity with the pre-resolved target state (NMS block states are
 * canonical singletons), so unchanged voxels are skipped without creating a
 * {@code CraftBlock} or {@code CraftBlockData}. Otherwise the Bukkit API is used.</p>
 *
 * @author DravoraX
 */
public final class FastBlockWriter {

    /** Voxel already matched the snapshot. */
    public static final int SKIPPED = 0;
    /** Voxel was written. */
    public static final int WRITTEN = 1;
    /** Write failed (logged by the caller if needed). */
    public static final int FAILED = 2;

    private static volatile boolean nmsAllowed = true;

    private FastBlockWriter() {
    }

    public static void setNmsAllowed(final boolean allowed) {
        nmsAllowed = allowed;
    }

    public static boolean nmsEnabled() {
        return nmsAllowed && NmsAccess.available();
    }

    /**
     * Resolve the NMS state for each palette entry.
     *
     * @return the NMS palette, or null if the fast path is unavailable.
     */
    public static Object @Nullable [] resolveNms(@NotNull final BlockData[] palette) {
        if (!nmsEnabled()) {
            return null;
        }
        final Object[] out = new Object[palette.length];
        for (int i = 0; i < palette.length; i++) {
            out[i] = NmsAccess.nmsState(palette[i]);
            if (out[i] == null) {
                return null;
            }
        }
        return out;
    }

    /** Open a writer for one batch. Not thread-safe: use one per thread. */
    public static @NotNull Writer writer(@NotNull final World world, @NotNull final BlockData[] palette,
                                         final boolean @NotNull [] air, final Object @Nullable [] nmsPalette) {
        final Object level = nmsPalette != null && nmsEnabled() ? NmsAccess.level(world) : null;
        return new Writer(world, palette, air, level == null ? null : nmsPalette, level);
    }

    /** Batch-scoped block writer. */
    public static final class Writer {
        private final World world;
        private final BlockData[] palette;
        private final boolean[] air;
        private final Object[] nmsPalette;
        private Object level;

        private Writer(final World world, final BlockData[] palette, final boolean[] air,
                       final Object[] nmsPalette, final Object level) {
            this.world = world;
            this.palette = palette;
            this.air = air;
            this.nmsPalette = nmsPalette;
            this.level = level;
        }

        /**
         * Restore one voxel.
         *
         * @param skipIfSame leave the voxel alone when it already matches (delta skip).
         * @return {@link #SKIPPED}, {@link #WRITTEN} or {@link #FAILED}.
         */
        public int apply(final int x, final int y, final int z, final int palId, final boolean skipIfSame) {
            final Object lvl = level;
            if (lvl != null) {
                try {
                    final Object pos = NmsAccess.blockPos(x, y, z);
                    final Object current = NmsAccess.stateAt(lvl, pos);
                    final Object target = nmsPalette[palId];
                    if (skipIfSame && (current == target || (air[palId] && NmsAccess.isAir(current)))) {
                        return SKIPPED;
                    }
                    NmsAccess.write(lvl, pos, current, target);
                    return WRITTEN;
                } catch (final Throwable t) {
                    // Unexpected NMS failure: stay on the Bukkit path for the rest of this batch.
                    level = null;
                }
            }
            final Block block = world.getBlockAt(x, y, z);
            final BlockData target = palette[palId];
            if (skipIfSame) {
                if (air[palId] && block.getType().isAir()) {
                    return SKIPPED;
                }
                if (block.getBlockData().equals(target)) {
                    return SKIPPED;
                }
            }
            try {
                block.setBlockData(target, false);
                return WRITTEN;
            } catch (final Throwable t) {
                return FAILED;
            }
        }
    }
}
