/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.arena;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.particles.SelectionVisualizer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player cuboid selection state (pos1 / pos2) with auto-expiry.
 *
 * <p>Selections automatically expire after 20 seconds if the player takes too
 * long to create the arena. Particles are shown at both positions immediately
 * and remain until the arena is created or the timeout expires.</p>
 *
 * @author DravoraX
 * @since 1.0.1
 */
public final class ArenaSelectionManager implements Listener {

    /** Auto-expiry for incomplete selections. Complete boxes stay until used. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final ArenaRegenDXPlugin plugin;
    private final ConfigManager configManager;
    private final SelectionVisualizer visualizer;

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public ArenaSelectionManager(@NotNull final ArenaRegenDXPlugin plugin,
                                 @NotNull final ConfigManager configManager,
                                 @NotNull final SelectionVisualizer visualizer) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.visualizer = visualizer;
    }

    /**
     * Set position 1 for a player.
     */
    public void setPos1(@NotNull final Player player, @NotNull final Location location) {
        final Selection sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Selection());
        sel.pos1 = location.clone();
        sel.lastUpdate = System.currentTimeMillis();
        visualizer.showSelection(player, sel);
    }

    /**
     * Set position 2 for a player.
     */
    public void setPos2(@NotNull final Player player, @NotNull final Location location) {
        final Selection sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Selection());
        sel.pos2 = location.clone();
        sel.lastUpdate = System.currentTimeMillis();
        visualizer.showSelection(player, sel);
    }

    /**
     * @return the player's current selection (may be partial).
     */
    public @Nullable Selection getSelection(@NotNull final Player player) {
        final Selection sel = selections.get(player.getUniqueId());
        if (sel == null) return null;
        // Check for auto-expiry.
        if (!sel.isComplete() && System.currentTimeMillis() - sel.lastUpdate > TIMEOUT_MS) {
            clear(player);
            return null;
        }
        return sel;
    }

    /**
     * @return true if the player has both corners set (and not expired).
     */
    public boolean isComplete(@NotNull final Player player) {
        final Selection sel = getSelection(player);
        return sel != null && sel.isComplete();
    }

    /**
     * Clear a player's selection.
     */
    public void clear(@NotNull final Player player) {
        selections.remove(player.getUniqueId());
        visualizer.hideSelection(player);
    }

    /**
     * Clear all selections.
     */
    public void clearAll() {
        selections.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull final PlayerQuitEvent event) {
        clear(event.getPlayer());
    }
}
