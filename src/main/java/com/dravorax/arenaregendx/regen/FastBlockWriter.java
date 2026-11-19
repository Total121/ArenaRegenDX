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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Places blocks with physics off. Prefers NMS {@code Level.setBlock} (no neighbour
 * updates, no drops) and caches the NMS BlockState per Bukkit palette entry.
 *
 * @author DravoraX
 */
public final class FastBlockWriter {

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

    public static boolean set(@NotNull final Block block, @NotNull final BlockData data) {
        return set(block.getWorld(), block.getX(), block.getY(), block.getZ(), block, data);
    }

    public static boolean set(@NotNull final World world, final int x, final int y, final int z,
                              @NotNull final Block block, @NotNull final BlockData data) {
        if (nmsEnabled()) {
            final Object nms = NMS_CACHE.computeIfAbsent(data, d -> {
                final Object s = NmsAccess.nmsState(d);
                return s == null ? Boolean.FALSE : s;
            });
            if (nms != Boolean.FALSE && NmsAccess.setBlock(world, x, y, z, nms)) {
                return true;
            }
        }
        try {
            block.setBlockData(data, false);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }
}
