/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2026 DravoraX. All rights reserved.
 */

package com.dravorax.arenaregendx.config;

import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

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
        public final int queueWorkerThreads;
        /** Minimum seconds between two automatic (explosion / fire / piston) restores of one arena. */
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

        private PerformanceSettings(final FileConfiguration c) {
            this.maxBlockBudget = Math.max(100, c.getInt("max-block-budget", 20_000));
            this.tpsFull = c.getDouble("tps-full", 19.5);
            this.tpsThrottle = c.getDouble("tps-throttle", 18.0);
            this.tpsCrawl = c.getDouble("tps-crawl", 14.0);
            this.chunkBatchSize = Math.max(1, c.getInt("chunk-batch-size", 8));
            this.queueWorkerThreads = Math.max(1, Math.min(8, c.getInt("queue-worker-threads", 2)));
            this.regenCountdownSeconds = Math.max(0, c.getInt("auto-regen-cooldown-seconds",
                    c.getInt("regen-countdown-seconds", 3)));
            this.maxTickMillis = Math.max(0.4, Math.min(25.0, c.getDouble("max-tick-millis", 6.0)));
            this.deltaSkip = c.getBoolean("delta-skip", true);
            this.skipUnchangedTiles = c.getBoolean("skip-unchanged-tiles", false);
            this.dirtyTracking = c.getBoolean("dirty-tracking", true);
            this.dirtyFullThreshold = Math.max(0.05, Math.min(1.0, c.getDouble("dirty-full-threshold", 0.35)));
            this.parallelChunks = Math.max(1, Math.min(64, c.getInt("parallel-chunks", 4)));
            this.nmsWrites = c.getBoolean("nms-writes", true);
            this.nbtTiles = c.getBoolean("nbt-tiles", true);
            this.progressBar = c.getBoolean("progress-bar", true);
            this.regenSpeedPercent = Math.max(10, Math.min(500, c.getInt("regen-speed-percent", 100)));
        }

        public static @NotNull PerformanceSettings from(@NotNull final FileConfiguration c) {
            return new PerformanceSettings(c);
        }
    }

    // -----------------------------------------------------------------------
    // storage.yml
    // -----------------------------------------------------------------------
    public static final class StorageSettings {
        public final CompressionType compression;
        public final int compressionLevel;
        public final int maxSnapshotsPerArena;
        /** Memory budget for decoded snapshots kept in RAM (megabytes). */
        public final int cacheMemoryMb;

        public enum CompressionType {
            /** No compression — fastest, largest files. */
            NONE,
            /** GZIP — good ratio, fast. */
            GZIP,
            /** ZLIB (Deflater) — configurable level, default for snapshots. */
            ZLIB
        }

        private StorageSettings(final CompressionType compression, final int compressionLevel,
                                final int maxSnapshotsPerArena, final int cacheMemoryMb) {
            this.compression = compression;
            this.compressionLevel = compressionLevel;
            this.maxSnapshotsPerArena = maxSnapshotsPerArena;
            this.cacheMemoryMb = cacheMemoryMb;
        }

        public static @NotNull StorageSettings from(@NotNull final FileConfiguration c) {
            CompressionType ct;
            try {
                ct = CompressionType.valueOf(c.getString("compression", "ZLIB").trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                ct = CompressionType.ZLIB;
            }
            return new StorageSettings(
                    ct,
                    Math.max(-1, Math.min(9, c.getInt("compression-level", 6))),
                    Math.max(1, c.getInt("max-snapshots-per-arena", 10)),
                    Math.max(16, c.getInt("cache-memory-mb", 256))
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
