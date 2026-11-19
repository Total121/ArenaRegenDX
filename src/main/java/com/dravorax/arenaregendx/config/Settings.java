/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.config;

import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Typed settings holders for each config file. Keeping these as plain immutable
 * value objects (rather than live {@code FileConfiguration} access) means the
 * hot path (regen engine) reads primitive fields with zero map lookups.
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class Settings {

    private Settings() {
        // namespace
    }

    // -----------------------------------------------------------------------
    // config.yml
    // -----------------------------------------------------------------------
    public static final class GeneralSettings {
        public final boolean debug;
        public final boolean banner;
        public final boolean wandEnabled;
        public final boolean preferWorldEditSelection;
        public final boolean autoRegenOnExplosion;
        public final boolean autoRegenOnFire;
        public final boolean autoRegenOnPiston;
        public final boolean safeModeDefault;

        private GeneralSettings(final boolean debug, final boolean banner,
                                final boolean wandEnabled, final boolean preferWorldEditSelection,
                                final boolean autoRegenOnExplosion, final boolean autoRegenOnFire,
                                final boolean autoRegenOnPiston, final boolean safeModeDefault) {
            this.debug = debug;
            this.banner = banner;
            this.wandEnabled = wandEnabled;
            this.preferWorldEditSelection = preferWorldEditSelection;
            this.autoRegenOnExplosion = autoRegenOnExplosion;
            this.autoRegenOnFire = autoRegenOnFire;
            this.autoRegenOnPiston = autoRegenOnPiston;
            this.safeModeDefault = safeModeDefault;
        }

        public static @NotNull GeneralSettings from(@NotNull final FileConfiguration c) {
            return new GeneralSettings(
                    c.getBoolean("debug", false),
                    c.getBoolean("banner", true),
                    c.getBoolean("wand-enabled", true),
                    c.getBoolean("prefer-worldedit-selection", false),
                    c.getBoolean("auto-regen.on-explosion", true),
                    c.getBoolean("auto-regen.on-fire", false),
                    c.getBoolean("auto-regen.on-piston", false),
                    c.getBoolean("safe-mode-default", true)
            );
        }
    }

    // -----------------------------------------------------------------------
    // performance.yml
    // -----------------------------------------------------------------------
    public static final class PerformanceSettings {
        public final int maxBlockBudget;
        public final double tpsFull;
        public final double tpsThrottle;
        public final double tpsCrawl;
        public final int chunkBatchSize;
        public final boolean asyncSnapshots;
        public final boolean asyncCompression;
        public final int queueWorkerThreads;
        public final boolean autoMatchRegen;
        public final int regenCountdownSeconds;
        public final double maxTickMillis;
        public final boolean deltaSkip;
        public final boolean skipUnchangedTiles;
        public final boolean dirtyTracking;
        public final double dirtyFullThreshold;
        public final int parallelChunks;
        public final boolean nmsWrites;
        public final boolean nbtTiles;
        public final boolean progressBar;
        /** Global restore throughput multiplier (percent). 100 = default. */
        public final int regenSpeedPercent;

        private PerformanceSettings(final int maxBlockBudget, final double tpsFull,
                                    final double tpsThrottle, final double tpsCrawl,
                                    final int chunkBatchSize, final boolean asyncSnapshots,
                                    final boolean asyncCompression, final int queueWorkerThreads,
                                    final boolean autoMatchRegen, final int regenCountdownSeconds,
                                    final double maxTickMillis, final boolean deltaSkip,
                                    final boolean skipUnchangedTiles, final boolean dirtyTracking,
                                    final double dirtyFullThreshold, final int parallelChunks,
                                    final boolean nmsWrites, final boolean nbtTiles,
                                    final boolean progressBar, final int regenSpeedPercent) {
            this.maxBlockBudget = maxBlockBudget;
            this.tpsFull = tpsFull;
            this.tpsThrottle = tpsThrottle;
            this.tpsCrawl = tpsCrawl;
            this.chunkBatchSize = chunkBatchSize;
            this.asyncSnapshots = asyncSnapshots;
            this.asyncCompression = asyncCompression;
            this.queueWorkerThreads = queueWorkerThreads;
            this.autoMatchRegen = autoMatchRegen;
            this.regenCountdownSeconds = regenCountdownSeconds;
            this.maxTickMillis = maxTickMillis;
            this.deltaSkip = deltaSkip;
            this.skipUnchangedTiles = skipUnchangedTiles;
            this.dirtyTracking = dirtyTracking;
            this.dirtyFullThreshold = dirtyFullThreshold;
            this.parallelChunks = parallelChunks;
            this.nmsWrites = nmsWrites;
            this.nbtTiles = nbtTiles;
            this.progressBar = progressBar;
            this.regenSpeedPercent = regenSpeedPercent;
        }

        public static @NotNull PerformanceSettings from(@NotNull final FileConfiguration c) {
            return new PerformanceSettings(
                    c.getInt("max-block-budget", 20_000),
                    c.getDouble("tps-full", 19.5),
                    c.getDouble("tps-throttle", 18.0),
                    c.getDouble("tps-crawl", 14.0),
                    c.getInt("chunk-batch-size", 8),
                    c.getBoolean("async-snapshots", true),
                    c.getBoolean("async-compression", true),
                    Math.max(1, c.getInt("queue-worker-threads", 2)),
                    c.getBoolean("auto-match-regen", true),
                    Math.max(0, c.getInt("regen-countdown-seconds", 3)),
                    c.getDouble("max-tick-millis", 6.0),
                    c.getBoolean("delta-skip", true),
                    c.getBoolean("skip-unchanged-tiles", false),
                    c.getBoolean("dirty-tracking", true),
                    c.getDouble("dirty-full-threshold", 0.35),
                    Math.max(1, c.getInt("parallel-chunks", 4)),
                    c.getBoolean("nms-writes", true),
                    c.getBoolean("nbt-tiles", true),
                    c.getBoolean("progress-bar", true),
                    Math.max(10, Math.min(500, c.getInt("regen-speed-percent", 100)))
            );
        }
    }

    // -----------------------------------------------------------------------
    // storage.yml
    // -----------------------------------------------------------------------
    public static final class StorageSettings {
        public final CompressionType compression;
        public final int compressionLevel;
        public final boolean deltaStorage;
        public final int maxSnapshotsPerArena;
        public final boolean asyncDiskWrite;
        public final int writeBufferSize;

        public enum CompressionType {
            /** No compression — fastest, largest files. */
            NONE,
            /** GZIP — good ratio, fast. */
            GZIP,
            /** ZLIB (Deflater) — configurable level, default for snapshots. */
            ZLIB
        }

        private StorageSettings(final CompressionType compression, final int compressionLevel,
                                final boolean deltaStorage, final int maxSnapshotsPerArena,
                                final boolean asyncDiskWrite, final int writeBufferSize) {
            this.compression = compression;
            this.compressionLevel = compressionLevel;
            this.deltaStorage = deltaStorage;
            this.maxSnapshotsPerArena = maxSnapshotsPerArena;
            this.asyncDiskWrite = asyncDiskWrite;
            this.writeBufferSize = writeBufferSize;
        }

        public static @NotNull StorageSettings from(@NotNull final FileConfiguration c) {
            final String compStr = c.getString("compression", "ZLIB").toUpperCase();
            CompressionType ct;
            try {
                ct = CompressionType.valueOf(compStr);
            } catch (final IllegalArgumentException e) {
                ct = CompressionType.ZLIB;
            }
            if (ct == null) ct = CompressionType.ZLIB;
            return new StorageSettings(
                    ct,
                    Math.max(-1, Math.min(9, c.getInt("compression-level", 6))),
                    c.getBoolean("delta-storage", true),
                    Math.max(1, c.getInt("max-snapshots-per-arena", 10)),
                    c.getBoolean("async-disk-write", true),
                    Math.max(1024, c.getInt("write-buffer-size", 65536))
            );
        }
    }

    // -----------------------------------------------------------------------
    // particles.yml
    // -----------------------------------------------------------------------
    public static final class ParticleSettings {
        public final boolean enabled;
        public final String borderParticle;
        public final String cornerParticle;
        public final String fillParticle;
        public final int borderDensity;     // particles per edge per refresh
        public final int refreshIntervalTicks;
        public final boolean animatedBorders;
        public final double viewDistanceBlocks;

        private ParticleSettings(final boolean enabled, final String borderParticle,
                                 final String cornerParticle, final String fillParticle,
                                 final int borderDensity, final int refreshIntervalTicks,
                                 final boolean animatedBorders, final double viewDistanceBlocks) {
            this.enabled = enabled;
            this.borderParticle = borderParticle;
            this.cornerParticle = cornerParticle;
            this.fillParticle = fillParticle;
            this.borderDensity = borderDensity;
            this.refreshIntervalTicks = refreshIntervalTicks;
            this.animatedBorders = animatedBorders;
            this.viewDistanceBlocks = viewDistanceBlocks;
        }

        public static @NotNull ParticleSettings from(@NotNull final FileConfiguration c) {
            return new ParticleSettings(
                    c.getBoolean("enabled", true),
                    c.getString("border-particle", Particle.HAPPY_VILLAGER.name()),
                    c.getString("corner-particle", Particle.END_ROD.name()),
                    c.getString("fill-particle", Particle.DUST.name()),
                    c.getInt("border-density", 8),
                    Math.max(1, c.getInt("refresh-interval-ticks", 10)),
                    c.getBoolean("animated-borders", true),
                    c.getDouble("view-distance-blocks", 48.0)
            );
        }
    }

    // -----------------------------------------------------------------------
    // sounds.yml
    // -----------------------------------------------------------------------
    public static final class SoundSettings {
        public final boolean enabled;
        public final String selectPos1;
        public final String selectPos2;
        public final String snapshotSaved;
        public final String snapshotLoaded;
        public final String regenStart;
        public final String regenComplete;
        public final String error;
        public final float volume;
        public final float pitch;

        private SoundSettings(final boolean enabled, final String selectPos1,
                              final String selectPos2, final String snapshotSaved,
                              final String snapshotLoaded, final String regenStart,
                              final String regenComplete, final String error,
                              final float volume, final float pitch) {
            this.enabled = enabled;
            this.selectPos1 = selectPos1;
            this.selectPos2 = selectPos2;
            this.snapshotSaved = snapshotSaved;
            this.snapshotLoaded = snapshotLoaded;
            this.regenStart = regenStart;
            this.regenComplete = regenComplete;
            this.error = error;
            this.volume = volume;
            this.pitch = pitch;
        }

        public static @NotNull SoundSettings from(@NotNull final FileConfiguration c) {
            return new SoundSettings(
                    c.getBoolean("enabled", true),
                    c.getString("select-pos1", "BLOCK_NOTE_BLOCK_PLING"),
                    c.getString("select-pos2", "BLOCK_NOTE_BLOCK_BELL"),
                    c.getString("snapshot-saved", "UI_TOAST_CHALLENGE_COMPLETE"),
                    c.getString("snapshot-loaded", "BLOCK_BEACON_POWER_SELECT"),
                    c.getString("regen-start", "BLOCK_PISTON_EXTEND"),
                    c.getString("regen-complete", "BLOCK_BEACON_ACTIVATE"),
                    c.getString("error", "BLOCK_NOTE_BLOCK_BASS"),
                    (float) c.getDouble("volume", 1.0),
                    (float) c.getDouble("pitch", 1.0)
            );
        }
    }

    // -----------------------------------------------------------------------
    // gui.yml
    // -----------------------------------------------------------------------
    public static final class GuiSettings {
        public final String titleMain;
        public final String titleArenaManager;
        public final String titleSettings;
        public final String titlePerformance;
        public final int arenaManagerPageSize;

        private GuiSettings(final String titleMain, final String titleArenaManager,
                            final String titleSettings, final String titlePerformance,
                            final int arenaManagerPageSize) {
            this.titleMain = titleMain;
            this.titleArenaManager = titleArenaManager;
            this.titleSettings = titleSettings;
            this.titlePerformance = titlePerformance;
            this.arenaManagerPageSize = arenaManagerPageSize;
        }

        public static @NotNull GuiSettings from(@NotNull final FileConfiguration c) {
            return new GuiSettings(
                    c.getString("titles.main", "<dark_gray>› <gradient:#7af0d8:#f7c948>ᴀʀᴇɴᴀ ʀᴇɢᴇɴ ᴅx</gradient> <dark_gray>‹"),
                    c.getString("titles.arena-manager", "<dark_gray>› <aqua>ᴀʀᴇɴᴀꜱ <dark_gray>‹"),
                    c.getString("titles.settings", "<dark_gray>› <aqua>ꜱᴇᴛᴛɪɴɢꜱ <dark_gray>‹"),
                    c.getString("titles.performance", "<dark_gray>› <aqua>ᴘᴇʀꜰᴏʀᴍᴀɴᴄᴇ <dark_gray>‹"),
                    Math.max(9, Math.min(45, c.getInt("arena-manager-page-size", 36)))
            );
        }
    }
}
