/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.hooks.papi;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion exposing live plugin statistics.
 *
 * <p>Placeholders:</p>
 * <ul>
 *   <li>{@code %ardx_arenas%} — number of registered arenas.</li>
 *   <li>{@code %ardx_active%} — {@code true} if a regeneration is running.</li>
 *   <li>{@code %ardx_queue%} — pending regen queue depth.</li>
 *   <li>{@code %ardx_tps%} — current 1-minute TPS.</li>
 *   <li>{@code %ardx_memory%} — used memory in MB.</li>
 * </ul>
 *
 * <p>The identifier is {@code ardx}. The expansion is auto-unregistered on plugin
 * disable by PlaceholderAPI.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ArenaRegenPlaceholderExpansion extends PlaceholderExpansion {

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final RegenManager regenManager;
    private final TPSMonitor tpsMonitor;

    public ArenaRegenPlaceholderExpansion(@NotNull final ArenaRegenDXPlugin plugin,
                                          @NotNull final ArenaManager arenaManager,
                                          @NotNull final RegenManager regenManager,
                                          @NotNull final TPSMonitor tpsMonitor) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.regenManager = regenManager;
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
        return com.dravorax.arenaregendx.util.RegistryUtil.getPluginVersion(plugin);
    }

    @Override
    public boolean persist() {
        return true; // survive /papi reload
    }

    @Override
    public @Nullable String onRequest(@Nullable final OfflinePlayer player, @NotNull final String params) {
        final String key = params.toLowerCase();
        switch (key) {
            case "arenas":
                return Integer.toString(arenaManager.count());
            case "active":
                return Boolean.toString(regenManager.isRegenerating());
            case "queue":
                return Integer.toString(regenManager.queueSize());
            case "tps":
                return String.format("%.2f", tpsMonitor.currentTPS());
            case "tps_5m":
                return String.format("%.2f", tpsMonitor.tps5m());
            case "tps_15m":
                return String.format("%.2f", tpsMonitor.tps15m());
            case "ticktime":
                return String.format("%.2f", tpsMonitor.averageTickTimeMs());
            case "budget":
                return Integer.toString(tpsMonitor.adaptiveBlockBudget());
            case "memory": {
                final Runtime r = Runtime.getRuntime();
                final long used = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
                return used + "MB";
            }
            case "memory_max": {
                final long max = Runtime.getRuntime().maxMemory() / (1024 * 1024);
                return max + "MB";
            }
            default:
                return null;
        }
    }
}
