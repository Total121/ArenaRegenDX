/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.api;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.regen.RegenMode;
import com.dravorax.arenaregendx.regen.RegenTask;
import com.dravorax.arenaregendx.snapshot.Snapshot;
import com.dravorax.arenaregendx.snapshot.SnapshotMetadata;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Public, stable API for ArenaRegenDX.
 *
 * <p>This facade is the only supported entry point for third-party plugins.
 * It delegates to the internal managers and exposes a minimal, well-documented
 * surface that will not break across minor versions.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // 1. Obtain the API.
 * ArenaRegenAPI api = ArenaRegenAPI.get();
 * if (api == null) {
 *     // ArenaRegenDX is not installed.
 *     return;
 * }
 *
 * // 2. Create an arena from two corners.
 * api.createArena("duel1", world, pos1, pos2).thenAccept(arena -> {
 *     // 3. Capture a snapshot.
 *     return api.saveSnapshot("duel1");
 * }).thenCompose(meta -> {
 *     // 4. Regenerate it later (e.g., after a match).
 *     return api.regen("duel1", RegenMode.LAYER_BY_LAYER, true);
 * });
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>All methods return {@link CompletableFuture}s and never block the caller.
 * Futures complete on the plugin's async pools; the {@code thenAccept} callback
 * runs on whichever thread last touched the future. Use
 * {@link CompletableFuture#thenAcceptAsync} with a known executor if you need
 * a specific thread.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ArenaRegenAPI {

    /**
     * @return the API instance, or {@code null} if the plugin is not enabled.
     */
    public static @Nullable ArenaRegenAPI get() {
        final ArenaRegenDXPlugin plugin = ArenaRegenDXPlugin.getInstance();
        return plugin == null ? null : new ArenaRegenAPI(plugin);
    }

    private final ArenaRegenDXPlugin plugin;

    private ArenaRegenAPI(@NotNull final ArenaRegenDXPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Arena lifecycle
    // -----------------------------------------------------------------------

    /**
     * Create a new arena from two corners.
     *
     * @param name   the arena name (will be sanitized to lowercase alnum + dash/underscore).
     * @param world  the world.
     * @param pos1   corner 1.
     * @param pos2   corner 2.
     * @return a future completed with the created arena.
     * @throws IllegalArgumentException if the name is taken.
     */
    public @NotNull CompletableFuture<Arena> createArena(@NotNull final String name,
                                                         @NotNull final World world,
                                                         @NotNull final Location pos1,
                                                         @NotNull final Location pos2) {
        return CompletableFuture.supplyAsync(() ->
                arenaManager().createArena(name, world, pos1, pos2, 0, true));
    }

    /**
     * Delete an arena and all its snapshots.
     *
     * @param name the arena name.
     * @return a future completed with true if the arena existed.
     */
    public @NotNull CompletableFuture<Boolean> deleteArena(@NotNull final String name) {
        return CompletableFuture.supplyAsync(() -> arenaManager().deleteArena(name));
    }

    /**
     * @return all registered arenas (snapshot copy).
     */
    public @NotNull Collection<Arena> arenas() {
        return arenaManager().all();
    }

    /**
     * @param name the arena name.
     * @return the arena, or null.
     */
    public @Nullable Arena arena(@NotNull final String name) {
        return arenaManager().getArena(name);
    }

    // -----------------------------------------------------------------------
    // Snapshots
    // -----------------------------------------------------------------------

    /**
     * Save a fresh snapshot of an arena.
     *
     * @param arenaName the arena name.
     * @return a future completed with the snapshot metadata.
     */
    public @NotNull CompletableFuture<SnapshotMetadata> saveSnapshot(@NotNull final String arenaName) {
        final Arena arena = arenaManager().getArena(arenaName);
        if (arena == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Arena not found: " + arenaName));
        }
        // Same path as /ardx save: marks SAVING, records the new restore point,
        // persists and prunes. (Previously the API wrote a file the arena never used.)
        return arenaManager().captureSnapshot(arena);
    }

    /**
     * Load a snapshot into memory (cached).
     *
     * @param arenaName  the arena name.
     * @param snapshotId the snapshot id (use the arena's latestSnapshotId() for the most recent).
     * @return a future completed with the snapshot.
     */
    public @NotNull CompletableFuture<Snapshot> loadSnapshot(@NotNull final String arenaName, final long snapshotId) {
        return snapshotManager().loadSnapshot(arenaName, snapshotId);
    }

    // -----------------------------------------------------------------------
    // Regeneration
    // -----------------------------------------------------------------------

    /**
     * Regenerate an arena from its latest snapshot.
     *
     * @param arenaName the arena name.
     * @param mode      the regeneration mode.
     * @param safeMode  teleport entities out of voxels before placing solid blocks.
     * @return a future completed with the regen task when regeneration finishes.
     */
    public @NotNull CompletableFuture<RegenTask> regen(@NotNull final String arenaName,
                                                       @NotNull final RegenMode mode,
                                                       final boolean safeMode) {
        return regenManager().regen(arenaName, mode, safeMode);
    }

    /**
     * @return true if a regeneration is currently running.
     */
    public boolean isRegenerating() {
        return regenManager().isRegenerating();
    }

    /**
     * @return the number of pending regen jobs in the queue.
     */
    public int queueSize() {
        return regenManager().queueSize();
    }

    /**
     * Add or replace a cuboid exclusion zone (protected spawn, etc.).
     */
    public boolean addExclusion(@NotNull final String arenaName,
                                @NotNull final com.dravorax.arenaregendx.arena.ExclusionZone zone) {
        final Arena arena = arenaManager().getArena(arenaName);
        if (arena == null) {
            return false;
        }
        arena.addExclusion(zone);
        arenaManager().saveArena(arena);
        return true;
    }

    /**
     * Remove a named exclusion zone.
     */
    public boolean removeExclusion(@NotNull final String arenaName, @NotNull final String zoneName) {
        final Arena arena = arenaManager().getArena(arenaName);
        if (arena == null) {
            return false;
        }
        final boolean removed = arena.removeExclusion(zoneName);
        if (removed) {
            arenaManager().saveArena(arena);
        }
        return removed;
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    /**
     * @return the current TPS (1-minute average), clamped to [0, 20].
     */
    public double currentTPS() {
        return plugin.tpsMonitor().currentTPS();
    }

    /**
     * @return the adaptive block budget for this tick.
     */
    public int adaptiveBlockBudget() {
        return plugin.tpsMonitor().adaptiveBlockBudget();
    }

    // -----------------------------------------------------------------------
    // Internal accessor shortcuts
    // -----------------------------------------------------------------------

    private @NotNull ArenaManager arenaManager() { return plugin.arenaManager(); }
    private @NotNull SnapshotManager snapshotManager() { return plugin.snapshotManager(); }
    private @NotNull RegenManager regenManager() { return plugin.regenManager(); }
}
