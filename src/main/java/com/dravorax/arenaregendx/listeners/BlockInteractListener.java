/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.listeners;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.config.Settings;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.regen.RegenMode;
import org.bukkit.Location;
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
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Triggers auto-regeneration when configured world events damage an arena.
 * Switches live in {@code config.yml} under {@code auto-regen}.
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
        if (!plugin.configManager().general().autoRegenOnExplosion) {
            return;
        }
        handleDamage(event.blockList(), event.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(@NotNull final BlockExplodeEvent event) {
        if (!plugin.configManager().general().autoRegenOnExplosion) {
            return;
        }
        handleDamage(event.blockList(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(@NotNull final BlockBurnEvent event) {
        if (!plugin.configManager().general().autoRegenOnFire) {
            return;
        }
        handleDamage(List.of(event.getBlock()), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(@NotNull final BlockPistonExtendEvent event) {
        if (!plugin.configManager().general().autoRegenOnPiston) {
            return;
        }
        handleDamage(event.getBlocks(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(@NotNull final BlockPistonRetractEvent event) {
        if (!plugin.configManager().general().autoRegenOnPiston) {
            return;
        }
        handleDamage(event.getBlocks(), event.getBlock().getLocation());
    }

    private void handleDamage(@NotNull final List<Block> blocks, @NotNull final Location epicenter) {
        // Prefer an arena that actually contains damaged blocks — epicenter alone
        // can sit outside the cuboid (e.g. TNT just above the roof).
        for (final Block b : blocks) {
            final Arena candidate = findArenaAt(b.getLocation());
            if (candidate != null && !candidate.isExcluded(b.getX(), b.getY(), b.getZ())) {
                triggerAutoRegen(candidate);
                return;
            }
        }
        final Arena atEpicenter = findArenaAt(epicenter);
        if (atEpicenter != null) {
            triggerAutoRegen(atEpicenter);
        }
    }

    private @Nullable Arena findArenaAt(@NotNull final Location loc) {
        if (loc.getWorld() == null) {
            return null;
        }
        return arenaManager.findContaining(loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private void triggerAutoRegen(@NotNull final Arena arena) {
        if (regenManager.isRegenerating(arena.name())) {
            return;
        }
        final long now = System.currentTimeMillis();
        final int cooldownSec = Math.max(0, plugin.configManager().performance().regenCountdownSeconds);
        final Long last = lastTrigger.get(arena.name());
        if (last != null && now - last < cooldownSec * 1000L) {
            return;
        }
        lastTrigger.put(arena.name(), now);
        plugin.getSLF4JLogger().info("Auto-regenerating arena '{}' after arena damage.", arena.name());
        final RegenMode mode = arena.asyncRegen() ? RegenMode.LAYER_BY_LAYER : RegenMode.INSTANT;
        final Settings.GeneralSettings general = plugin.configManager().general();
        regenManager.regen(arena.name(), mode, general.safeModeDefault, false)
                .whenComplete((task, err) -> {
                    if (err != null) {
                        plugin.getSLF4JLogger().warn("Auto-regen failed for '{}'", arena.name(), err);
                        if (arena.status() == com.dravorax.arenaregendx.arena.ArenaStatus.REGENERATING) {
                            arena.setStatus(com.dravorax.arenaregendx.arena.ArenaStatus.ERROR);
                        }
                    }
                });
    }

    /** Drop cooldown state when an arena is deleted/renamed. */
    public void clearTrigger(@NotNull final String arenaName) {
        lastTrigger.remove(arenaName);
    }
}
