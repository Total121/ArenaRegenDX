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

public final class SettingsMenu {

    private SettingsMenu() {
    }

    private static final int SLOT_EXPLOSION = 20;
    private static final int SLOT_FIRE = 22;
    private static final int SLOT_PARTICLES = 24;
    private static final int SLOT_RELOAD = 40;
    private static final int SLOT_BACK = 49;

    public static @NotNull Inventory build(@NotNull final GuiManager gm, @NotNull final Player player) {
        final GuiManager.GuiHolder holder = new GuiManager.GuiHolder(GuiManager.GuiType.SETTINGS, 0);
        final Inventory inv = Bukkit.createInventory(holder, 54, ColorUtil.mini(
                "<dark_gray>› <aqua>" + ColorUtil.smallCaps("settings") + " <dark_gray>‹"));
        holder.inventory(inv);
        GuiItems.frame(inv);

        final var general = gm.configManager().general();
        final var particles = gm.configManager().particles();

        inv.setItem(4, GuiItems.head(GuiHeads.SETTINGS,
                ColorUtil.named("<aqua>", "settings"),
                java.util.List.of(ColorUtil.loreLine("click a head — it saves to disk"))));

        inv.setItem(SLOT_EXPLOSION, GuiItems.named(
                general.autoRegenOnExplosion ? "<green>" : "<red>",
                "explosion auto regen",
                GuiHeads.EXPLOSION,
                general.autoRegenOnExplosion ? "on  ·  click to disable" : "off  ·  click to enable",
                "writes config.yml"));
        inv.setItem(SLOT_FIRE, GuiItems.named(
                general.autoRegenOnFire ? "<green>" : "<red>",
                "fire auto regen",
                GuiHeads.FIRE,
                general.autoRegenOnFire ? "on  ·  click to disable" : "off  ·  click to enable"));
        inv.setItem(SLOT_PARTICLES, GuiItems.named(
                particles.enabled ? "<green>" : "<red>",
                "region dust",
                GuiHeads.DUST,
                particles.enabled ? "on  ·  full wall overlay" : "off"));
        inv.setItem(SLOT_RELOAD, GuiItems.named("<gold>", "reload yaml", GuiHeads.RELOAD,
                "re-read all config files"));
        inv.setItem(SLOT_BACK, GuiItems.named("<gray>", "back", GuiHeads.BACK, "return to menu"));
        return inv;
    }

    public static void handleClick(@NotNull final GuiManager gm, @NotNull final Player player, final int slot) {
        if (slot == SLOT_BACK) {
            gm.openMain(player);
            return;
        }
        if (!player.hasPermission("arenaregendx.reload") && !player.hasPermission("arenaregendx.admin")) {
            player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
            return;
        }
        if (slot == SLOT_EXPLOSION) {
            final boolean next = !gm.configManager().general().autoRegenOnExplosion;
            gm.configManager().setAutoRegenOnExplosion(next);
            player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                    + ColorUtil.smallCaps("explosion auto-regen ")
                    + "<white>" + ColorUtil.smallCaps(next ? "on" : "off")));
            gm.openSettings(player);
            return;
        }
        if (slot == SLOT_FIRE) {
            final boolean next = !gm.configManager().general().autoRegenOnFire;
            gm.configManager().setAutoRegenOnFire(next);
            player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                    + ColorUtil.smallCaps("fire auto-regen ")
                    + "<white>" + ColorUtil.smallCaps(next ? "on" : "off")));
            gm.openSettings(player);
            return;
        }
        if (slot == SLOT_PARTICLES) {
            final boolean next = !gm.configManager().particles().enabled;
            gm.configManager().setParticlesEnabled(next);
            gm.plugin().selectionVisualizer().restart();
            player.sendMessage(ColorUtil.mini("<dark_gray>› <aqua>"
                    + ColorUtil.smallCaps("region dust ")
                    + "<white>" + ColorUtil.smallCaps(next ? "on" : "off")));
            gm.openSettings(player);
            return;
        }
        if (slot == SLOT_RELOAD) {
            gm.configManager().reload();
            gm.plugin().applyRuntimeSettings();
            final var p = gm.configManager().performance();
            gm.plugin().tpsMonitor().configure(p.maxBlockBudget, p.tpsFull, p.tpsThrottle, p.tpsCrawl, p.maxTickMillis);
            gm.plugin().selectionVisualizer().restart();
            player.sendMessage(ColorUtil.mini("<dark_gray>› <green>"
                    + ColorUtil.smallCaps("configuration reloaded")));
            gm.openSettings(player);
        }
    }
}
