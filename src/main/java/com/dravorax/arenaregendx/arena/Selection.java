/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.arena;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/**
 * Mutable per-player selection state: two opposite corners of a cuboid.
 *
 * <p>Both corners are stored as world-absolute {@link Location}s. The selection
 * is "complete" when both are non-null. Instances are owned by
 * {@link ArenaSelectionManager} and accessed only from the main thread (Paper) or
 * the global region (Folia) — no synchronization needed.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class Selection {
    @Nullable public Location pos1;
    @Nullable public Location pos2;
    /** Timestamp of the last corner set (for auto-expiry). */
    public volatile long lastUpdate;

    /**
     * @return true if both corners are set and in the same world.
     */
    public boolean isComplete() {
        return pos1 != null && pos2 != null
                && pos1.getWorld() != null
                && pos1.getWorld().equals(pos2.getWorld());
    }

    /**
     * @return the minimum corner, or null if incomplete.
     */
    public @Nullable Location min() {
        if (!isComplete()) return null;
        return new Location(pos1.getWorld(),
                Math.min(pos1.getBlockX(), pos2.getBlockX()),
                Math.min(pos1.getBlockY(), pos2.getBlockY()),
                Math.min(pos1.getBlockZ(), pos2.getBlockZ()));
    }

    /**
     * @return the maximum corner, or null if incomplete.
     */
    public @Nullable Location max() {
        if (!isComplete()) return null;
        return new Location(pos1.getWorld(),
                Math.max(pos1.getBlockX(), pos2.getBlockX()),
                Math.max(pos1.getBlockY(), pos2.getBlockY()),
                Math.max(pos1.getBlockZ(), pos2.getBlockZ()));
    }
}
