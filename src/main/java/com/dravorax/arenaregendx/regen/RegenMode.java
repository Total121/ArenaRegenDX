/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.regen;

/**
 * Regeneration strategy — controls the order in which blocks are restored.
 *
 * @author DravoraX
 * @since 1.0.0
 */
public enum RegenMode {

    /**
     * Restore every block as fast as the TPS-adaptive budget allows, in flat-array
     * order. Fastest mode; no visual choreography.
     */
    INSTANT,

    /**
     * Restore blocks in an expanding-sphere pattern from the region centre, so the
     * rebuild looks like a "growth" animation. Visually pleasing for spectator cams.
     */
    ANIMATED,

    /**
     * Restore one chunk at a time, in row-major chunk order. Best when the arena
     * spans multiple regions on Folia (each chunk is restored on its own region
     * thread, parallelism is automatic).
     */
    CHUNK_BY_CHUNK,

    /**
     * Restore one Y-layer at a time, bottom to top. Good for layered structures
     * (floors, stages) and matches the "build-up" visual archetype.
     */
    LAYER_BY_LAYER
}
