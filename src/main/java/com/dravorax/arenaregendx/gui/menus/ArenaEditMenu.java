/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.gui.menus;

import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.gui.GuiHeads;
import com.dravorax.arenaregendx.gui.GuiItems;
import com.dravorax.arenaregendx.gui.GuiManager;
import com.dravorax.arenaregendx.regen.RegenMode;
import com.dravorax.arenaregendx.regen.RegenSpeed;
import com.dravorax.arenaregendx.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Editor with real restore modes. Instant is the first head.
 */
public final class ArenaEditMenu {

    private ArenaEditMenu() { }

    private static final int SLOT_INSTANT = 19;
    private static final int SLOT_LAYER = 21;
    private static final int SLOT_CHUNK = 23;
    private static final int SLOT_ANIMATED = 25;
    private static final int SLOT_SPAWN = 28;
    private static final int SLOT_AUTO_PLUS = 30;
    private static final int SLOT_SPEED = 31;
    private static final int SLOT_AUTO_MINUS = 32;
    private static final int SLOT_ASYNC = 34;
    private static final int SLOT_SAVE = 37;
    private static final int SLOT_PREVIEW = 39;
    private static final int SLOT_DELETE = 43;
    private static final int SLOT_BACK = 49;

    public static @NotNull Inventory build(@NotNull final GuiManager gm, @NotNull final Player player,
                                           @NotNull final String arenaName) {
        final Arena arena = gm.arenaManager().getArena(arenaName);
        final GuiManager.GuiHolder holder = new GuiManager.GuiHolder(
                GuiManager.GuiType.ARENA_EDIT, 0, arenaName);
        final Inventory inv = Bukkit.createInventory(holder, 54, ColorUtil.mini(
                "<dark_gray>› <aqua>" + ColorUtil.smallCaps(arenaName) + " <dark_gray>‹"));
        holder.inventory(inv);
        GuiItems.frame(inv);

        if (arena == null) {
            inv.setItem(22, GuiItems.named("<red>", "not found", GuiHeads.ARENA_ERROR,
                    "this arena was deleted"));
            inv.setItem(SLOT_BACK, GuiItems.named("<gray>", "back", GuiHeads.BACK, "return to arenas"));
            return inv;
        }

        final int effectiveSpeed = RegenSpeed.resolve(
                arena.regenSpeedPercent(), gm.configManager().performance().regenSpeedPercent);
        inv.setItem(4, GuiItems.head(GuiHeads.INFO,
                ColorUtil.named("<aqua>", arena.name()),
                List.of(
                        ColorUtil.loreLine("size  " + arena.sizeX() + " × " + arena.sizeY() + " × " + arena.sizeZ()),
                        ColorUtil.loreLine("blocks  " + arena.blockCount()),
                        ColorUtil.loreLine("snapshot  #" + arena.latestSnapshotId()),
                        ColorUtil.loreLine("status  " + arena.status().name().toLowerCase(java.util.Locale.ROOT)),
                        ColorUtil.loreLine("auto-reset  " + (arena.autoResetInterval() > 0 ? arena.autoResetInterval() + "s" : "off")),
                        ColorUtil.loreLine("async  " + (arena.asyncRegen() ? "on" : "off")),
                        ColorUtil.loreLine("speed  " + RegenSpeed.label(arena.regenSpeedPercent())
                                + " → " + effectiveSpeed + "%"),
                        ColorUtil.loreLine("spawn  " + (arena.hasSpawn() ? "set" : "none"))
                )));

        inv.setItem(SLOT_INSTANT, GuiItems.named("<gold>", "instant regen", GuiHeads.INSTANT,
                "full snapshot  ·  fastest mode",
                "uses arena speed  " + effectiveSpeed + "%",
                "click to restore now"));
        inv.setItem(SLOT_LAYER, GuiItems.named("<aqua>", "layer by layer", GuiHeads.LAYER,
                "builds from the floor up",
                "click to restore now"));
        inv.setItem(SLOT_CHUNK, GuiItems.named("<aqua>", "chunk by chunk", GuiHeads.CHUNK,
                "one chunk at a time",
                "click to restore now"));
        inv.setItem(SLOT_ANIMATED, GuiItems.named("<gold>", "animated", GuiHeads.ANIMATED,
                "expands from the centre",
                "click to restore now"));

        inv.setItem(SLOT_SPAWN, GuiItems.named("<aqua>", "set spawn", GuiHeads.SPAWN,
                "use your current location"));
        inv.setItem(SLOT_AUTO_PLUS, GuiItems.named("<green>", "+ auto reset", GuiHeads.PLUS,
                "add 60 seconds",
                arena.autoResetInterval() > 0 ? arena.autoResetInterval() + "s" : "off"));
        inv.setItem(SLOT_SPEED, GuiItems.named("<gold>", "regen speed", GuiHeads.INSTANT,
                "arena  " + RegenSpeed.label(arena.regenSpeedPercent()),
                "effective  " + effectiveSpeed + "%",
                "click to cycle inherit/slow/normal/fast/max"));
        inv.setItem(SLOT_AUTO_MINUS, GuiItems.named("<red>", "- auto reset", GuiHeads.MINUS,
                "remove 60 seconds",
                arena.autoResetInterval() > 0 ? arena.autoResetInterval() + "s" : "off"));
        inv.setItem(SLOT_ASYNC, GuiItems.named(
                arena.asyncRegen() ? "<green>" : "<red>",
                arena.asyncRegen() ? "async on" : "async off",
                arena.asyncRegen() ? GuiHeads.ON : GuiHeads.OFF,
                "auto / explosion style",
                "on = layer    off = instant"));
        inv.setItem(SLOT_SAVE, GuiItems.named("<gold>", "save snapshot", GuiHeads.SAVE,
                "capture the live region"));
        inv.setItem(SLOT_PREVIEW, GuiItems.named("<aqua>", "preview dust", GuiHeads.PREVIEW,
                "gold = walls that restore",
                "red = exclusion volume"));
        final boolean confirm = gm.isDeletePending(player, arena.name());
        inv.setItem(SLOT_DELETE, GuiItems.named("<red>",
                confirm ? "click again" : "delete arena",
                GuiHeads.DELETE,
                confirm ? "this cannot be undone" : "second click required"));
        inv.setItem(SLOT_BACK, GuiItems.named("<gray>", "back", GuiHeads.BACK, "return to arenas"));
        return inv;
    }

