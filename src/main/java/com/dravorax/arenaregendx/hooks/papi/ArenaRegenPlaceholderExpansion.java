/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.hooks.papi;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.regen.AutoResetManager;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * PlaceholderAPI expansion ({@code %ardx_...%}).
 *
 * <h2>Global</h2>
 * <ul>
 *   <li>{@code arenas}, {@code active}, {@code queue}, {@code jobs}</li>
 *   <li>{@code tps}, {@code tps_5m}, {@code tps_15m}, {@code ticktime}, {@code budget}</li>
 *   <li>{@code memory}, {@code memory_max}</li>
 * </ul>
 * <h2>Per arena</h2>
 * <ul>
 *   <li>{@code status_<arena>} — empty / ready / regenerating / saving / error</li>
 *   <li>{@code progress_<arena>} — 0-100 while restoring, otherwise empty</li>
 *   <li>{@code reset_<arena>} — seconds until the next auto reset (or -1)</li>
 *   <li>{@code reset_formatted_<arena>} — the same as {@code mm:ss}</li>
 *   <li>{@code regenerating_<arena>} — true / false</li>
 *   <li>{@code size_<arena>}, {@code blocks_<arena>}, {@code snapshot_<arena>}</li>
 * </ul>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ArenaRegenPlaceholderExpansion extends PlaceholderExpansion {

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final RegenManager regenManager;
    private final AutoResetManager autoResetManager;
    private final TPSMonitor tpsMonitor;

    public ArenaRegenPlaceholderExpansion(@NotNull final ArenaRegenDXPlugin plugin,
                                          @NotNull final ArenaManager arenaManager,
                                          @NotNull final RegenManager regenManager,
                                          @NotNull final AutoResetManager autoResetManager,
                                          @NotNull final TPSMonitor tpsMonitor) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.regenManager = regenManager;
        this.autoResetManager = autoResetManager;
        this.tpsMonitor = tpsMonitor;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ardx";
    }

    @Override
    public @NotNull String getAuthor() {
        return "DravoraX";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // survive /papi reload; unregistered explicitly on plugin disable
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return List.of("%ardx_arenas%", "%ardx_active%", "%ardx_queue%", "%ardx_jobs%", "%ardx_tps%",
                "%ardx_tps_5m%", "%ardx_tps_15m%", "%ardx_ticktime%", "%ardx_budget%", "%ardx_memory%",
                "%ardx_memory_max%", "%ardx_status_<arena>%", "%ardx_progress_<arena>%", "%ardx_reset_<arena>%",
                "%ardx_reset_formatted_<arena>%", "%ardx_regenerating_<arena>%", "%ardx_size_<arena>%",
                "%ardx_blocks_<arena>%", "%ardx_snapshot_<arena>%");
    }

    @Override
    public @Nullable String onRequest(@Nullable final OfflinePlayer player, @NotNull final String params) {
        final String key = params.toLowerCase(Locale.ROOT);
        switch (key) {
            case "arenas": return Integer.toString(arenaManager.count());
            case "active": return Boolean.toString(regenManager.isRegenerating());
            case "queue": return Integer.toString(regenManager.queueSize());
            case "jobs": return Integer.toString(regenManager.activeCount());
            case "tps": return format(tpsMonitor.currentTPS());
            case "tps_5m": return format(tpsMonitor.tps5m());
            case "tps_15m": return format(tpsMonitor.tps15m());
            case "ticktime": return format(tpsMonitor.averageTickTimeMs());
            case "budget": return Integer.toString(tpsMonitor.adaptiveBlockBudget());
            case "memory": {
                final Runtime r = Runtime.getRuntime();
                return ((r.totalMemory() - r.freeMemory()) / (1024 * 1024)) + "MB";
            }
            case "memory_max": return (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB";
            default: return arenaPlaceholder(key);
        }
    }

    private @Nullable String arenaPlaceholder(@NotNull final String key) {
        // Longest prefixes first: "reset_formatted_" before "reset_".
        for (final String prefix : new String[]{"reset_formatted_", "regenerating_", "progress_", "snapshot_",
                "status_", "blocks_", "reset_", "size_"}) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            final Arena arena = arenaManager.getArena(key.substring(prefix.length()));
            if (arena == null) {
                return "";
            }
            return switch (prefix) {
                case "status_" -> arena.status().name().toLowerCase(Locale.ROOT);
                case "progress_" -> {
                    final float p = regenManager.progress(arena.name());
                    yield p < 0f ? "" : Integer.toString(Math.round(p * 100f));
                }
                case "reset_" -> Long.toString(autoResetManager.getRemaining(arena.name()));
                case "reset_formatted_" -> {
                    final long s = autoResetManager.getRemaining(arena.name());
                    yield s < 0 ? "" : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
                }
                case "regenerating_" -> Boolean.toString(regenManager.isRegenerating(arena.name()));
                case "size_" -> arena.sizeX() + "x" + arena.sizeY() + "x" + arena.sizeZ();
                case "blocks_" -> Integer.toString(arena.blockCount());
                case "snapshot_" -> Long.toString(arena.latestSnapshotId());
                default -> null;
            };
        }
        return null;
    }

    private static @NotNull String format(final double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
