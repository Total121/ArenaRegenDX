/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.hooks.worldedit;

import com.dravorax.arenaregendx.arena.Selection;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Optional WorldEdit integration: use a player's WorldEdit selection when the
 * wand box is incomplete (or first, with {@code prefer-worldedit-selection}).
 *
 * <p>WorldEdit classes are only touched after {@link #enable()} confirmed the
 * plugin is present, so servers without WorldEdit never load them.</p>
 *
 * @author DravoraX
 */
public final class WorldEditHook {

    private static volatile boolean enabled;

    private WorldEditHook() {
    }

    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** @return the player's complete cuboid selection bounds, or null. */
    public static @Nullable Selection getSelection(@NotNull final Player player) {
        if (!enabled) return null;
        try {
            return Bridge.selection(player);
        } catch (final Throwable t) {
            return null; // no selection / incomplete / WorldEdit API mismatch
        }
    }

    /** Isolated so WorldEdit classes resolve only when the hook is enabled. */
    private static final class Bridge {
        static @Nullable Selection selection(@NotNull final Player player) throws Exception {
            final com.sk89q.worldedit.entity.Player actor = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player);
            final com.sk89q.worldedit.LocalSession session =
                    com.sk89q.worldedit.WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
            if (session == null) return null;
            final com.sk89q.worldedit.world.World weWorld = session.getSelectionWorld();
            if (weWorld == null) return null;
            final com.sk89q.worldedit.regions.Region region = session.getSelection(weWorld);
            final org.bukkit.World world = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(weWorld);
            final var min = region.getMinimumPoint();
            final var max = region.getMaximumPoint();
            final Selection sel = new Selection();
            sel.pos1 = new Location(world, min.x(), min.y(), min.z());
            sel.pos2 = new Location(world, max.x(), max.y(), max.z());
            return sel.isComplete() ? sel : null;
        }
    }
}
