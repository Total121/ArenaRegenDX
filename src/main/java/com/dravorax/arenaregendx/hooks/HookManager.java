/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.hooks;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Coordinator for all optional third-party hooks.
 *
 * <p>Each hook is enabled only if its target plugin is present at runtime. Hooks
 * are isolated in dedicated classes so their (compile-only) dependencies are
 * loaded lazily — the jar runs cleanly on servers without PlaceholderAPI /
 * WorldEdit / WorldGuard.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class HookManager {

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final RegenManager regenManager;
    private final TPSMonitor tpsMonitor;
    private final SchedulerAdapter scheduler;

    private boolean papiEnabled;
    /** Held so it can be unregistered: persist() keeps it alive past our disable otherwise. */
    private Object papiExpansion;
    private boolean worldEditEnabled;
    private boolean worldGuardEnabled;

    public HookManager(@NotNull final ArenaRegenDXPlugin plugin,
                       @NotNull final ArenaManager arenaManager,
                       @NotNull final RegenManager regenManager,
                       @NotNull final TPSMonitor tpsMonitor,
                       @NotNull final SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.regenManager = regenManager;
        this.tpsMonitor = tpsMonitor;
        this.scheduler = scheduler;
    }

    /**
     * Attempt to enable every supported hook. Each hook logs whether it activated.
     */
    public void enableAll() {
        papiEnabled = tryHook("PlaceholderAPI", this::enablePapi);
        worldEditEnabled = tryHook("WorldEdit", this::enableWorldEdit);
        worldGuardEnabled = tryHook("WorldGuard", this::enableWorldGuard);
    }

    /**
     * Disable all hooks (unregister expansions, etc.).
     */
    public void disableAll() {
        // A persistent expansion survives our disable and would pin this plugin
        // instance (and every manager it references) in memory across reloads.
        if (papiExpansion != null) {
            try {
                ((com.dravorax.arenaregendx.hooks.papi.ArenaRegenPlaceholderExpansion) papiExpansion).unregister();
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("PlaceholderAPI unregister failed", t);
            }
            papiExpansion = null;
        }
        // WorldEdit / WorldGuard don't require explicit teardown.
    }

    public boolean isPapiEnabled() { return papiEnabled; }
    public boolean isWorldEditEnabled() { return worldEditEnabled; }
    public boolean isWorldGuardEnabled() { return worldGuardEnabled; }

    /**
     * Try to enable a single hook by plugin name. Returns true on success.
     */
    private boolean tryHook(@NotNull final String pluginName, @NotNull final Runnable enabler) {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
            plugin.getSLF4JLogger().info("Hook skipped ({} not installed).", pluginName);
            return false;
        }
        try {
            enabler.run();
            plugin.getSLF4JLogger().info("Hooked into {}.", pluginName);
            return true;
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Failed to hook into {}", pluginName, t);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Per-hook enablers (isolated methods so classes load lazily).
    // -----------------------------------------------------------------------

    private void enablePapi() {
        // Class loaded only here — safe if PAPI is absent (this method is never called).
        final var expansion = new com.dravorax.arenaregendx.hooks.papi.ArenaRegenPlaceholderExpansion(
                plugin, arenaManager, regenManager, tpsMonitor);
        if (expansion.register()) {
            papiExpansion = expansion;
        }
    }

    private void enableWorldEdit() {
        com.dravorax.arenaregendx.hooks.worldedit.WorldEditHook.enable(plugin);
    }

    private void enableWorldGuard() {
        com.dravorax.arenaregendx.hooks.worldguard.WorldGuardHook.enable(plugin, arenaManager);
    }
}
