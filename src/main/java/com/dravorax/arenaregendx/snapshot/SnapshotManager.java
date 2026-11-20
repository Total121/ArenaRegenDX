/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.snapshot;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.config.Settings;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Snapshot lifecycle: scan → serialize → compress → write, and the reverse.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li><b>Scanning</b> copies chunk data on region threads and parses it async
 *       ({@link RegionScanner}).</li>
 *   <li><b>Serialization & compression</b> run on a small CPU pool.</li>
 *   <li><b>Disk I/O</b> runs on a single ordered writer thread, so files for an
 *       arena are never written concurrently. Files are written to a temp file
 *       and atomically moved into place, so a crash never leaves a torn snapshot.</li>
 * </ul>
 *
 * <h2>Caching</h2>
 * <p>Loaded snapshots are kept in an access-ordered LRU bounded by an estimated
 * memory budget ({@code storage.yml → cache-memory-mb}), not by entry count.
 * The previous count-bounded cache could hold dozens of multi-megabyte
 * warzone snapshots indefinitely.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class SnapshotManager {

    private static final String EXT = ".ardx";
    private static final String LEGACY_EXT = ".sarp";

    private final ArenaRegenDXPlugin plugin;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final RegionScanner scanner;

    /** Single-thread executor for ordered disk I/O. */
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "ArenaRegenDX-Disk");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** Bounded pool for compression/serialization (CPU-bound). */
    private final ExecutorService compressionPool;

    /** Snapshot-id generator per arena. */
    private final Map<String, AtomicLong> idCounters = new ConcurrentHashMap<>();

    /** Memory-bounded LRU cache of loaded snapshots. Guarded by {@code this}. */
    private final LinkedHashMap<String, Snapshot> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;

    public SnapshotManager(@NotNull final ArenaRegenDXPlugin plugin,
                           @NotNull final ConfigManager configManager,
                           @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.scanner = new RegionScanner(plugin, scheduler, configManager::performance);
        final int threads = configManager.performance().queueWorkerThreads;
        this.compressionPool = Executors.newFixedThreadPool(threads, r -> {
            final Thread t = new Thread(r, "ArenaRegenDX-Compression");
            t.setDaemon(true);
            return t;
        });
        final File dir = snapshotsRoot();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getSLF4JLogger().error("Could not create snapshot directory {}", dir.getAbsolutePath());
        }
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    /**
     * Capture, compress and write a snapshot of {@code [min, max]}.
     * Prefer {@code ArenaManager#captureSnapshot}, which also updates the arena.
     *
     * @return a future completed with the snapshot metadata once it is on disk.
     */
    public @NotNull CompletableFuture<SnapshotMetadata> saveSnapshot(@NotNull final String arenaName,
                                                                     @NotNull final World world,
                                                                     @NotNull final Location min,
                                                                     @NotNull final Location max) {
        final String worldName = world.getName();
        final int originX = Math.min(min.getBlockX(), max.getBlockX());
        final int originY = Math.min(min.getBlockY(), max.getBlockY());
        final int originZ = Math.min(min.getBlockZ(), max.getBlockZ());
        return scanner.scan(world, min, max)
                .thenApplyAsync(snapshot -> new Object[]{snapshot, serialize(snapshot)}, compressionPool)
                .thenApplyAsync(pair -> {
                    final Snapshot snapshot = (Snapshot) pair[0];
                    final byte[] bytes = (byte[]) pair[1];
                    final long snapshotId = nextSnapshotId(arenaName);
                    final File file = new File(ensureArenaDir(arenaName), snapshotId + EXT);
                    try {
                        writeAtomically(file.toPath(), bytes);
                    } catch (final IOException e) {
                        throw new IllegalStateException("Snapshot write failed: " + file, e);
                    }
                    cachePut(arenaName, snapshotId, snapshot);
                    final SnapshotMetadata meta = new SnapshotMetadata(
                            arenaName, snapshotId, worldName, originX, originY, originZ,
                            snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ(),
                            snapshot.createdAt(), snapshot.blockCount(), bytes.length,
                            configManager.storage().compression != Settings.StorageSettings.CompressionType.NONE);
                    try {
                        scheduler.runGlobal(() -> org.bukkit.Bukkit.getPluginManager().callEvent(
                                new com.dravorax.arenaregendx.api.events.SnapshotSavedEvent(meta)));
                    } catch (final Throwable t) {
                        plugin.getSLF4JLogger().debug("SnapshotSavedEvent not dispatched (disabling?)", t);
                    }
                    return meta;
                }, diskWriter);
    }

    private byte @NotNull [] serialize(@NotNull final Snapshot snapshot) {
        final Settings.StorageSettings storage = configManager.storage();
        final int compression = switch (storage.compression) {
            case NONE -> SnapshotSerializer.COMPRESSION_NONE;
            case GZIP -> SnapshotSerializer.COMPRESSION_GZIP;
            case ZLIB -> SnapshotSerializer.COMPRESSION_ZLIB;
        };
        try {
            return SnapshotSerializer.serialize(snapshot, compression, storage.compressionLevel);
        } catch (final IOException e) {
            throw new IllegalStateException("Snapshot serialization failed", e);
        }
    }

    private static void writeAtomically(@NotNull final Path target, final byte @NotNull [] bytes) throws IOException {
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // -----------------------------------------------------------------------
    // Load / list / delete
    // -----------------------------------------------------------------------

    /** Load a snapshot (cached), decompressing on the disk thread. */
    public @NotNull CompletableFuture<Snapshot> loadSnapshot(@NotNull final String arenaName,
                                                             final long snapshotId) {
        final Snapshot cached = cacheGet(arenaName, snapshotId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> {
            final Snapshot again = cacheGet(arenaName, snapshotId);
            if (again != null) {
                return again;
            }
            final File file = existingSnapshotFile(arenaName, snapshotId);
            if (file == null) {
                throw new IllegalStateException("Snapshot #" + snapshotId + " of " + arenaName + " not found");
            }
            try {
                final Snapshot snapshot = SnapshotSerializer.deserialize(Files.readAllBytes(file.toPath()));
                snapshot.resolvePalette();
                cachePut(arenaName, snapshotId, snapshot);
                return snapshot;
            } catch (final IOException e) {
                throw new IllegalStateException("Snapshot read failed: " + file.getName() + " (" + e.getMessage() + ")", e);
            }
        }, diskWriter);
    }

    /** List snapshot metadata for an arena, newest first (reads 28-byte headers only). */
    public @NotNull CompletableFuture<List<SnapshotMetadata>> listSnapshots(@NotNull final String arenaName) {
        return CompletableFuture.supplyAsync(() -> {
            final List<SnapshotMetadata> list = new ArrayList<>();
            for (final File f : snapshotFiles(arenaName)) {
                try {
                    final SnapshotSerializer.HeaderInfo h = SnapshotSerializer.readHeader(f.toPath());
                    list.add(new SnapshotMetadata(
                            arenaName, parseSnapshotId(f.getName()), "", 0, 0, 0,
                            h.sizeX(), h.sizeY(), h.sizeZ(),
                            h.createdAt(), h.sizeX() * h.sizeY() * h.sizeZ(),
                            f.length(), h.compressed()));
                } catch (final Exception e) {
                    plugin.getSLF4JLogger().warn("Skipping corrupt snapshot file {}: {}", f.getName(), e.getMessage());
                }
            }
            list.sort((a, b) -> Long.compare(b.snapshotId(), a.snapshotId()));
            return list;
        }, diskWriter);
    }

    /** @return true when the snapshot file exists on disk (or is cached). */
    public @NotNull CompletableFuture<Boolean> exists(@NotNull final String arenaName, final long snapshotId) {
        if (cacheGet(arenaName, snapshotId) != null) {
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.supplyAsync(() -> existingSnapshotFile(arenaName, snapshotId) != null, diskWriter);
    }

    public @NotNull CompletableFuture<Boolean> deleteSnapshot(@NotNull final String arenaName, final long snapshotId) {
        cacheRemove(arenaName, snapshotId);
        return CompletableFuture.supplyAsync(() -> {
            final File file = existingSnapshotFile(arenaName, snapshotId);
            return file != null && file.delete();
        }, diskWriter);
    }

    /** Delete every snapshot of an arena (arena deleted). */
    public void deleteAllSnapshots(@NotNull final String arenaName) {
        cacheRemoveArena(arenaName);
        idCounters.remove(arenaName);
        diskWriter.execute(() -> {
            final File dir = arenaDir(arenaName);
            final File[] files = dir.listFiles();
            if (files != null) {
                for (final File f : files) {
                    if (!f.delete()) {
                        plugin.getSLF4JLogger().warn("Could not delete {}", f.getAbsolutePath());
                    }
                }
            }
            if (dir.exists() && !dir.delete()) {
                plugin.getSLF4JLogger().warn("Could not delete {}", dir.getAbsolutePath());
            }
        });
    }

    /**
     * Prune snapshots beyond {@code max-snapshots-per-arena}, oldest ids first.
     * The snapshot an arena currently restores from is never pruned.
     */
    public void pruneOldSnapshots(@NotNull final String arenaName, final long keepId) {
        diskWriter.execute(() -> {
            try {
                final List<File> files = snapshotFiles(arenaName);
                files.sort((a, b) -> Long.compare(parseSnapshotId(b.getName()), parseSnapshotId(a.getName())));
                final int max = maxSnapshotsPerArena();
                for (int i = max; i < files.size(); i++) {
                    final File f = files.get(i);
                    final long id = parseSnapshotId(f.getName());
                    if (id == keepId) {
                        continue;
                    }
                    if (!f.delete()) {
                        plugin.getSLF4JLogger().warn("Could not delete old snapshot {}", f.getName());
                    }
                    cacheRemove(arenaName, id);
                }
            } catch (final Exception e) {
                plugin.getSLF4JLogger().warn("Snapshot prune failed for {}", arenaName, e);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Rename
    // -----------------------------------------------------------------------

    /** Remap id counters and cache keys after an arena rename. */
    public void remapArenaName(@NotNull final String oldName, @NotNull final String newName) {
        final AtomicLong counter = idCounters.remove(oldName);
        if (counter != null) {
            idCounters.put(newName, counter);
        }
        synchronized (this) {
            final String oldPrefix = oldName + ":";
            final List<Map.Entry<String, Snapshot>> moved = new ArrayList<>();
            final Iterator<Map.Entry<String, Snapshot>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<String, Snapshot> e = it.next();
                if (e.getKey().startsWith(oldPrefix)) {
                    moved.add(Map.entry(newName + ":" + e.getKey().substring(oldPrefix.length()), e.getValue()));
                    it.remove();
                }
            }
            for (final Map.Entry<String, Snapshot> e : moved) {
                cache.put(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Rename the on-disk snapshot directory on the disk thread and wait for it.
     *
     * @return true if moved, absent, or nothing to do.
     */
    public boolean renameSnapshotDirectory(@NotNull final String oldName, @NotNull final String newName) {
        try {
            return diskWriter.submit(() -> {
                final File oldDir = arenaDir(oldName);
                final File newDir = arenaDir(newName);
                if (!oldDir.exists()) {
                    return true;
                }
                if (newDir.exists()) {
                    plugin.getSLF4JLogger().error("Cannot rename snapshots {} -> {}: target exists", oldName, newName);
                    return false;
                }
                try {
                    Files.move(oldDir.toPath(), newDir.toPath());
                    return true;
                } catch (final IOException moveFailed) {
                    // Cross-volume fallback: copy then delete.
                    if (!newDir.mkdirs()) {
                        return false;
                    }
                    final File[] files = oldDir.listFiles();
                    if (files != null) {
                        for (final File f : files) {
                            Files.copy(f.toPath(), new File(newDir, f.getName()).toPath(),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                        for (final File f : files) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                    //noinspection ResultOfMethodCallIgnored
                    oldDir.delete();
                    return true;
                }
            }).get(60L, TimeUnit.SECONDS);
        } catch (final Exception e) {
            plugin.getSLF4JLogger().error("Snapshot directory rename failed {} -> {}", oldName, newName, e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Cache
    // -----------------------------------------------------------------------

    private synchronized @Nullable Snapshot cacheGet(@NotNull final String arena, final long id) {
        return cache.get(cacheKey(arena, id));
    }

    private synchronized void cachePut(@NotNull final String arena, final long id, @NotNull final Snapshot snapshot) {
        final Snapshot previous = cache.put(cacheKey(arena, id), snapshot);
        if (previous != null) {
            cachedBytes -= previous.estimatedBytes();
        }
        cachedBytes += snapshot.estimatedBytes();
        final long budget = Math.max(16L, configManager.storage().cacheMemoryMb) * 1024L * 1024L;
        final Iterator<Map.Entry<String, Snapshot>> it = cache.entrySet().iterator();
        // Evict least-recently-used entries, but always keep the one just inserted.
        while (cachedBytes > budget && cache.size() > 1 && it.hasNext()) {
            final Map.Entry<String, Snapshot> eldest = it.next();
            if (eldest.getValue() == snapshot) {
                continue;
            }
            cachedBytes -= eldest.getValue().estimatedBytes();
            it.remove();
        }
    }

    private synchronized void cacheRemove(@NotNull final String arena, final long id) {
        final Snapshot removed = cache.remove(cacheKey(arena, id));
        if (removed != null) {
            cachedBytes -= removed.estimatedBytes();
        }
    }

    private synchronized void cacheRemoveArena(@NotNull final String arena) {
        final String prefix = arena + ":";
        final Iterator<Map.Entry<String, Snapshot>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Snapshot> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                cachedBytes -= e.getValue().estimatedBytes();
                it.remove();
            }
        }
    }

    /** Evict all cached snapshots. */
    public synchronized void clearCache() {
        cache.clear();
        cachedBytes = 0L;
    }

    /** @return estimated bytes held by the snapshot cache. */
    public synchronized long cachedBytes() {
        return cachedBytes;
    }

    /** @return number of cached snapshots. */
    public synchronized int cachedCount() {
        return cache.size();
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    /** Flush pending disk writes, then stop the executors. */
    public void shutdown() {
        try {
            diskWriter.submit(() -> { }).get(30L, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException | java.util.concurrent.TimeoutException e) {
            plugin.getSLF4JLogger().warn("Snapshot disk writer did not flush cleanly", e);
        }
        compressionPool.shutdown();
        diskWriter.shutdown();
        clearCache();
        try {
            if (!diskWriter.awaitTermination(10L, TimeUnit.SECONDS)) {
                diskWriter.shutdownNow();
            }
            if (!compressionPool.awaitTermination(5L, TimeUnit.SECONDS)) {
                compressionPool.shutdownNow();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Filesystem helpers
    // -----------------------------------------------------------------------

    @NotNull File snapshotsRoot() {
        return new File(plugin.getDataFolder(), "snapshots");
    }

    @NotNull File arenaDir(@NotNull final String arenaName) {
        return new File(snapshotsRoot(), arenaName);
    }

    private @NotNull File ensureArenaDir(@NotNull final String arenaName) {
        final File dir = arenaDir(arenaName);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create " + dir.getAbsolutePath());
        }
        return dir;
    }

    private @Nullable File existingSnapshotFile(@NotNull final String arenaName, final long snapshotId) {
        final File dir = arenaDir(arenaName);
        final File current = new File(dir, snapshotId + EXT);
        if (current.isFile()) {
            return current;
        }
        final File legacy = new File(dir, snapshotId + LEGACY_EXT);
        return legacy.isFile() ? legacy : null;
    }

    private @NotNull List<File> snapshotFiles(@NotNull final String arenaName) {
        final File[] files = arenaDir(arenaName).listFiles((d, name) -> name.endsWith(EXT) || name.endsWith(LEGACY_EXT));
        return files == null ? new ArrayList<>() : new ArrayList<>(List.of(files));
    }

    private long nextSnapshotId(@NotNull final String arenaName) {
        return idCounters.computeIfAbsent(arenaName, k -> new AtomicLong(loadMaxSnapshotId(k))).incrementAndGet();
    }

    private long loadMaxSnapshotId(@NotNull final String arenaName) {
        long max = 0L;
        for (final File f : snapshotFiles(arenaName)) {
            max = Math.max(max, parseSnapshotId(f.getName()));
        }
        return max;
    }

    private static long parseSnapshotId(@NotNull final String filename) {
        final int dot = filename.indexOf('.');
        if (dot <= 0) return 0L;
        try {
            return Long.parseLong(filename.substring(0, dot));
        } catch (final NumberFormatException e) {
            return 0L;
        }
    }

    private static @NotNull String cacheKey(@NotNull final String arenaName, final long snapshotId) {
        return arenaName + ":" + snapshotId;
    }

    /** @return the configured maximum snapshots per arena. */
    public int maxSnapshotsPerArena() {
        return configManager.storage().maxSnapshotsPerArena;
    }
}
