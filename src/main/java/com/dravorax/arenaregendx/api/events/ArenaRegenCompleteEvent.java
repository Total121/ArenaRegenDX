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
 * participants, etc. The event fires on the main thread (Paper) or the global
 * region thread (Folia); on Folia, hop to an entity / region scheduler before
 * touching players or blocks.</p>
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
