/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.listeners;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.config.Settings;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.regen.RegenMode;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Triggers automatic restores when configured world events damage an arena
 * ({@code config.yml → auto-regen}).
 *
 * <p>Only blocks that are inside an arena and outside its exclusion zones
 * count: an explosion confined to a protected spawn no longer restores the
 * whole warzone. Restores use dirty tracking when it is trusted, so a TNT
 * crater costs a few hundred block writes instead of a full pass.</p>
 */
public final class BlockInteractListener implements Listener {

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final RegenManager regenManager;
    private final Map<String, Long> lastTrigger = new ConcurrentHashMap<>();

    public BlockInteractListener(@NotNull final ArenaRegenDXPlugin plugin,
                                 @NotNull final ArenaManager arenaManager,
                                 @NotNull final RegenManager regenManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.regenManager = regenManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(@NotNull final EntityExplodeEvent event) {
        if (plugin.configManager().general().autoRegenOnExplosion) {
            handleDamage(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(@NotNull final BlockExplodeEvent event) {
        if (plugin.configManager().general().autoRegenOnExplosion) {
            handleDamage(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(@NotNull final BlockBurnEvent event) {
        if (plugin.configManager().general().autoRegenOnFire) {
            handleDamage(List.of(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(@NotNull final BlockPistonExtendEvent event) {
        if (plugin.configManager().general().autoRegenOnPiston) {
            handleDamage(event.getBlocks());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(@NotNull final BlockPistonRetractEvent event) {
        if (plugin.configManager().general().autoRegenOnPiston) {
            handleDamage(event.getBlocks());
        }
    }

    private void handleDamage(@NotNull final List<Block> blocks) {
        for (final Block b : blocks) {
            final Arena arena = arenaManager.findContaining(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            if (arena != null && !arena.isExcluded(b.getX(), b.getY(), b.getZ())) {
                triggerAutoRegen(arena);
                return;
            }
        }
    }

    private void triggerAutoRegen(@NotNull final Arena arena) {
        if (arena.latestSnapshotId() <= 0 || regenManager.isRegenerating(arena.name())) {
            return;
        }
        final Settings.PerformanceSettings perf = plugin.configManager().performance();
        final long now = System.currentTimeMillis();
        final String key = arena.name().toLowerCase(Locale.ROOT);
        final Long last = lastTrigger.get(key);
        if (last != null && now - last < perf.regenCountdownSeconds * 1000L) {
            return;
        }
        lastTrigger.put(key, now);
        final RegenMode mode = arena.asyncRegen() ? RegenMode.LAYER_BY_LAYER : RegenMode.INSTANT;
        // Restores run a tick later at the earliest, after the explosion has removed its blocks.
        regenManager.regen(arena.name(), mode, plugin.configManager().general().safeModeDefault, true)
                .whenComplete((task, err) -> {
                    if (err == null) {
                        return;
                    }
                    final Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null
                            ? err.getCause() : err;
                    if (cause instanceof IllegalStateException) {
                        // Expected races: already regenerating, cancelled, saving.
                        plugin.getSLF4JLogger().debug("Auto-regen of '{}' skipped: {}", arena.name(), cause.getMessage());
                    } else {
                        plugin.getSLF4JLogger().warn("Auto-regen of '{}' failed", arena.name(), cause);
                    }
                });
    }

    /** Drop cooldown state when an arena is deleted / renamed. */
    public void clearTrigger(@NotNull final String arenaName) {
        lastTrigger.remove(arenaName.toLowerCase(Locale.ROOT));
    }
}
