/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.arena;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.regen.RegenSpeed;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.snapshot.SnapshotMetadata;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * CRUD manager for {@link Arena} instances.
 *
 * <p>Arena metadata is persisted to {@code arenas.yml} and loaded into an
 * in-memory concurrent map at startup. Mutations are written through to disk
 * asynchronously (no main-thread file IO).</p>
 *
 * <p>When an arena is created with an auto-reset interval, a repeating task
 * is started that broadcasts countdown messages and triggers regeneration
 * when the timer reaches zero.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ArenaManager {

    private final ArenaRegenDXPlugin plugin;
    private final ConfigManager configManager;
    private final SnapshotManager snapshotManager;
    private final SchedulerAdapter scheduler;

    private final ConcurrentMap<String, Arena> arenas = new ConcurrentHashMap<>();

    /** Serializes all arenas.yml mutations to prevent lost updates. */
    private final Object arenaIoLock = new Object();

    public ArenaManager(@NotNull final ArenaRegenDXPlugin plugin,
                        @NotNull final ConfigManager configManager,
                        @NotNull final SnapshotManager snapshotManager,
                        @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.snapshotManager = snapshotManager;
        this.scheduler = scheduler;
    }

    /**
     * Async initialization: load arena metadata from arenas.yml.
     */
    public void initializeAsync() {
        scheduler.runAsync(this::loadFromDisk);
    }

    /**
     * Load all arenas from arenas.yml into memory.
     */
    private void loadFromDisk() {
        final FileConfiguration cfg = configManager.config("arenas.yml");
        if (cfg == null) {
            return;
        }
        final ConfigurationSection root = cfg.getConfigurationSection("arenas");
        if (root == null) {
            plugin.getSLF4JLogger().info("No arenas defined yet.");
            return;
        }
        int count = 0;
        for (final String name : root.getKeys(false)) {
            final ConfigurationSection a = root.getConfigurationSection(name);
            if (a == null) continue;
            try {
                final ArenaStatus loadedStatus;
                try {
                    loadedStatus = ArenaStatus.valueOf(a.getString("status", "EMPTY"));
                } catch (final IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Bad status for arena " + name, ex);
                }
                // Coerce stuck in-flight statuses from a previous crash/shutdown.
                final ArenaStatus status = (loadedStatus == ArenaStatus.REGENERATING
                        || loadedStatus == ArenaStatus.SAVING)
                        ? (a.getLong("latest-snapshot", 0) > 0 ? ArenaStatus.READY : ArenaStatus.EMPTY)
                        : loadedStatus;
                final Arena arena = new Arena(
                        name,
                        a.getString("world", ""),
                        a.getInt("origin.x"), a.getInt("origin.y"), a.getInt("origin.z"),
                        a.getInt("size.x"), a.getInt("size.y"), a.getInt("size.z"),
                        a.getLong("created-at", System.currentTimeMillis()),
                        a.getLong("latest-snapshot", 0),
                        a.getLong("last-regenerated", 0),
                        a.getLong("snapshot-count", 0),
                        status,
                        a.getInt("auto-reset", 0),
                        a.getBoolean("async-regen", true),
                        a.getBoolean("spawn.set", false),
                        a.getDouble("spawn.x", 0), a.getDouble("spawn.y", 64), a.getDouble("spawn.z", 0),
                        (float) a.getDouble("spawn.yaw", 0), (float) a.getDouble("spawn.pitch", 0)
                );
                loadExclusions(arena, a);
                arena.setRegenSpeedPercent(a.getInt("regen-speed-percent", RegenSpeed.INHERIT));
                arenas.put(name, arena);
                count++;
            } catch (final Exception e) {
                plugin.getSLF4JLogger().warn("Skipping malformed arena entry: {}", name, e);
            }
        }
        plugin.getSLF4JLogger().info("Loaded {} arena(s) from arenas.yml.", count);
        // Countdowns must start only after arenas are in memory.
        try {
            plugin.autoResetManager().initializeAll();
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Auto-reset initializeAll failed after arena load", t);
        }
    }

    /**
     * Create a new arena from a selection.
     *
     * @param name the arena name.
     * @param world the world.
     * @param pos1  corner 1.
     * @param pos2  corner 2.
     * @param autoResetInterval auto-reset interval in seconds (0 = disabled).
     * @param asyncRegen whether to use async regen.
     * @return the created arena.
     * @throws IllegalArgumentException if the name is taken or invalid.
     */
    public @NotNull Arena createArena(@NotNull final String name, @NotNull final World world,
                                      @NotNull final Location pos1, @NotNull final Location pos2,
                                      final int autoResetInterval, final boolean asyncRegen) {
        final String sanitized = com.dravorax.arenaregendx.util.ColorUtil.sanitizeName(name);
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Invalid arena name: " + name);
        }
        final int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        final int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        final int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        final int sizeX = Math.abs(pos1.getBlockX() - pos2.getBlockX()) + 1;
        final int sizeY = Math.abs(pos1.getBlockY() - pos2.getBlockY()) + 1;
        final int sizeZ = Math.abs(pos1.getBlockZ() - pos2.getBlockZ()) + 1;
        final Arena arena = new Arena(sanitized, world.getName(), minX, minY, minZ,
                sizeX, sizeY, sizeZ, System.currentTimeMillis(),
                0L, 0L, 0L, ArenaStatus.EMPTY,
                autoResetInterval, asyncRegen,
                false, 0, 64, 0, 0, 0);
        if (arenas.putIfAbsent(sanitized, arena) != null) {
            throw new IllegalArgumentException("Arena already exists: " + sanitized);
        }
        saveArena(arena);
        return arena;
    }

    /**
     * Rename an arena. Moves the arena entry, renames its snapshot directory,
     * and updates all metadata.
     *
     * @param oldName the current name.
     * @param newName the new name (will be sanitized).
     * @return true if renamed successfully.
     * @throws IllegalArgumentException if oldName doesn't exist or newName is taken.
     */
    public boolean renameArena(@NotNull final String oldName, @NotNull final String newName) {
        final Arena old = arenas.get(oldName);
        if (old == null) {
            throw new IllegalArgumentException("Arena not found: " + oldName);
        }
        final String sanitized = com.dravorax.arenaregendx.util.ColorUtil.sanitizeName(newName);
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Invalid name: " + newName);
        }
        if (arenas.containsKey(sanitized)) {
            throw new IllegalArgumentException("Arena already exists: " + sanitized);
        }
        final java.io.File newDir = new java.io.File(plugin.getDataFolder(), "snapshots/" + sanitized);
        if (newDir.exists()) {
            throw new IllegalArgumentException("Snapshot directory already exists for: " + sanitized);
        }
        // Cancel in-flight restore before touching identity / disk.
        try {
            plugin.regenManager().cancel(oldName);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Could not cancel regen before rename of {}", oldName, t);
        }
        try {
            plugin.autoResetManager().stopCountdown(oldName);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("stopCountdown during rename failed", t);
        }
        plugin.changeTracker().clear(oldName);
        if (plugin.blockInteractListener() != null) {
            plugin.blockInteractListener().clearTrigger(oldName);
        }

        final ArenaStatus status = (old.status() == ArenaStatus.REGENERATING || old.status() == ArenaStatus.SAVING)
                ? ArenaStatus.READY : old.status();
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
        // Reserve the new name before touching disk so a failed rename never
        // leaves snapshots under a name that is missing from the arena map.
        if (arenas.putIfAbsent(sanitized, renamed) != null) {
            throw new IllegalArgumentException("Arena already exists: " + sanitized);
        }
        final boolean moved = snapshotManager.renameSnapshotDirectory(oldName, sanitized);
        if (!moved) {
            arenas.remove(sanitized, renamed);
            throw new IllegalStateException("Failed to rename snapshot directory " + oldName + " → " + sanitized);
        }
        snapshotManager.remapArenaName(oldName, sanitized);
        arenas.remove(oldName);

        scheduler.runAsync(() -> {
            synchronized (arenaIoLock) {
                final FileConfiguration cfg = configManager.config("arenas.yml");
                if (cfg != null) {
                    cfg.set("arenas." + oldName, null);
                    writeArenaFields(cfg, renamed);
                    configManager.saveArenas(cfg);
                }
            }
        });
        if (renamed.autoResetInterval() > 0 && renamed.latestSnapshotId() > 0) {
            try {
                plugin.autoResetManager().startCountdown(renamed);
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("startCountdown after rename failed", t);
            }
        }
        return true;
    }

    /**
     * Delete an arena and all its snapshots.
     * Cancels regen / auto-reset / dirty tracking before removing identity.
     */
    public boolean deleteArena(@NotNull final String name) {
        final Arena existing = getArena(name);
        if (existing == null) {
            return false;
        }
        final String key = existing.name();
        try {
            plugin.regenManager().cancel(key);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Could not cancel regen before delete of {}", key, t);
        }
        try {
            plugin.autoResetManager().stopCountdown(key);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("stopCountdown during delete failed", t);
        }
        plugin.changeTracker().clear(key);
        if (plugin.blockInteractListener() != null) {
            plugin.blockInteractListener().clearTrigger(key);
        }
        final Arena removed = arenas.remove(key);
        if (removed == null) {
            return false;
        }
        snapshotManager.deleteAllSnapshots(key);
        scheduler.runAsync(() -> {
            synchronized (arenaIoLock) {
                final FileConfiguration cfg = configManager.config("arenas.yml");
                if (cfg != null) {
                    cfg.set("arenas." + key, null);
                    configManager.saveArenas(cfg);
                }
            }
        });
        return true;
    }

    /**
     * Capture a fresh snapshot of an arena and make it the arena's restore point.
     * Single code path for the command, the GUI and the public API: sets
     * {@code SAVING}, records the new snapshot id, persists, prunes old files.
     *
     * @return a future completed with the metadata once the file is written.
     */
    public @NotNull CompletableFuture<SnapshotMetadata> captureSnapshot(@NotNull final Arena arena) {
        if (arenas.get(arena.name()) != arena) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Arena not found: " + arena.name()));
        }
        if (arena.status() == ArenaStatus.SAVING || arena.status() == ArenaStatus.REGENERATING
                || plugin.regenManager().isRegenerating(arena.name())) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Arena is busy (saving or regenerating): " + arena.name()));
        }
        final World world = Bukkit.getWorld(arena.worldName());
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("World not loaded: " + arena.worldName()));
        }
        final Location min = new Location(world, arena.originX(), arena.originY(), arena.originZ());
        final Location max = new Location(world,
                arena.originX() + arena.sizeX() - 1,
                arena.originY() + arena.sizeY() - 1,
                arena.originZ() + arena.sizeZ() - 1);
        arena.setStatus(ArenaStatus.SAVING);
        final CompletableFuture<SnapshotMetadata> future;
        try {
            future = snapshotManager.saveSnapshot(arena.name(), world, min, max);
        } catch (final Throwable t) {
            arena.setStatus(arena.latestSnapshotId() > 0 ? ArenaStatus.READY : ArenaStatus.EMPTY);
            return CompletableFuture.failedFuture(t);
        }
        return future.whenComplete((meta, err) -> {
            if (arenas.get(arena.name()) != arena) {
                return; // deleted or renamed meanwhile — never resurrect it
            }
            if (err == null && meta != null) {
                arena.onSnapshotSaved(meta.snapshotId());
                arena.setStatus(ArenaStatus.READY);
                saveArena(arena);
                snapshotManager.pruneOldSnapshots(arena.name());
            } else {
                arena.setStatus(ArenaStatus.ERROR);
                plugin.getSLF4JLogger().error("Snapshot save failed for {}", arena.name(), err);
            }
        });
    }

    /**
     * Synchronous in-memory lookup.
     */
    public @Nullable Arena getArena(@NotNull final String name) {
        final Arena exact = arenas.get(name);
        if (exact != null) return exact;
        final String sanitized = com.dravorax.arenaregendx.util.ColorUtil.sanitizeName(name);
        return arenas.get(sanitized);
    }

    /**
     * Async lookup.
     */
    public @NotNull CompletableFuture<Optional<Arena>> getArenaAsync(@NotNull final String name) {
        return CompletableFuture.completedFuture(Optional.ofNullable(getArena(name)));
    }

    /**
     * @return a snapshot of all known arenas.
     */
    public @NotNull Collection<Arena> all() {
        return new ArrayList<>(arenas.values());
    }

    /**
     * First arena whose cuboid contains this voxel, or {@code null}.
     */
    public @Nullable Arena findContaining(@NotNull final String world, final int x, final int y, final int z) {
        for (final Arena arena : arenas.values()) {
            if (arena.worldName().equals(world) && arena.contains(x, y, z)) {
                return arena;
            }
        }
        return null;
    }

    public int count() {
        return arenas.size();
    }

    /**
     * Persist a single arena's metadata to arenas.yml (async).
     */
    public void saveArena(@NotNull final Arena arena) {
        scheduler.runAsync(() -> {
            synchronized (arenaIoLock) {
                // Never resurrect a deleted arena after a raced cancel/save.
                if (!arenas.containsKey(arena.name())) {
                    return;
                }
                final FileConfiguration cfg = configManager.config("arenas.yml");
                if (cfg == null) return;
                writeArenaFields(cfg, arena);
                configManager.saveArenas(cfg);
            }
        });
    }

    private void writeArenaFields(@NotNull final FileConfiguration cfg, @NotNull final Arena arena) {
        final String base = "arenas." + arena.name();
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
        cfg.set(base + ".status", arena.status().name());
        cfg.set(base + ".auto-reset", arena.autoResetInterval());
        cfg.set(base + ".async-regen", arena.asyncRegen());
        cfg.set(base + ".regen-speed-percent", arena.regenSpeedPercent());
        cfg.set(base + ".spawn.set", arena.hasSpawn());
        cfg.set(base + ".spawn.x", arena.spawnX());
        cfg.set(base + ".spawn.y", arena.spawnY());
        cfg.set(base + ".spawn.z", arena.spawnZ());
        cfg.set(base + ".spawn.yaw", (double) arena.spawnYaw());
        cfg.set(base + ".spawn.pitch", (double) arena.spawnPitch());
        writeExclusions(cfg, base, arena);
    }

    /**
     * Get all players inside an arena's cuboid.
     *
     * <p>Uses {@link Bukkit#getOnlinePlayers()} instead of {@code World#getPlayers()}
     * so Folia does not require owning the world's player list from a foreign region.
     * Location reads still hop via {@link #forEachPlayerInArena} for mutating work.</p>
     */
    public @NotNull List<Player> playersInArena(@NotNull final Arena arena) {
        final List<Player> result = new ArrayList<>();
        final String worldName = arena.worldName();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (!worldName.equals(p.getWorld().getName())) {
                    continue;
                }
                final Location loc = p.getLocation();
                if (arena.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                    result.add(p);
                }
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("playersInArena skip {}", p.getName(), t);
            }
        }
        return result;
    }

    /**
     * Folia-safe iteration: each matching player is visited on their entity scheduler
     * before containment is re-checked and {@code action} runs.
     */
    public void forEachPlayerInArena(@NotNull final Arena arena,
                                     @NotNull final java.util.function.Consumer<Player> action) {
        final String worldName = arena.worldName();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (!worldName.equals(p.getWorld().getName())) {
                    continue;
                }
            } catch (final Throwable ignored) {
                continue;
            }
            scheduler.runAtEntity(p, () -> {
                try {
                    if (!p.isOnline()) return;
                    if (!worldName.equals(p.getWorld().getName())) return;
                    final Location loc = p.getLocation();
                    if (arena.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                        action.accept(p);
                    }
                } catch (final Throwable t) {
                    plugin.getSLF4JLogger().debug("forEachPlayerInArena failed for {}", p.getName(), t);
                }
            });
        }
    }

    /**
     * Refresh the snapshot count + latest id for an arena.
     */
    public void refreshSnapshotStats(@NotNull final String arenaName) {
        snapshotManager.listSnapshots(arenaName).thenAccept(list -> {
            final Arena arena = arenas.get(arenaName);
            if (arena == null) return;
            long maxId = 0;
            for (final SnapshotMetadata m : list) {
                if (m.snapshotId() > maxId) maxId = m.snapshotId();
            }
            arena.onSnapshotSaved(maxId);
            saveArena(arena);
        });
    }

    /**
     * Shutdown: persist all arenas.
     */
    public void shutdown() {
        synchronized (arenaIoLock) {
            final FileConfiguration cfg = configManager.config("arenas.yml");
            if (cfg == null) {
                plugin.getSLF4JLogger().warn("arenas.yml unavailable during shutdown persist");
                return;
            }
            for (final Arena arena : arenas.values()) {
                // Never persist transient in-flight statuses across restarts.
                if (arena.status() == ArenaStatus.REGENERATING || arena.status() == ArenaStatus.SAVING) {
                    arena.setStatus(ArenaStatus.READY);
                }
                writeArenaFields(cfg, arena);
            }
            configManager.saveArenas(cfg);
        }
        plugin.getSLF4JLogger().info("Arena metadata persisted ({} arenas).", arenas.size());
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

    private static void writeExclusions(@NotNull final FileConfiguration cfg,
                                        @NotNull final String base,
                                        @NotNull final Arena arena) {
        cfg.set(base + ".exclusions", null);
        for (final ExclusionZone zone : arena.exclusions()) {
            final ConfigurationSection z = cfg.createSection(base + ".exclusions." + zone.name());
            zone.writeTo(z);
        }
    }
}