    public static void handleClick(@NotNull final GuiManager gm, @NotNull final Player player,
                                   final int slot, @NotNull final String arenaName) {
        final Arena arena = gm.arenaManager().getArena(arenaName);
        if (arena == null) {
            if (slot == SLOT_BACK) gm.openArenaManager(player, 0);
            return;
        }

        switch (slot) {
            case SLOT_BACK -> gm.openArenaManager(player, 0);
            case SLOT_INSTANT -> startRegen(gm, player, arena, RegenMode.INSTANT);
            case SLOT_LAYER -> startRegen(gm, player, arena, RegenMode.LAYER_BY_LAYER);
            case SLOT_CHUNK -> startRegen(gm, player, arena, RegenMode.CHUNK_BY_CHUNK);
            case SLOT_ANIMATED -> startRegen(gm, player, arena, RegenMode.ANIMATED);
            case SLOT_SPAWN -> {
                player.closeInventory();
                player.performCommand("ardx setspawn " + arena.name());
            }
            case SLOT_AUTO_PLUS -> {
                if (!player.hasPermission("arenaregendx.admin")) {
                    player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
                    return;
                }
                final int newTime = arena.autoResetInterval() + 60;
                arena.setAutoResetInterval(newTime);
                gm.arenaManager().saveArena(arena);
                gm.plugin().autoResetManager().startCountdown(arena);
                player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                        + ColorUtil.smallCaps("auto-reset ") + "<white>" + newTime + "s"));
                gm.openArenaEdit(player, arenaName);
            }
            case SLOT_SPEED -> {
                if (!player.hasPermission("arenaregendx.admin") && !player.hasPermission("arenaregendx.regen")) {
                    player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
                    return;
                }
                arena.setRegenSpeedPercent(RegenSpeed.nextArenaPreset(arena.regenSpeedPercent()));
                gm.arenaManager().saveArena(arena);
                final int effective = RegenSpeed.resolve(
                        arena.regenSpeedPercent(), gm.configManager().performance().regenSpeedPercent);
                player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                        + ColorUtil.smallCaps("speed ")
                        + "<white>" + RegenSpeed.label(arena.regenSpeedPercent())
                        + " <gray>(" + effective + "%)"));
                gm.openArenaEdit(player, arenaName);
            }
            case SLOT_AUTO_MINUS -> {
                if (!player.hasPermission("arenaregendx.admin")) {
                    player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
                    return;
                }
                final int newTime = Math.max(0, arena.autoResetInterval() - 60);
                arena.setAutoResetInterval(newTime);
                gm.arenaManager().saveArena(arena);
                gm.plugin().autoResetManager().startCountdown(arena); // stops it when 0
                player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                        + ColorUtil.smallCaps("auto-reset ")
                        + "<white>" + (newTime > 0 ? newTime + "s" : ColorUtil.smallCaps("off"))));
                gm.openArenaEdit(player, arenaName);
            }
            case SLOT_ASYNC -> {
                if (!player.hasPermission("arenaregendx.admin")) {
                    player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
                    return;
                }
                arena.setAsyncRegen(!arena.asyncRegen());
                gm.arenaManager().saveArena(arena);
                player.sendMessage(ColorUtil.mini("<dark_gray>› <aqua>"
                        + ColorUtil.smallCaps("async ")
                        + "<white>" + ColorUtil.smallCaps(arena.asyncRegen() ? "on" : "off")));
                gm.openArenaEdit(player, arenaName);
            }
            case SLOT_SAVE -> {
                if (!player.hasPermission("arenaregendx.save") && !player.hasPermission("arenaregendx.admin")) {
                    player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
                    return;
                }
                player.closeInventory();
                player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                        + ColorUtil.smallCaps("saving ") + "<aqua>" + arena.name()));
                gm.arenaManager().captureSnapshot(arena).whenComplete((meta, err) -> {
                    final net.kyori.adventure.text.Component msg = err == null
                            ? ColorUtil.mini("<dark_gray>› <green>" + ColorUtil.smallCaps("saved ") + "<aqua>#" + meta.snapshotId())
                            : ColorUtil.mini("<red>" + ColorUtil.smallCaps("save failed: ") + "<gray>"
                            + ColorUtil.escape(rootMessage(err)));
                    gm.plugin().messages().deliver(player, msg);
                });
            }
            case SLOT_PREVIEW -> {
                player.closeInventory();
                gm.plugin().selectionVisualizer().showArenaPreview(player, arena);
                player.sendMessage(ColorUtil.mini("<dark_gray>› <aqua>"
                        + ColorUtil.smallCaps("showing full wall dust")));
            }
            case SLOT_DELETE -> {
                if (!player.hasPermission("arenaregendx.delete") && !player.hasPermission("arenaregendx.admin")) {
                    player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
                    return;
                }
                if (gm.confirmDelete(player, arena.name())) {
                    player.closeInventory();
                    if (gm.arenaManager().deleteArena(arena.name())) {
                        player.sendMessage(ColorUtil.mini("<dark_gray>› <red>"
                                + ColorUtil.smallCaps("deleted ") + "<aqua>" + arena.name()));
                    }
                } else {
                    player.sendMessage(ColorUtil.mini("<dark_gray>› <red>"
                            + ColorUtil.smallCaps("click delete again within 10s to confirm")));
                    gm.openArenaEdit(player, arenaName);
                }
            }
            default -> { }
        }
    }

    private static void startRegen(@NotNull final GuiManager gm, @NotNull final Player player,
                                   @NotNull final Arena arena, @NotNull final RegenMode mode) {
        if (!player.hasPermission("arenaregendx.regen") && !player.hasPermission("arenaregendx.admin")) {
            player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("missing permission")));
            return;
        }
        player.closeInventory();
        if (arena.latestSnapshotId() <= 0) {
            player.sendMessage(ColorUtil.mini("<red>" + ColorUtil.smallCaps("no snapshot. save first.")));
            return;
        }
        final boolean safe = gm.configManager().general().safeModeDefault && !player.hasPermission("arenaregendx.bypass");
        player.sendMessage(ColorUtil.mini("<dark_gray>› <gold>"
                + ColorUtil.smallCaps("restoring ") + "<aqua>" + arena.name()
                + " <gray>(" + ColorUtil.smallCaps(mode.name().replace('_', ' ')) + ")"));
        gm.regenManager().regen(arena.name(), mode, safe, false).thenAccept(task ->
                gm.plugin().messages().deliver(player, ColorUtil.mini(
                    "<dark_gray>› <green>" + ColorUtil.smallCaps("done. ") + "<white>" + task.blocksApplied()
                            + " <green>" + ColorUtil.smallCaps("written")))).exceptionally(t -> {
            gm.plugin().messages().deliver(player, ColorUtil.mini("<red>" + ColorUtil.smallCaps("regen failed: ")
                    + "<gray>" + ColorUtil.escape(rootMessage(t))));
            return null;
        });
    }

    private static @NotNull String rootMessage(@NotNull final Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && (cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException)) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
