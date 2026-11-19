/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.api.events;

import com.dravorax.arenaregendx.snapshot.SnapshotMetadata;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a snapshot has been captured and written to disk.
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class SnapshotSavedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SnapshotMetadata metadata;

    public SnapshotSavedEvent(@NotNull final SnapshotMetadata metadata) {
        this.metadata = metadata;
    }

    public @NotNull SnapshotMetadata metadata() { return metadata; }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
