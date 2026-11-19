/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.gui.menus;

import com.dravorax.arenaregendx.gui.GuiHeads;
import com.dravorax.arenaregendx.gui.GuiItems;
import com.dravorax.arenaregendx.gui.GuiManager;
import com.dravorax.arenaregendx.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * 54-slot hub. Frame only — the middle is empty so it does not feel packed.
 *
 * <pre>
 *   [logo]
 *     arenas     create     restore
 *
 *     stats      settings   help
 *                 back
 * </pre>
 */
public final class MainMenu {

    private MainMenu() { }

    private static final int SLOT_ARENAS = 20;
    private static final int SLOT_CREATE = 22;
    private static final int SLOT_RESTORE = 24;
    private static final int SLOT_PERFORMANCE = 38;
    private static final int SLOT_SETTINGS = 40;
    private static final int SLOT_HELP = 42;
    private static final int SLOT_STATUS = 45;
    private static final int SLOT_BACK = 49;

    public static @NotNull Inventory build(@NotNull final GuiManager gm, @NotNull final Player player) {
        final GuiManager.GuiHolder holder = new GuiManager.GuiHolder(GuiManager.GuiType.MAIN, 0);
        final Inventory inv = Bukkit.createInventory(holder, 54, ColorUtil.mini(
                "<dark_gray>› " + GuiItems.BRAND + " <dark_gray>‹"));
        holder.inventory(inv);

        GuiItems.frame(inv);

        inv.setItem(4, GuiItems.head(GuiHeads.LOGO, GuiItems.BRAND, java.util.List.of(
                ColorUtil.loreLine("snapshot  ·  restore  ·  dust"),
                ColorUtil.loreLine("paper & folia  1.21+")
        )));

        inv.setItem(SLOT_ARENAS, GuiItems.named("<aqua>", "arenas", GuiHeads.ARENAS,
                "browse every saved region",
                "click an arena to edit it"));
        inv.setItem(SLOT_CREATE, GuiItems.named("<gold>", "create", GuiHeads.CREATE,
                "take the selection wand",
                "left = pos 1    right = pos 2"));
        inv.setItem(SLOT_RESTORE, GuiItems.named("<aqua>", "restore", GuiHeads.RESTORE,
                "open an arena, then pick a mode",
                "instant lives on the editor"));
        inv.setItem(SLOT_PERFORMANCE, GuiItems.named("<aqua>", "performance", GuiHeads.PERFORMANCE,
                "tps, queue, memory"));
        inv.setItem(SLOT_SETTINGS, GuiItems.named("<gold>", "settings", GuiHeads.SETTINGS,
                "explosion auto-regen",
                "fire, dust, reload"));
        inv.setItem(SLOT_HELP, GuiItems.named("<aqua>", "help", GuiHeads.HELP,
                "commands and dust colours"));
        inv.setItem(SLOT_STATUS, GuiItems.named("<gray>", "status", GuiHeads.STATUS,
                "print live stats in chat"));
        inv.setItem(SLOT_BACK, GuiItems.named("<gray>", "close", GuiHeads.BACK,
                "close this menu"));

        return inv;
    }

    public static void handleClick(@NotNull final GuiManager gm, @NotNull final Player player, final int slot) {
        switch (slot) {
            case SLOT_ARENAS, SLOT_RESTORE -> gm.openArenaManager(player, 0);
            case SLOT_CREATE -> {
                player.closeInventory();
                player.performCommand("ardx wand");
            }
            case SLOT_PERFORMANCE -> gm.openPerformance(player);
            case SLOT_SETTINGS -> {
                if (!player.hasPermission("arenaregendx.reload") && !player.hasPermission("arenaregendx.admin")) {
                    player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
                    return;
                }
                gm.openSettings(player);
            }
            case SLOT_HELP -> gm.openHelp(player);
            case SLOT_STATUS -> {
                player.closeInventory();
                player.performCommand("ardx status");
            }
            case SLOT_BACK -> player.closeInventory();
            default -> { }
        }
    }
}
