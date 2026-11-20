/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.arena;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.regen.RegenSpeed;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import com.dravorax.arenaregendx.snapshot.SnapshotMetadata;
import com.dravorax.arenaregendx.snapshot.SnapshotSerializer;
import com.dravorax.arenaregendx.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * CRUD manager for {@link Arena} instances.
 *
 * <p>The in-memory map is the source of truth. {@code arenas.yml} is read once
 * on enable and rewritten as a whole from memory whenever something changes.
 * Writes are coalesced (many changes in one tick produce one write), run off
 * the server thread, and are atomic (temp file + move).</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ArenaManager {

    private static final List<String> FILE_HEADER = List.of(
            "ArenaRegenDX - arena metadata.",
            "This file is written by the plugin. Edit arenas with /ardx commands;",
            "manual edits made while the server runs are overwritten.");

    private final ArenaRegenDXPlugin plugin;
    private final ConfigManager configManager;
    private final SnapshotManager snapshotManager;
    private final SchedulerAdapter scheduler;

    /** Keyed by sanitized (lowercase) arena name. */
    private final ConcurrentMap<String, Arena> arenas = new ConcurrentHashMap<>();
    /** Per-world arrays for the hot {@link #findContaining} lookup (block events). */
    private volatile Map<String, Arena[]> byWorld = Map.of();

    private final Object ioLock = new Object();
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    public ArenaManager(@NotNull final ArenaRegenDXPlugin plugin,
                        @NotNull final ConfigManager configManager,
                        @NotNull final SnapshotManager snapshotManager,
                        @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.snapshotManager = snapshotManager;
        this.scheduler = scheduler;
    }

    // -----------------------------------------------------------------------
    // Loading / persistence
    // -----------------------------------------------------------------------

    /**
     * Load all arenas from {@code arenas.yml}. Runs synchronously during enable
     * (the YAML is already parsed), so commands, placeholders and listeners
     * never observe a half-loaded arena list.
     */
    public void load() {
        final FileConfiguration cfg = configManager.config("arenas.yml");
        final ConfigurationSection root = cfg == null ? null : cfg.getConfigurationSection("arenas");
        if (root == null) {
            plugin.getSLF4JLogger().info("No arenas defined yet.");
            return;
        }
        int count = 0;
        for (final String rawName : root.getKeys(false)) {
            final ConfigurationSection a = root.getConfigurationSection(rawName);
            if (a == null) continue;
            try {
                final String name = ColorUtil.sanitizeName(rawName);
                if (!ColorUtil.isValidName(name)) {
                    plugin.getSLF4JLogger().warn("Skipping arena '{}': invalid name.", rawName);
                    continue;
                }
                ArenaStatus status;
                try {
                    status = ArenaStatus.valueOf(a.getString("status", "EMPTY").toUpperCase(Locale.ROOT));
                } catch (final IllegalArgumentException ex) {
                    status = ArenaStatus.EMPTY;
                }
                final long latest = a.getLong("latest-snapshot", 0);
                // In-flight states cannot survive a restart.
                if (status == ArenaStatus.REGENERATING || status == ArenaStatus.SAVING) {
                    status = latest > 0 ? ArenaStatus.READY : ArenaStatus.EMPTY;
                }
                final Arena arena = new Arena(
                        name,
                        a.getString("world", ""),
                        a.getInt("origin.x"), a.getInt("origin.y"), a.getInt("origin.z"),
                        Math.max(1, a.getInt("size.x", 1)), Math.max(1, a.getInt("size.y", 1)),
                        Math.max(1, a.getInt("size.z", 1)),
                        a.getLong("created-at", System.currentTimeMillis()),
                        latest,
                        a.getLong("last-regenerated", 0),
                        a.getLong("snapshot-count", 0),
                        status,
                        Math.max(0, a.getInt("auto-reset", 0)),
                        a.getBoolean("async-regen", true),
                        a.getBoolean("spawn.set", false),
                        a.getDouble("spawn.x", 0), a.getDouble("spawn.y", 64), a.getDouble("spawn.z", 0),
                        (float) a.getDouble("spawn.yaw", 0), (float) a.getDouble("spawn.pitch", 0)
                );
                loadExclusions(arena, a);
                arena.setRegenSpeedPercent(a.getInt("regen-speed-percent", RegenSpeed.INHERIT));
                if (arenas.putIfAbsent(name, arena) != null) {
                    plugin.getSLF4JLogger().warn("Duplicate arena '{}' in arenas.yml ignored.", rawName);
                    continue;
                }
                count++;
            } catch (final Exception e) {
                plugin.getSLF4JLogger().warn("Skipping malformed arena entry '{}'", rawName, e);
            }
        }
        rebuildIndex();
        plugin.getSLF4JLogger().info("Loaded {} arena(s).", count);
    }

    /** Persist one arena's change. Coalesced: the whole file is rewritten at most once per burst. */
    public void saveArena(@NotNull final Arena arena) {
        requestFlush();
    }

    /** Schedule an async rewrite of arenas.yml unless one is already queued. */
    public void requestFlush() {
        if (flushQueued.compareAndSet(false, true)) {
            try {
                scheduler.runAsync(() -> {
                    flushQueued.set(false);
                    flushNow();
                });
            } catch (final Throwable t) {
                // Plugin disabling: schedulers refuse new tasks, so write now.
                flushQueued.set(false);
                flushNow();
            }
        }
    }

    /** Synchronously rewrite arenas.yml from memory. */
    public void flushNow() {
        synchronized (ioLock) {
            final YamlConfiguration out = new YamlConfiguration();
            out.options().setHeader(FILE_HEADER);
            final List<Arena> sorted = new ArrayList<>(arenas.values());
            sorted.sort((a, b) -> a.name().compareTo(b.name()));
            out.createSection("arenas");
            for (final Arena arena : sorted) {
                writeArenaFields(out, arena);
            }
            configManager.writeAtomically("arenas.yml", out.saveToString());
        }
    }

    private static void writeArenaFields(@NotNull final FileConfiguration cfg, @NotNull final Arena arena) {
        final String base = "arenas." + arena.name();
        final ArenaStatus status = arena.status() == ArenaStatus.REGENERATING || arena.status() == ArenaStatus.SAVING
                ? (arena.latestSnapshotId() > 0 ? ArenaStatus.READY : ArenaStatus.EMPTY)
                : arena.status();
        cfg.set(base + ".world", arena.worldName());
        cfg.set(base + ".origin.x", arena.originX());
        cfg.set(base + ".origin.y", arena.originY());
        cfg.set(base + ".origin.z", arena.originZ());
        cfg.set(base + ".size.x", arena.sizeX());
        cfg.set(base + ".size.y", arena.sizeY());
        cfg.set(base + ".size.z", arena.sizeZ());
        cfg.set(base + ".created-at", arena.createdAt());
        cfg.set(base + ".latest-snapshot", arena.latestSnapshotId());
        cfg.set(base + ".last-regenerated", arena.lastRegeneratedAt());
        cfg.set(base + ".snapshot-count", arena.snapshotCount());
        cfg.set(base + ".status", status.name());
        cfg.set(base + ".auto-reset", arena.autoResetInterval());
        cfg.set(base + ".async-regen", arena.asyncRegen());
        cfg.set(base + ".regen-speed-percent", arena.regenSpeedPercent());
        cfg.set(base + ".spawn.set", arena.hasSpawn());
        cfg.set(base + ".spawn.x", arena.spawnX());
        cfg.set(base + ".spawn.y", arena.spawnY());
        cfg.set(base + ".spawn.z", arena.spawnZ());
        cfg.set(base + ".spawn.yaw", (double) arena.spawnYaw());
        cfg.set(base + ".spawn.pitch", (double) arena.spawnPitch());
        for (final ExclusionZone zone : arena.exclusions()) {
            zone.writeTo(cfg.createSection(base + ".exclusions." + zone.name()));
        }
    }

    private static void loadExclusions(@NotNull final Arena arena, @NotNull final ConfigurationSection section) {
        final ConfigurationSection root = section.getConfigurationSection("exclusions");
        if (root == null) {
            return;
        }
        for (final String key : root.getKeys(false)) {
            final ConfigurationSection z = root.getConfigurationSection(key);
            if (z == null) continue;
            final ExclusionZone zone = ExclusionZone.readFrom(key, z);
            if (zone != null) {
                arena.addExclusion(zone);
            }
        }
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    /**
     * Create a new arena from two corners (no snapshot yet; see {@link #captureSnapshot}).
     *
     * @throws IllegalArgumentException if the name is invalid / taken or the region is invalid.
     */
    public @NotNull Arena createArena(@NotNull final String name, @NotNull final World world,
                                      @NotNull final Location pos1, @NotNull final Location pos2,
                                      final int autoResetInterval, final boolean asyncRegen) {
        final String sanitized = ColorUtil.sanitizeName(name);
        if (!ColorUtil.isValidName(sanitized)) {
            throw new IllegalArgumentException("Invalid arena name '" + name
                    + "'. Use 1-" + ColorUtil.MAX_NAME_LENGTH + " characters: a-z, 0-9, - and _.");
        }
        final int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        final int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        final int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        final int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
        final int sizeX = Math.abs(pos1.getBlockX() - pos2.getBlockX()) + 1;
        final int sizeY = maxY - minY + 1;
        final int sizeZ = Math.abs(pos1.getBlockZ() - pos2.getBlockZ()) + 1;
        if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) {
            throw new IllegalArgumentException("Selection Y " + minY + ".." + maxY + " is outside the world height ("
                    + world.getMinHeight() + ".." + (world.getMaxHeight() - 1) + ").");
        }
        final long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > SnapshotSerializer.MAX_VOLUME) {
            throw new IllegalArgumentException("Selection is " + volume + " blocks; the maximum is "
                    + SnapshotSerializer.MAX_VOLUME + ".");
        }
        final Arena arena = new Arena(sanitized, world.getName(), minX, minY, minZ,
                sizeX, sizeY, sizeZ, System.currentTimeMillis(),
                0L, 0L, 0L, ArenaStatus.EMPTY,
                Math.max(0, autoResetInterval), asyncRegen,
                false, 0, 64, 0, 0, 0);
        if (arenas.putIfAbsent(sanitized, arena) != null) {
            throw new IllegalArgumentException("Arena already exists: " + sanitized);
        }
        rebuildIndex();
        requestFlush();
        return arena;
    }

    /**
     * Capture a fresh snapshot of an arena and make it the active restore point.
     * Single implementation shared by commands, GUI and the public API.
     *
     * @return a future completed with the metadata once the snapshot is on disk.
     */
    public @NotNull CompletableFuture<SnapshotMetadata> captureSnapshot(@NotNull final Arena arena) {
        final World world = Bukkit.getWorld(arena.worldName());
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("World not loaded: " + arena.worldName()));
        }
        if (plugin.regenManager().isRegenerating(arena.name())) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Arena " + arena.name() + " is regenerating; cancel it or wait."));
        }
        if (!arena.beginSaving()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Arena " + arena.name() + " is busy (" + arena.status().name().toLowerCase(Locale.ROOT) + ")."));
        }
        final Location min = new Location(world, arena.originX(), arena.originY(), arena.originZ());
        final Location max = new Location(world,
                arena.originX() + arena.sizeX() - 1,
                arena.originY() + arena.sizeY() - 1,
                arena.originZ() + arena.sizeZ() - 1);
        // Changes after this point are tracked relative to the new snapshot.
        plugin.changeTracker().reset(arena);
        final CompletableFuture<SnapshotMetadata> future;
        try {
            future = snapshotManager.saveSnapshot(arena.name(), world, min, max);
        } catch (final Throwable t) {
            arena.endSaving(false);
            plugin.changeTracker().forget(arena.name());
            return CompletableFuture.failedFuture(t);
        }
        return future.whenComplete((meta, err) -> {
            if (arenas.get(arena.name()) != arena) {
                return; // deleted or renamed while saving
            }
            if (err != null) {
                plugin.changeTracker().forget(arena.name());
                arena.endSaving(false);
            } else {
                arena.onSnapshotSaved(meta.snapshotId());
                arena.endSaving(true);
                snapshotManager.pruneOldSnapshots(arena.name(), meta.snapshotId());
            }
            requestFlush();
        });
    }

    /**
     * Make an existing snapshot the one future restores use (for {@code /ardx load}).
     */
    public @NotNull CompletableFuture<Void> selectSnapshot(@NotNull final Arena arena, final long snapshotId) {
        return snapshotManager.loadSnapshot(arena.name(), snapshotId).thenAccept(snapshot -> {
            if (snapshot.sizeX() != arena.sizeX() || snapshot.sizeY() != arena.sizeY()
                    || snapshot.sizeZ() != arena.sizeZ()) {
                throw new IllegalStateException("Snapshot #" + snapshotId + " is "
                        + snapshot.sizeX() + "x" + snapshot.sizeY() + "x" + snapshot.sizeZ()
                        + " but the arena is " + arena.sizeX() + "x" + arena.sizeY() + "x" + arena.sizeZ() + ".");
            }
            if (arena.latestSnapshotId() != snapshotId) {
                arena.selectSnapshot(snapshotId);
                // Dirty voxels were relative to the previous snapshot.
                plugin.changeTracker().forget(arena.name());
                requestFlush();
            }
        });
    }

    /**
     * Rename an arena, moving its snapshots and runtime state.
     *
     * @throws IllegalArgumentException for unknown / taken / invalid names.
     * @throws IllegalStateException if the snapshot directory cannot be moved.
     */
    public boolean renameArena(@NotNull final String oldName, @NotNull final String newName) {
        final Arena old = getArena(oldName);
        if (old == null) {
            throw new IllegalArgumentException("Arena not found: " + oldName);
        }
        final String sanitized = ColorUtil.sanitizeName(newName);
        if (!ColorUtil.isValidName(sanitized)) {
            throw new IllegalArgumentException("Invalid name: " + newName);
        }
        if (arenas.containsKey(sanitized)) {
            throw new IllegalArgumentException("Arena already exists: " + sanitized);
        }
        if (old.status() == ArenaStatus.SAVING) {
            throw new IllegalArgumentException("Arena " + old.name() + " is saving a snapshot; try again shortly.");
        }
        final String from = old.name();
        plugin.regenManager().cancel(from);
        plugin.autoResetManager().stopCountdown(from);
        plugin.changeTracker().forget(from);
        if (plugin.blockInteractListener() != null) {
            plugin.blockInteractListener().clearTrigger(from);
        }

        final ArenaStatus status = old.status() == ArenaStatus.REGENERATING ? ArenaStatus.READY : old.status();
        final Arena renamed = new Arena(sanitized, old.worldName(),
                old.originX(), old.originY(), old.originZ(),
                old.sizeX(), old.sizeY(), old.sizeZ(),
                old.createdAt(), old.latestSnapshotId(),
                old.lastRegeneratedAt(), old.snapshotCount(), status,
                old.autoResetInterval(), old.asyncRegen(),
                old.hasSpawn(), old.spawnX(), old.spawnY(), old.spawnZ(),
                old.spawnYaw(), old.spawnPitch());
        renamed.setExclusions(old.exclusions());
        renamed.setRegenSpeedPercent(old.regenSpeedPercent());
        // Reserve the new name before touching disk.
        if (arenas.putIfAbsent(sanitized, renamed) != null) {
            throw new IllegalArgumentException("Arena already exists: " + sanitized);
        }
        if (!snapshotManager.renameSnapshotDirectory(from, sanitized)) {
            arenas.remove(sanitized, renamed);
            throw new IllegalStateException("Could not move snapshots of " + from + " to " + sanitized
                    + " (see console).");
        }
        snapshotManager.remapArenaName(from, sanitized);
        arenas.remove(from, old);
        rebuildIndex();
        requestFlush();
        if (renamed.autoResetInterval() > 0 && renamed.latestSnapshotId() > 0) {
            plugin.autoResetManager().startCountdown(renamed);
        }
        return true;
    }

    /** Delete an arena and all its snapshots. */
    public boolean deleteArena(@NotNull final String name) {
        final Arena existing = getArena(name);
        if (existing == null) {
            return false;
        }
        final String key = existing.name();
        plugin.regenManager().cancel(key);
        plugin.autoResetManager().stopCountdown(key);
        plugin.changeTracker().forget(key);
        if (plugin.blockInteractListener() != null) {
            plugin.blockInteractListener().clearTrigger(key);
        }
        if (!arenas.remove(key, existing)) {
            return false;
        }
        rebuildIndex();
        snapshotManager.deleteAllSnapshots(key);
        requestFlush();
        return true;
    }

    /** Call after adding / removing exclusion zones. Dirty tracking ignored excluded voxels, so it restarts. */
    public void onExclusionsChanged(@NotNull final Arena arena) {
        plugin.changeTracker().forget(arena.name());
        requestFlush();
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /** Lookup by name (case-insensitive; sanitized). */
    public @Nullable Arena getArena(@NotNull final String name) {
        final Arena exact = arenas.get(name);
        if (exact != null) return exact;
        return arenas.get(ColorUtil.sanitizeName(name));
    }

    /** @deprecated lookups are synchronous; use {@link #getArena(String)}. */
    @Deprecated
    public @NotNull CompletableFuture<Optional<Arena>> getArenaAsync(@NotNull final String name) {
        return CompletableFuture.completedFuture(Optional.ofNullable(getArena(name)));
    }

    /** @return a copy of all known arenas. */
    public @NotNull Collection<Arena> all() {
        return new ArrayList<>(arenas.values());
    }

    public int count() {
        return arenas.size();
    }

    /** First arena in {@code world} whose cuboid contains the voxel, or {@code null}. Hot path. */
    public @Nullable Arena findContaining(@NotNull final String world, final int x, final int y, final int z) {
        final Arena[] candidates = byWorld.get(world);
        if (candidates == null) {
            return null;
        }
        for (final Arena arena : candidates) {
            if (arena.contains(x, y, z)) {
                return arena;
            }
        }
        return null;
    }

    private void rebuildIndex() {
        final Map<String, List<Arena>> grouped = new HashMap<>();
        for (final Arena arena : arenas.values()) {
            grouped.computeIfAbsent(arena.worldName(), k -> new ArrayList<>()).add(arena);
        }
        final Map<String, Arena[]> index = new HashMap<>();
        grouped.forEach((world, list) -> index.put(world, list.toArray(new Arena[0])));
        this.byWorld = Map.copyOf(index);
    }

    /**
     * Visit every online player inside the arena, on that player's own thread.
     * Containment is re-checked on the player's thread before {@code action} runs.
     */
    public void forEachPlayerInArena(@NotNull final Arena arena, @NotNull final Consumer<Player> action) {
        final String worldName = arena.worldName();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            scheduler.runAtEntity(p, () -> {
                if (!p.isOnline() || !worldName.equals(p.getWorld().getName())) {
                    return;
                }
                final Location loc = p.getLocation();
                if (arena.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                    action.accept(p);
                }
            });
        }
    }

    /**
     * Shutdown: persist all arenas synchronously.
     */
    public void shutdown() {
        flushNow();
        plugin.getSLF4JLogger().info("Arena metadata persisted ({} arenas).", arenas.size());
    }
}
