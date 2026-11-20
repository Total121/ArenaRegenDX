/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.arena;

import com.dravorax.arenaregendx.hooks.worldedit.WorldEditHook;
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
 * Per-player wand selections (pos1 / pos2).
 *
 * <p>Incomplete selections expire after 30 minutes of inactivity; complete
 * selections are kept until used, cleared, or the player leaves.</p>
 *
 * @author DravoraX
 * @since 1.0.1
 */
public final class ArenaSelectionManager implements Listener {

    /** Auto-expiry for incomplete selections. */
    private static final long TIMEOUT_MS = 30L * 60L * 1000L;

    private final SelectionVisualizer visualizer;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public ArenaSelectionManager(@NotNull final SelectionVisualizer visualizer) {
        this.visualizer = visualizer;
    }

    public void setPos1(@NotNull final Player player, @NotNull final Location location) {
        final Selection sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Selection());
        sel.pos1 = location.toBlockLocation();
        sel.lastUpdate = System.currentTimeMillis();
        visualizer.showSelection(player, sel);
    }

    public void setPos2(@NotNull final Player player, @NotNull final Location location) {
        final Selection sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Selection());
        sel.pos2 = location.toBlockLocation();
        sel.lastUpdate = System.currentTimeMillis();
        visualizer.showSelection(player, sel);
    }

    /** @return the player's wand selection (may be partial), or null. */
    public @Nullable Selection getSelection(@NotNull final Player player) {
        final Selection sel = selections.get(player.getUniqueId());
        if (sel == null) return null;
        if (!sel.isComplete() && System.currentTimeMillis() - sel.lastUpdate > TIMEOUT_MS) {
            clear(player);
            return null;
        }
        return sel;
    }

    /**
     * Resolve the selection a command should use: the wand box and the WorldEdit
     * selection, in the order given by {@code prefer-worldedit-selection}.
     *
     * @return a complete selection, or null.
     */
    public @Nullable Selection resolve(@NotNull final Player player, final boolean preferWorldEdit) {
        final Selection wand = getSelection(player);
        final Selection wandComplete = wand != null && wand.isComplete() ? wand : null;
        if (preferWorldEdit) {
            final Selection we = WorldEditHook.getSelection(player);
            return we != null ? we : wandComplete;
        }
        return wandComplete != null ? wandComplete : WorldEditHook.getSelection(player);
    }

    public boolean isComplete(@NotNull final Player player) {
        final Selection sel = getSelection(player);
        return sel != null && sel.isComplete();
    }

    public void clear(@NotNull final Player player) {
        selections.remove(player.getUniqueId());
        visualizer.hideSelection(player);
    }

    public void clearAll() {
        selections.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull final PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
        visualizer.forget(event.getPlayer().getUniqueId());
    }
}
