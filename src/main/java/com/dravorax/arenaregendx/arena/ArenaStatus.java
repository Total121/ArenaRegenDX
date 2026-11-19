/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.arena;

/**
 * Lifecycle status of an arena.
 *
 * @author DravoraX
 * @since 1.0.0
 */
public enum ArenaStatus {
    /** Arena has no snapshot yet — capture one with /ardx save. */
    EMPTY,
    /** Arena has a snapshot and is ready for matches. */
    READY,
    /** Arena is currently being regenerated. */
    REGENERATING,
    /** Arena snapshot is being captured. */
    SAVING,
    /** Arena is in an error state (see server logs). */
    ERROR
}
