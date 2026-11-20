/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.listeners;

import com.dravorax.arenaregendx.arena.ChangeTracker;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * Feeds {@link ChangeTracker} so restore can skip untouched voxels.
 *
 * <p>All handlers run at MONITOR with {@code ignoreCancelled}, so they only
 * record changes that actually happen. Lookups are O(arenas in that world)
 * and nothing is recorded for arenas the tracker does not currently trust.</p>
 */
public final class ChangeTrackerListener implements Listener {

    private final ChangeTracker tracker;

    public ChangeTrackerListener(@NotNull final ChangeTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NotNull final BlockBreakEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(@NotNull final BlockPlaceEvent event) {
        tracker.mark(event.getBlock().getLocation());
        tracker.mark(event.getBlockAgainst().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(@NotNull final BlockMultiPlaceEvent event) {
        for (final var state : event.getReplacedBlockStates()) {
            tracker.mark(state.getLocation());
        }
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(@NotNull final BlockBurnEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(@NotNull final BlockFadeEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(@NotNull final BlockFromToEvent event) {
        tracker.mark(event.getBlock().getLocation());
        tracker.mark(event.getToBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(@NotNull final BlockGrowEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(@NotNull final BlockSpreadEvent event) {
        tracker.mark(event.getBlock().getLocation());
        tracker.mark(event.getSource().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(@NotNull final BlockFormEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(@NotNull final BlockFertilizeEvent event) {
        tracker.mark(event.getBlock().getLocation());
        for (final var state : event.getBlocks()) {
            tracker.mark(state.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeaves(@NotNull final LeavesDecayEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoisture(@NotNull final MoistureChangeEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSponge(@NotNull final SpongeAbsorbEvent event) {
        tracker.mark(event.getBlock().getLocation());
        for (final var state : event.getBlocks()) {
            tracker.mark(state.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NotNull final PlayerInteractEvent event) {
        // Doors, levers, buttons, trapdoors, fence gates, etc. change block data.
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        final Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK, LEFT_CLICK_BLOCK, PHYSICAL -> tracker.mark(clicked.getLocation());
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(@NotNull final BlockPistonExtendEvent event) {
        tracker.mark(event.getBlock().getLocation());
        for (final Block b : event.getBlocks()) {
            tracker.mark(b.getLocation());
            tracker.mark(b.getRelative(event.getDirection()).getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(@NotNull final BlockPistonRetractEvent event) {
        tracker.mark(event.getBlock().getLocation());
        for (final Block b : event.getBlocks()) {
            tracker.mark(b.getLocation());
            tracker.mark(b.getRelative(event.getDirection().getOppositeFace()).getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBlock(@NotNull final EntityChangeBlockEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(@NotNull final PlayerBucketEmptyEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(@NotNull final PlayerBucketFillEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(@NotNull final BlockExplodeEvent event) {
        tracker.mark(event.getBlock().getLocation());
        for (final Block b : event.blockList()) tracker.mark(b.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(@NotNull final EntityExplodeEvent event) {
        for (final Block b : event.blockList()) tracker.mark(b.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(@NotNull final BlockIgniteEvent event) {
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrime(@NotNull final TNTPrimeEvent event) {
        // Priming removes the TNT block without a BlockBreakEvent.
        tracker.mark(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(@NotNull final BlockDispenseEvent event) {
        // Dispensers place water / lava / blocks in front of them.
        final var face = event.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d
                ? d.getFacing() : null;
        tracker.mark(event.getBlock().getLocation());
        if (face != null) {
            tracker.mark(event.getBlock().getRelative(face).getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull final InventoryCloseEvent event) {
        // getHolder(false) avoids copying the block state on every inventory close.
        final var holder = event.getInventory().getHolder(false);
        if (holder instanceof org.bukkit.block.BlockState state) {
            tracker.mark(state.getLocation());
        } else if (holder instanceof org.bukkit.block.DoubleChest dc) {
            if (dc.getLeftSide() instanceof org.bukkit.block.BlockState l) tracker.mark(l.getLocation());
            if (dc.getRightSide() instanceof org.bukkit.block.BlockState r) tracker.mark(r.getLocation());
        }
    }
}
