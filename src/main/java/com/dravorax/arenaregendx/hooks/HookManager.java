/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.hooks;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.hooks.papi.ArenaRegenPlaceholderExpansion;
import com.dravorax.arenaregendx.hooks.worldedit.WorldEditHook;
import com.dravorax.arenaregendx.hooks.worldguard.WorldGuardHook;
import com.dravorax.arenaregendx.regen.AutoResetManager;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Soft-dependency hooks (PlaceholderAPI, WorldEdit, WorldGuard).
 *
 * <p>Each hook's classes are only loaded after the target plugin is confirmed
 * present and enabled. All hooks are torn down on disable, so a plugin reload
 * never leaves a stale expansion holding the old class loader.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class HookManager {

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final RegenManager regenManager;
    private final AutoResetManager autoResetManager;
    private final TPSMonitor tpsMonitor;

    private boolean papiEnabled;
    private boolean worldEditEnabled;
    private boolean worldGuardEnabled;
    private Object papiExpansion;

    public HookManager(@NotNull final ArenaRegenDXPlugin plugin,
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

    public void enableAll() {
        papiEnabled = tryHook("PlaceholderAPI", this::enablePapi);
        worldEditEnabled = tryHook("WorldEdit", WorldEditHook::enable);
        worldGuardEnabled = tryHook("WorldGuard", WorldGuardHook::enable);
    }

    public void disableAll() {
        if (papiExpansion != null) {
            try {
                ((ArenaRegenPlaceholderExpansion) papiExpansion).unregister();
            } catch (final Throwable t) {
                plugin.getSLF4JLogger().debug("PlaceholderAPI unregister failed", t);
            }
            papiExpansion = null;
        }
        if (worldEditEnabled) {
            WorldEditHook.disable();
        }
        if (worldGuardEnabled) {
            WorldGuardHook.disable();
        }
        papiEnabled = worldEditEnabled = worldGuardEnabled = false;
    }

    public boolean isPapiEnabled() { return papiEnabled; }
    public boolean isWorldEditEnabled() { return worldEditEnabled; }
    public boolean isWorldGuardEnabled() { return worldGuardEnabled; }

    private boolean tryHook(@NotNull final String pluginName, @NotNull final Runnable enabler) {
        final var target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null || !target.isEnabled()) {
            return false;
        }
        try {
            enabler.run();
            plugin.getSLF4JLogger().info("Hooked into {} {}.", pluginName, target.getPluginMeta().getVersion());
            return true;
        } catch (final Throwable t) {
            plugin.getSLF4JLogger().warn("Failed to hook into {}", pluginName, t);
            return false;
        }
    }

    private void enablePapi() {
        final ArenaRegenPlaceholderExpansion expansion = new ArenaRegenPlaceholderExpansion(
                plugin, arenaManager, regenManager, autoResetManager, tpsMonitor);
        if (!expansion.register()) {
            throw new IllegalStateException("PlaceholderAPI refused the 'ardx' expansion");
        }
        papiExpansion = expansion;
    }
}
