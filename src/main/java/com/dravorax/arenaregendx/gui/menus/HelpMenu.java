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

public final class HelpMenu {

    private HelpMenu() { }

    private static final int SLOT_BACK = 49;

    public static @NotNull Inventory build(@NotNull final GuiManager gm, @NotNull final Player player) {
        final GuiManager.GuiHolder holder = new GuiManager.GuiHolder(GuiManager.GuiType.HELP, 0);
        final Inventory inv = Bukkit.createInventory(holder, 54, ColorUtil.mini(
                "<dark_gray>› <aqua>" + ColorUtil.smallCaps("help") + " <dark_gray>‹"));
        holder.inventory(inv);
        GuiItems.frame(inv);

        inv.setItem(4, GuiItems.head(GuiHeads.HELP, GuiItems.BRAND,
                java.util.List.of(ColorUtil.loreLine("commands and dust"))));

        inv.setItem(20, GuiItems.named("<gold>", "/ardx create", GuiHeads.WAND,
                "create + auto-save snapshot",
                "create [name] [time] [async]"));
        inv.setItem(22, GuiItems.named("<aqua>", "/ardx regen", GuiHeads.RESTORE,
                "restore from snapshot",
                "regen [name] [mode]",
                "instant · layer · chunk · animated"));
        inv.setItem(24, GuiItems.named("<aqua>", "/ardx gui", GuiHeads.LOGO,
                "open this menu",
                "instant regen is on the arena editor"));
        inv.setItem(30, GuiItems.named("<gold>", "gold dust", GuiHeads.DUST,
                "full region walls that restore"));
        inv.setItem(32, GuiItems.named("<red>", "red dust", GuiHeads.DELETE,
                "exclusion volume — never restored"));

        inv.setItem(SLOT_BACK, GuiItems.named("<gray>", "back", GuiHeads.BACK, "return to menu"));
        return inv;
    }

    public static void handleClick(@NotNull final GuiManager gm, @NotNull final Player player, final int slot) {
        if (slot == SLOT_BACK) {
            gm.openMain(player);
        }
    }
}
