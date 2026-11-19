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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Places blocks with physics off. Prefers NMS {@code Level.setBlock} (no neighbour
 * updates, no drops) and caches the NMS BlockState per Bukkit palette entry.
 *
 * <p>The hot restore loop resolves the NMS level and the NMS palette once per
 * task (see {@link #resolveNms(BlockData)} / {@link NmsAccess#levelHandle(World)})
 * so a voxel write is a single method-handle call.</p>
 *
 * @author DravoraX
 */
public final class FastBlockWriter {

    /** Sentinel for "NMS state unavailable for this BlockData". */
    public static final Object NO_NMS = new Object();

    /** Bounded by the number of distinct block states in the game. */
    private static final ConcurrentHashMap<BlockData, Object> NMS_CACHE = new ConcurrentHashMap<>(256);
    private static volatile boolean nmsAllowed = true;

    private FastBlockWriter() {
    }

    public static void setNmsAllowed(final boolean allowed) {
        nmsAllowed = allowed;
    }

    public static boolean nmsEnabled() {
        return nmsAllowed && NmsAccess.available();
    }

    /** Drop cached NMS states (plugin disable). */
    public static void clearCache() {
        NMS_CACHE.clear();
    }

    /**
     * @return the cached NMS block state for {@code data}, or {@link #NO_NMS}.
     */
    public static @NotNull Object resolveNms(@NotNull final BlockData data) {
        return NMS_CACHE.computeIfAbsent(data, d -> {
            final Object s = NmsAccess.nmsState(d);
            return s == null ? NO_NMS : s;
        });
    }

    public static boolean set(@NotNull final Block block, @NotNull final BlockData data) {
        return set(block.getWorld(), block.getX(), block.getY(), block.getZ(), block, data);
    }

    public static boolean set(@NotNull final World world, final int x, final int y, final int z,
                              @NotNull final Block block, @NotNull final BlockData data) {
        if (nmsEnabled()) {
            final Object nms = resolveNms(data);
            if (nms != NO_NMS && NmsAccess.setBlock(world, x, y, z, nms)) {
                return true;
            }
        }
        return bukkitSet(block, data);
    }

    /**
     * Hot-path write with a pre-resolved level and NMS state.
     *
     * @param level    the NMS level from {@link NmsAccess#levelHandle(World)} or {@code null}.
     * @param nmsState the NMS state from {@link #resolveNms(BlockData)} or {@code null}/{@link #NO_NMS}.
     */
    public static boolean set(@Nullable final Object level, @Nullable final Object nmsState,
                              final int x, final int y, final int z,
                              @NotNull final Block block, @NotNull final BlockData data) {
        if (level != null && nmsState != null && nmsState != NO_NMS && nmsAllowed
                && NmsAccess.setBlock(level, x, y, z, nmsState)) {
            return true;
        }
        return bukkitSet(block, data);
    }

    private static boolean bukkitSet(@NotNull final Block block, @NotNull final BlockData data) {
        try {
            block.setBlockData(data, false);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }
}
