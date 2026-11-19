/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.gui.menus;

import com.dravorax.arenaregendx.gui.GuiHeads;
import com.dravorax.arenaregendx.gui.GuiItems;
import com.dravorax.arenaregendx.gui.GuiManager;
import com.dravorax.arenaregendx.regen.RegenSpeed;
import com.dravorax.arenaregendx.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

public final class PerformanceMenu {

    private PerformanceMenu() {
    }

    private static final int SLOT_SPEED_MINUS = 28;
    private static final int SLOT_SPEED = 31;
    private static final int SLOT_SPEED_PLUS = 34;
    private static final int SLOT_BACK = 49;

    public static @NotNull Inventory build(@NotNull final GuiManager gm, @NotNull final Player player) {
        final GuiManager.GuiHolder holder = new GuiManager.GuiHolder(GuiManager.GuiType.PERFORMANCE, 0);
        final Inventory inv = Bukkit.createInventory(holder, 54, ColorUtil.mini(
                "<dark_gray>› <aqua>" + ColorUtil.smallCaps("performance") + " <dark_gray>‹"));
        holder.inventory(inv);
        GuiItems.frame(inv);

        final var tps = gm.tpsMonitor();
        final var perf = gm.configManager().performance();
        final var runtime = Runtime.getRuntime();
        final long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        final long maxMb = runtime.maxMemory() / (1024 * 1024);
        final int speed = perf.regenSpeedPercent;
        final int effectiveBudget = Math.max(64, (int) (tps.adaptiveBlockBudget() * (speed / 100.0)));

        inv.setItem(4, GuiItems.head(GuiHeads.PERFORMANCE,
                ColorUtil.named("<aqua>", "performance"),
                java.util.List.of(
                        ColorUtil.loreLine("re-open this menu to refresh"),
                        ColorUtil.loreLine("global speed  " + RegenSpeed.label(speed))
                )));

        inv.setItem(20, GuiItems.named("<aqua>", "tps", GuiHeads.TPS,
                "now  " + String.format("%.2f", tps.currentTPS()),
                "5m  " + String.format("%.2f", tps.tps5m()),
                "15m  " + String.format("%.2f", tps.tps15m())));
        inv.setItem(22, GuiItems.named("<gold>", "budget", GuiHeads.QUEUE,
                "adaptive  " + tps.adaptiveBlockBudget() + " / tick",
                "with speed  " + effectiveBudget + " / tick",
                "avg tick  " + String.format("%.2f", tps.averageTickTimeMs()) + "ms"));
        inv.setItem(24, GuiItems.named("<aqua>", "queue", GuiHeads.QUEUE,
                "queued  " + gm.regenManager().queueSize(),
                "running  " + (gm.regenManager().isRegenerating() ? "yes" : "no"),
                "jobs  " + gm.regenManager().activeCount()));

        inv.setItem(SLOT_SPEED_MINUS, GuiItems.named("<red>", "slower", GuiHeads.MINUS,
                "global restore speed -20%",
                "current  " + speed + "%"));
        inv.setItem(SLOT_SPEED, GuiItems.named("<gold>", "regen speed", GuiHeads.INSTANT,
                "global  " + RegenSpeed.label(speed),
                "click to cycle slow/normal/fast/max",
                "effective budget  " + effectiveBudget));
        inv.setItem(SLOT_SPEED_PLUS, GuiItems.named("<green>", "faster", GuiHeads.PLUS,
                "global restore speed +20%",
                "current  " + speed + "%"));

        inv.setItem(39, GuiItems.named("<aqua>", "arenas", GuiHeads.ARENAS,
                "loaded  " + gm.arenaManager().count()));
        inv.setItem(40, GuiItems.named("<gold>", "memory", GuiHeads.MEMORY,
                "used  " + usedMb + " mb",
                "max  " + maxMb + " mb"));
        inv.setItem(41, GuiItems.named("<gray>", "runtime", GuiHeads.INFO,
                gm.plugin().scheduler().implementationName(),
                "java  " + System.getProperty("java.version")));

        inv.setItem(SLOT_BACK, GuiItems.named("<gray>", "back", GuiHeads.BACK, "return to menu"));
        return inv;
    }

    public static void handleClick(@NotNull final GuiManager gm, @NotNull final Player player, final int slot) {
        if (slot == SLOT_BACK) {
            gm.openMain(player);
            return;
        }
        if (!player.hasPermission("arenaregendx.admin") && !player.hasPermission("arenaregendx.reload")) {
            player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
            return;
        }
        final int current = gm.configManager().performance().regenSpeedPercent;
        if (slot == SLOT_SPEED_MINUS) {
            gm.configManager().setRegenSpeedPercent(Math.max(10, current - 20));
            player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                    + ColorUtil.smallCaps("global speed ")
                    + "<white>" + gm.configManager().performance().regenSpeedPercent + "%"));
            gm.openPerformance(player);
            return;
        }
        if (slot == SLOT_SPEED_PLUS) {
            gm.configManager().setRegenSpeedPercent(Math.min(500, current + 20));
            player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                    + ColorUtil.smallCaps("global speed ")
                    + "<white>" + gm.configManager().performance().regenSpeedPercent + "%"));
            gm.openPerformance(player);
            return;
        }
        if (slot == SLOT_SPEED) {
            gm.configManager().setRegenSpeedPercent(RegenSpeed.nextGlobalPreset(current));
            player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                    + ColorUtil.smallCaps("global speed ")
                    + "<white>" + RegenSpeed.label(gm.configManager().performance().regenSpeedPercent)));
            gm.openPerformance(player);
        }
    }
}
