/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.listeners;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.ArenaSelectionManager;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.config.Settings;
import com.dravorax.arenaregendx.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

/**
 * Golden-axe wand. Identified by persistent data, not display name.
 *
 * @author DravoraX
 */
public final class SelectionWandListener implements Listener {

    private static final String WAND_DISPLAY_NAME =
            "<dark_gray>[<gradient:#5eead4:#fbbf24>arena regen dx</gradient> <gray>wand<dark_gray>]";

    public static final String WAND_KEY_NAME = "selection_wand";

    private final ArenaRegenDXPlugin plugin;
    private final ArenaSelectionManager selectionManager;
    private final ConfigManager configManager;
    private final NamespacedKey wandKey;

    public SelectionWandListener(@NotNull final ArenaRegenDXPlugin plugin,
                                 @NotNull final ArenaSelectionManager selectionManager,
                                 @NotNull final ConfigManager configManager) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.configManager = configManager;
        this.wandKey = new NamespacedKey(plugin, WAND_KEY_NAME);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(@NotNull final PlayerInteractEvent event) {
        // The event fires once per hand; only the main hand holds the wand.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player player = event.getPlayer();
        final ItemStack item = player.getInventory().getItemInMainHand();
        if (!isWand(item) || !configManager.general().wandEnabled) {
            return;
        }
        if (!player.hasPermission("arenaregendx.wand") && !player.hasPermission("arenaregendx.admin")) {
            return;
        }
        final Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.LEFT_CLICK_AIR && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            clicked = player.getTargetBlockExact(8);
        }
        if (clicked == null) {
            return;
        }
        event.setCancelled(true);

        final Settings.SoundSettings sounds = configManager.sounds();
        final boolean pos1 = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;
        final var messages = plugin.messages();
        if (pos1) {
            selectionManager.setPos1(player, clicked.getLocation());
            messages.actionBar(player, "selection.pos1", "x", clicked.getX(), "y", clicked.getY(), "z", clicked.getZ());
            playSound(player, sounds.selectPos1);
        } else {
            selectionManager.setPos2(player, clicked.getLocation());
            messages.actionBar(player, "selection.pos2", "x", clicked.getX(), "y", clicked.getY(), "z", clicked.getZ());
            playSound(player, sounds.selectPos2);
        }
        final var sel = selectionManager.getSelection(player);
        if (sel != null && sel.isComplete()) {
            final var min = sel.min();
            final var max = sel.max();
            if (min != null && max != null) {
                final int dx = max.getBlockX() - min.getBlockX() + 1;
                final int dy = max.getBlockY() - min.getBlockY() + 1;
                final int dz = max.getBlockZ() - min.getBlockZ() + 1;
                messages.send(player, "selection.size", "size", dx + "x" + dy + "x" + dz,
                        "blocks", (long) dx * dy * dz);
            }
        }
    }

    private boolean isWand(@NotNull final ItemStack item) {
        if (item.getType() != Material.GOLDEN_AXE) {
            return false;
        }
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        final Byte mark = meta.getPersistentDataContainer().get(wandKey, PersistentDataType.BYTE);
        if (mark != null && mark == (byte) 1) {
            return true;
        }
        if (!meta.hasDisplayName() || meta.displayName() == null) {
            return false;
        }
        final String plain = ColorUtil.plain(ColorUtil.legacy(meta.displayName())).toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return plain.contains("arenaregendx") && plain.contains("wand");
    }

    private void playSound(@NotNull final Player player, @NotNull final String soundName) {
        final Settings.SoundSettings sounds = configManager.sounds();
        if (!sounds.enabled) return;
        final Sound s = com.dravorax.arenaregendx.util.RegistryUtil.soundFromString(soundName);
        if (s != null) {
            player.playSound(player.getLocation(), s, sounds.volume, sounds.pitch);
        }
    }

    public static @NotNull String wandDisplayName() {
        return WAND_DISPLAY_NAME;
    }

    public static @NotNull ItemStack createWand(@NotNull final ArenaRegenDXPlugin plugin) {
        final ItemStack wand = new ItemStack(Material.GOLDEN_AXE);
        final ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.displayName(ColorUtil.mini(WAND_DISPLAY_NAME));
            meta.lore(java.util.List.of(
                    ColorUtil.mini("<green>Left click <dark_gray>» <white>position 1"),
                    ColorUtil.mini("<aqua>Right click <dark_gray>» <white>position 2"),
                    ColorUtil.mini("<gray>Gold dust box shows the selected volume."),
                    ColorUtil.mini("<dark_gray>ArenaRegenDX <gray>· <yellow>DravoraX")
            ));
            meta.setUnbreakable(true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                    org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, WAND_KEY_NAME), PersistentDataType.BYTE, (byte) 1);
            wand.setItemMeta(meta);
        }
        return wand;
    }
}
