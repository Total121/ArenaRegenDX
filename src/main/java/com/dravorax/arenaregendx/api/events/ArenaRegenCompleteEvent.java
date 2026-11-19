/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.api.events;

import com.dravorax.arenaregendx.arena.Arena;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when an arena regeneration completes successfully.
 *
 * <p>Listeners can use this to broadcast a message, restart a match, award
 * participants, etc. The event fires on the region thread that completed the
 * final batch (Folia) or the main thread (Paper). For thread-sensitive work,
 * schedule your handler appropriately.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ArenaRegenCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Arena arena;
    private final long blocksRestored;
    private final long durationMillis;

    public ArenaRegenCompleteEvent(@NotNull final Arena arena, final long blocksRestored, final long durationMillis) {
        this.arena = arena;
        this.blocksRestored = blocksRestored;
        this.durationMillis = durationMillis;
    }

    public @NotNull Arena arena() { return arena; }
    public long blocksRestored() { return blocksRestored; }
    public long durationMillis() { return durationMillis; }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
