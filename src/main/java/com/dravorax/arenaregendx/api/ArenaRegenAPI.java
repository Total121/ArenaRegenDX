/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2026 DravoraX. All rights reserved.
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
 * // 2. Create an arena from two corners, then capture its snapshot.
 * api.createArena("duel1", world, pos1, pos2)
 *    .thenCompose(arena -> api.saveSnapshot("duel1"))
 *    .thenAccept(meta -> getLogger().info("duel1 saved as #" + meta.snapshotId()));
 *
 * // 3. After a match, restore it.
 * api.regen("duel1", RegenMode.INSTANT, true)
 *    .thenAccept(task -> getLogger().info(task.blocksApplied() + " blocks restored"));
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>Methods never block the caller. Futures complete on plugin worker or
 * region threads; schedule your own follow-up work onto the thread you need
 * (for example with the Bukkit / Folia schedulers) before touching the world.</p>
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
        try {
            return CompletableFuture.completedFuture(arenaManager().createArena(name, world, pos1, pos2, 0, true));
        } catch (final RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Delete an arena and all its snapshots.
     *
     * @param name the arena name.
     * @return a future completed with true if the arena existed.
     */
    public @NotNull CompletableFuture<Boolean> deleteArena(@NotNull final String name) {
        return CompletableFuture.completedFuture(arenaManager().deleteArena(name));
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
     * Capture a fresh snapshot of an arena and make it the restore point.
     *
     * @param arenaName the arena name.
     * @return a future completed with the snapshot metadata once it is on disk.
     */
    public @NotNull CompletableFuture<SnapshotMetadata> saveSnapshot(@NotNull final String arenaName) {
        final Arena arena = arenaManager().getArena(arenaName);
        if (arena == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Arena not found: " + arenaName));
        }
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
     * Restore only the voxels changed since the last restore when dirty tracking
     * trusts its record, otherwise the full snapshot (what auto-reset uses).
     */
    public @NotNull CompletableFuture<RegenTask> regenChanged(@NotNull final String arenaName,
                                                              @NotNull final RegenMode mode,
                                                              final boolean safeMode) {
        return regenManager().regen(arenaName, mode, safeMode, true);
    }

    /** Cancel a queued or running restore. @return true if something was cancelled. */
    public boolean cancelRegen(@NotNull final String arenaName) {
        return regenManager().cancel(arenaName);
    }

    /**
     * @return true if a regeneration is currently running.
     */
    public boolean isRegenerating() {
        return regenManager().isRegenerating();
    }

    /** @return true if this arena is being restored or queued. */
    public boolean isRegenerating(@NotNull final String arenaName) {
        return regenManager().isRegenerating(arenaName);
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
        arenaManager().onExclusionsChanged(arena);
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
            arenaManager().onExclusionsChanged(arena);
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
