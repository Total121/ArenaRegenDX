/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-level orchestrator for snapshot lifecycle: scan → serialize → compress →
 * write to disk, and the reverse for loading.
 *
 * <h2>Threading model</h2>
 * <ul>
 *   <li><b>Scanning</b> happens on region threads (Folia) or main thread (Paper)
 *       via {@link RegionScanner}.</li>
 *   <li><b>Serialization & compression</b> happens on a bounded async pool.</li>
 *   <li><b>Disk I/O</b> happens on a dedicated single-thread executor so writes
 *       are ordered per arena (avoids file corruption from concurrent writes).</li>
 * </ul>
 *
 * <h2>Caching</h2>
 * Loaded snapshots are cached in a {@link ConcurrentHashMap} keyed by
 * {@code arenaName:snapshotId}. The cache is unbounded by default (snapshots are
 * immutable and arenas are bounded by {@code max-snapshots-per-arena}); on
 * shutdown the cache is cleared to free memory.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class SnapshotManager {

    private final ArenaRegenDXPlugin plugin;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;

    private final RegionScanner scanner;

    /** Single-thread executor for ordered disk writes (per arena). */
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "arenaregendx-disk-writer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** Bounded async pool for compression/serialization (CPU-bound). */
    private final ExecutorService compressionPool;

    /** Snapshot-id generator per arena (persisted alongside arena metadata). */
    private final Map<String, AtomicLong> idCounters = new ConcurrentHashMap<>();

    /**
     * Byte-weighted LRU of loaded snapshots. Bounded by both entry count and an
     * estimate of retained heap so large arenas cannot pin gigabytes.
     */
    private final SnapshotCache cache = new SnapshotCache(
            16, Math.max(32L << 20, Runtime.getRuntime().maxMemory() / 8L));

    public SnapshotManager(@NotNull final ArenaRegenDXPlugin plugin,
                           @NotNull final ConfigManager configManager,
                           @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.scanner = new RegionScanner(plugin, scheduler, configManager::performance);
        final int threads = configManager.performance().queueWorkerThreads;
        this.compressionPool = Executors.newFixedThreadPool(threads, r -> {
            final Thread t = new Thread(r, "arenaregendx-compression");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
    }

    /**
     * Async initialization (creates snapshot directories).
     */
    public void initializeAsync() {
        scheduler.runAsync(() -> {
            final File dir = snapshotsRoot();
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            plugin.getSLF4JLogger().info("Snapshot storage ready at {}", dir.getAbsolutePath());
        });
    }

    /**
     * Save a snapshot of the given region under the given arena name.
     *
     * @param arenaName the arena name (sanitized).
     * @param world     the world.
     * @param min       min corner.
     * @param max       max corner.
     * @return a future completed with the snapshot metadata once written to disk.
     */
    public @NotNull CompletableFuture<SnapshotMetadata> saveSnapshot(@NotNull final String arenaName,
                                                                     @NotNull final World world,
                                                                     @NotNull final Location min,
                                                                     @NotNull final Location max) {
        // The snapshot becomes the new baseline: start recording changes now so
        // edits made while the scan is still running are not lost.
        try {
            plugin.changeTracker().arm(arenaName);
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().debug("changeTracker arm before save failed", t);
        }
        // 1. Scan (region-thread reads), then serialize+compress+write in a single
        //    composed stage so the snapshot reference stays in scope throughout.
        final CompletableFuture<SnapshotMetadata> result = scanner.scan(world, min, max).thenComposeAsync(snapshot -> {
            // 2. Serialize + compress (CPU pool).
            return CompletableFuture.supplyAsync(() -> {
                final Settings.StorageSettings storage = configManager.storage();
                final int compression = switch (storage.compression) {
                    case NONE -> SnapshotSerializer.COMPRESSION_NONE;
                    case GZIP -> SnapshotSerializer.COMPRESSION_GZIP;
                    case ZLIB -> SnapshotSerializer.COMPRESSION_ZLIB;
                };
                try {
                    return SnapshotSerializer.serialize(snapshot, compression, storage.compressionLevel);
                } catch (final IOException e) {
                    throw new RuntimeException("Snapshot serialization failed", e);
                }
            }, compressionPool).thenComposeAsync(bytes -> {
                // 3. Write to disk (single-thread executor). `snapshot` still in scope.
                return CompletableFuture.supplyAsync(() -> {
                    final long snapshotId = nextSnapshotId(arenaName);
                    final File file = snapshotFile(arenaName, snapshotId);
                    try {
                        writeAtomically(file, bytes);
                    } catch (final IOException e) {
                        throw new RuntimeException("Snapshot write failed: " + file, e);
                    }
                    final SnapshotMetadata meta = new SnapshotMetadata(
                            arenaName, snapshotId, world.getName(),
                            min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                            snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ(),
                            snapshot.createdAt(), snapshot.blockCount(),
                            file.length(), configManager.storage().compression != Settings.StorageSettings.CompressionType.NONE
                    );
                    // Older snapshots of this arena are rarely restored again: free them,
                    // then cache the new one so an immediate regen skips the disk read.
                    cache.removeArena(arenaName);
                    cache.put(cacheKey(arenaName, snapshotId), snapshot);
                    // Fire the public event on the global region (never from disk I/O).
                    final SnapshotMetadata eventMeta = meta;
                    scheduler.runGlobal(() -> {
                        try {
                            final var event = new com.dravorax.arenaregendx.api.events.SnapshotSavedEvent(eventMeta);
                            org.bukkit.Bukkit.getPluginManager().callEvent(event);
                        } catch (final Throwable t) {
                            plugin.getSLF4JLogger().warn("SnapshotSavedEvent dispatch failed", t);
                        }
                    });
                    return meta;
                }, diskWriter);
            });
        }, compressionPool);
        result.whenComplete((meta, err) -> {
            if (err != null) {
                // Baseline never materialised: force the next restore to be full.
                plugin.changeTracker().invalidate(arenaName);
            }
        });
        return result;
    }

    /** Write to a temp file then move into place, so a crash never leaves a torn snapshot. */
    private static void writeAtomically(@NotNull final File file, final byte @NotNull [] bytes) throws IOException {
        final java.nio.file.Path target = file.toPath();
        final java.nio.file.Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Load a snapshot from disk (async), with caching.
     *
     * @param arenaName  the arena name.
     * @param snapshotId the snapshot id.
     * @return a future completed with the snapshot.
     */
    public @NotNull CompletableFuture<Snapshot> loadSnapshot(@NotNull final String arenaName,
                                                             final long snapshotId) {
        final String key = cacheKey(arenaName, snapshotId);
        final Snapshot cached = cache.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> {
            final File file = snapshotFile(arenaName, snapshotId);
            if (!file.exists()) {
                throw new RuntimeException("Snapshot not found: " + file);
            }
            try {
                final byte[] bytes = Files.readAllBytes(file.toPath());
                final Snapshot snapshot = SnapshotSerializer.deserialize(bytes);
                snapshot.resolvePalette();
                cache.put(key, snapshot);
                return snapshot;
            } catch (final IOException e) {
                throw new RuntimeException("Snapshot read failed: " + file, e);
            }
        }, diskWriter);
    }

    /**
     * List all snapshot metadata for an arena (reads file headers only — fast).
     *
     * @param arenaName the arena name.
     * @return a future completed with the list (newest first).
     */
    public @NotNull CompletableFuture<List<SnapshotMetadata>> listSnapshots(@NotNull final String arenaName) {
        return CompletableFuture.supplyAsync(() -> {
            final File dir = arenaDir(arenaName);
            final List<SnapshotMetadata> list = new ArrayList<>();
            if (!dir.exists()) {
                return list;
            }
            final File[] files = dir.listFiles((d, name) -> name.endsWith(".ardx") || name.endsWith(".sarp"));
            if (files == null) {
                return list;
            }
            for (final File f : files) {
                try {
                    // Only the fixed-size header is needed; never read whole snapshots here.
                    final SnapshotSerializer.HeaderInfo h = SnapshotSerializer.readHeader(readHeaderBytes(f));
                    final long snapshotId = parseSnapshotId(f.getName());
                    list.add(new SnapshotMetadata(
                            arenaName, snapshotId, "", 0, 0, 0,
                            h.sizeX(), h.sizeY(), h.sizeZ(),
                            h.createdAt(), (int) Math.min(Integer.MAX_VALUE, (long) h.sizeX() * h.sizeY() * h.sizeZ()),
                            f.length(), h.compressed()
                    ));
                } catch (final Exception e) {
                    plugin.getSLF4JLogger().warn("Skipping corrupt snapshot file: {}", f.getName(), e);
                }
            }
            list.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
            return list;
        }, diskWriter);
    }

    /**
     * Delete a snapshot file.
     */
    public @NotNull CompletableFuture<Boolean> deleteSnapshot(@NotNull final String arenaName, final long snapshotId) {
        cache.remove(cacheKey(arenaName, snapshotId));
        return CompletableFuture.supplyAsync(() -> {
            final File file = snapshotFile(arenaName, snapshotId);
            return file.exists() && file.delete();
        }, diskWriter);
    }

    /**
     * Delete all snapshots for an arena (used when an arena is deleted).
     */
    public void deleteAllSnapshots(@NotNull final String arenaName) {
        // Evict cache entries and the id counter (no leak per deleted arena).
        cache.removeArena(arenaName);
        idCounters.remove(arenaName);
        diskWriter.submit(() -> {
            final File dir = arenaDir(arenaName);
            if (!dir.exists()) return;
            final File[] files = dir.listFiles();
            if (files != null) {
                for (final File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        });
    }

    /**
     * Evict all cached snapshots (frees memory; next load re-reads from disk).
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Shutdown: flush pending disk writes, then close executors.
     */
    public void shutdown() {
        // Flush the disk writer queue (ordered, blocking).
        try {
            diskWriter.submit(() -> plugin.getSLF4JLogger().info("Snapshot disk writer flushed.")).get();
        } catch (final InterruptedException | java.util.concurrent.ExecutionException e) {
            plugin.getSLF4JLogger().warn("Disk writer flush interrupted", e);
        }
        diskWriter.shutdown();
        compressionPool.shutdown();
        cache.clear();
        try {
            diskWriter.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            compressionPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
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

    @NotNull File snapshotFile(@NotNull final String arenaName, final long snapshotId) {
        final File dir = arenaDir(arenaName);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        final File ardx = new File(dir, snapshotId + ".ardx");
        if (ardx.exists()) {
            return ardx;
        }
        final File legacy = new File(dir, snapshotId + ".sarp");
        if (legacy.exists()) {
            return legacy;
        }
        return ardx;
    }

    private long nextSnapshotId(@NotNull final String arenaName) {
        return idCounters.computeIfAbsent(arenaName, k -> new AtomicLong(loadMaxSnapshotId(k)))
                .incrementAndGet();
    }

    /**
     * Remap in-memory id counters and soft cache keys when an arena is renamed.
     */
    public void remapArenaName(@NotNull final String oldName, @NotNull final String newName) {
        final AtomicLong counter = idCounters.remove(oldName);
        if (counter != null) {
            idCounters.put(newName, counter);
        }
        cache.renameArena(oldName, newName);
    }

    /**
     * Synchronously rename the on-disk snapshot directory via the disk writer.
     * Must complete before the arena map identity flips.
     *
     * @return true if the directory was moved, did not exist, or was already at the target.
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
                    plugin.getSLF4JLogger().error(
                            "Cannot rename snapshots {} → {}: target directory already exists", oldName, newName);
                    return false;
                }
                if (oldDir.renameTo(newDir)) {
                    return true;
                }
                // Cross-volume fallback: copy then delete.
                boolean ok = true;
                //noinspection ResultOfMethodCallIgnored
                newDir.mkdirs();
                final File[] files = oldDir.listFiles();
                if (files != null) {
                    for (final File f : files) {
                        try {
                            Files.copy(f.toPath(), new File(newDir, f.getName()).toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (final Exception e) {
                            ok = false;
                            plugin.getSLF4JLogger().error("Snapshot copy failed during rename", e);
                        }
                    }
                }
                if (!ok) {
                    return false;
                }
                if (files != null) {
                    for (final File f : files) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                oldDir.delete();
                return true;
            }).get(60L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final Exception e) {
            plugin.getSLF4JLogger().error("Snapshot directory rename failed {} → {}", oldName, newName, e);
            return false;
        }
    }

    private long loadMaxSnapshotId(@NotNull final String arenaName) {
        final File dir = arenaDir(arenaName);
        if (!dir.exists()) return 0L;
        final File[] files = dir.listFiles((d, name) -> name.endsWith(".ardx") || name.endsWith(".sarp"));
        if (files == null) return 0L;
        long max = 0L;
        for (final File f : files) {
            final long id = parseSnapshotId(f.getName());
            if (id > max) max = id;
        }
        return max;
    }

    private static byte @NotNull [] readHeaderBytes(@NotNull final File f) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(f.toPath())) {
            return in.readNBytes(SnapshotSerializer.HEADER_BYTES);
        }
    }

    private static long parseSnapshotId(@NotNull final String filename) {
        final int dot = filename.lastIndexOf('.');
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

    /** @return the configured maximum snapshots per arena (older ones are pruned). */
    public int maxSnapshotsPerArena() {
        return configManager.storage().maxSnapshotsPerArena;
    }

    /**
     * Prune old snapshots beyond the configured limit.
     * Must run entirely on {@code diskWriter} — never nest another diskWriter future.
     */
    public void pruneOldSnapshots(@NotNull final String arenaName) {
        diskWriter.submit(() -> {
            try {
                final File dir = arenaDir(arenaName);
                if (!dir.exists()) return;
                final File[] files = dir.listFiles((d, name) -> name.endsWith(".ardx") || name.endsWith(".sarp"));
                if (files == null || files.length == 0) return;
                final List<File> sorted = new ArrayList<>(List.of(files));
                // Newest id first (ids are monotonic; mtimes can be touched by backups).
                sorted.sort((a, b) -> Long.compare(parseSnapshotId(b.getName()), parseSnapshotId(a.getName())));
                final int max = maxSnapshotsPerArena();
                for (int i = max; i < sorted.size(); i++) {
                    final File f = sorted.get(i);
                    final long id = parseSnapshotId(f.getName());
                    if (!f.delete()) {
                        plugin.getSLF4JLogger().warn("Could not delete old snapshot {}", f.getName());
                    }
                    cache.remove(cacheKey(arenaName, id));
                }
            } catch (final Exception e) {
                plugin.getSLF4JLogger().warn("Snapshot prune failed for {}", arenaName, e);
            }
        });
    }

    /**
     * Small LRU keyed by {@code arena:id}, bounded by entry count and by the
     * approximate retained size of the snapshots it holds.
     */
    private static final class SnapshotCache {
        private final int maxEntries;
        private final long maxBytes;
        private final java.util.LinkedHashMap<String, Snapshot> map = new java.util.LinkedHashMap<>(16, 0.75f, true);
        private final Map<String, Long> weights = new java.util.HashMap<>();
        private long totalBytes;

        SnapshotCache(final int maxEntries, final long maxBytes) {
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        synchronized @org.jetbrains.annotations.Nullable Snapshot get(@NotNull final String key) {
            return map.get(key);
        }

        synchronized void put(@NotNull final String key, @NotNull final Snapshot snapshot) {
            final long weight = snapshot.approximateBytes();
            remove(key);
            if (weight > maxBytes) {
                return; // would evict everything else and still not fit
            }
            map.put(key, snapshot);
            weights.put(key, weight);
            totalBytes += weight;
            final var it = map.entrySet().iterator();
            while ((map.size() > maxEntries || totalBytes > maxBytes) && it.hasNext()) {
                final String eldest = it.next().getKey();
                if (eldest.equals(key)) continue;
                it.remove();
                final Long w = weights.remove(eldest);
                if (w != null) totalBytes -= w;
            }
        }

        synchronized void remove(@NotNull final String key) {
            if (map.remove(key) != null) {
                final Long w = weights.remove(key);
                if (w != null) totalBytes -= w;
            }
        }

        synchronized void removeArena(@NotNull final String arenaName) {
            final String prefix = arenaName + ":";
            final var it = map.keySet().iterator();
            while (it.hasNext()) {
                final String k = it.next();
                if (k.startsWith(prefix)) {
                    it.remove();
                    final Long w = weights.remove(k);
                    if (w != null) totalBytes -= w;
                }
            }
        }

        synchronized void renameArena(@NotNull final String oldName, @NotNull final String newName) {
            final String prefix = oldName + ":";
            final List<String> keys = new ArrayList<>();
            for (final String k : map.keySet()) {
                if (k.startsWith(prefix)) keys.add(k);
            }
            for (final String k : keys) {
                final Snapshot snap = map.remove(k);
                final Long w = weights.remove(k);
                if (w != null) totalBytes -= w;
                if (snap != null) {
                    put(newName + ":" + k.substring(prefix.length()), snap);
                }
            }
        }

        synchronized void clear() {
            map.clear();
            weights.clear();
            totalBytes = 0L;
        }
    }
}
