/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.gui.menus;

import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.gui.GuiHeads;
import com.dravorax.arenaregendx.gui.GuiItems;
import com.dravorax.arenaregendx.gui.GuiManager;
import com.dravorax.arenaregendx.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Arena cards sit on every other slot so the page can breathe.
 */
public final class ArenaManagerMenu {

    private ArenaManagerMenu() {
    }

    /** Spaced card slots on rows 1–4 (not the frame). */
    private static final int[] CARD_SLOTS = {
            10, 12, 14, 16,
            19, 21, 23, 25,
            28, 30, 32, 34,
            37, 39, 41, 43
    };
    private static final int PAGE_SIZE = CARD_SLOTS.length;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_PAGE = 47;

    /** Immutable and thread-safe (menus are built on many region threads under Folia). */
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public static @NotNull Inventory build(@NotNull final GuiManager gm, @NotNull final Player player,
                                           final int page) {
        final List<Arena> arenas = new ArrayList<>(gm.arenaManager().all());
        arenas.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

        final int total = arenas.size();
        final int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        final int safePage = Math.min(Math.max(0, page), totalPages - 1);
        // The holder must carry the clamped page, or card clicks map to the wrong arena.
        final GuiManager.GuiHolder holder = new GuiManager.GuiHolder(GuiManager.GuiType.ARENA_MANAGER, safePage);
        final Inventory inv = Bukkit.createInventory(holder, 54, ColorUtil.mini(
                "<dark_gray>› <aqua>" + ColorUtil.smallCaps("arenas") + " <dark_gray>‹"));
        holder.inventory(inv);
        GuiItems.frame(inv);

        inv.setItem(4, GuiItems.head(GuiHeads.ARENAS,
                ColorUtil.named("<aqua>", "arenas"),
                List.of(ColorUtil.loreLine("click a head to open the editor"))));
        final int start = safePage * PAGE_SIZE;
        final int end = Math.min(total, start + PAGE_SIZE);

        for (int i = start; i < end; i++) {
            inv.setItem(CARD_SLOTS[i - start], arenaCard(arenas.get(i)));
        }

        if (safePage > 0) {
            inv.setItem(SLOT_PREV, GuiItems.named("<aqua>", "previous", GuiHeads.BACK,
                    "page " + safePage));
        }
        inv.setItem(SLOT_PAGE, GuiItems.named("<gray>", "page " + (safePage + 1) + " / " + totalPages,
                GuiHeads.PAGE, total + " arenas"));
        inv.setItem(SLOT_BACK, GuiItems.named("<gray>", "back", GuiHeads.BACK, "return to menu"));
        if (safePage < totalPages - 1) {
            inv.setItem(SLOT_NEXT, GuiItems.named("<aqua>", "next", GuiHeads.NEXT,
                    "page " + (safePage + 2)));
        }
        return inv;
    }

    private static org.bukkit.inventory.ItemStack arenaCard(@NotNull final Arena a) {
        final String hash = switch (a.status()) {
            case REGENERATING, SAVING -> GuiHeads.ARENA_BUSY;
            case ERROR -> GuiHeads.ARENA_ERROR;
            default -> GuiHeads.ARENA_READY;
        };
        final String lastRegen = a.lastRegeneratedAt() > 0
                ? DATE_FMT.format(Instant.ofEpochMilli(a.lastRegeneratedAt()))
                : "never";
        return GuiItems.head(hash,
                ColorUtil.named("<aqua>", a.name()),
                List.of(
                        ColorUtil.loreLine("size  " + a.sizeX() + " × " + a.sizeY() + " × " + a.sizeZ()),
                        ColorUtil.loreLine("blocks  " + a.blockCount()),
                        ColorUtil.loreLine("snapshot  #" + a.latestSnapshotId()),
                        ColorUtil.loreLine("last regen  " + lastRegen),
                        ColorUtil.loreLine("status  " + a.status().name().toLowerCase(java.util.Locale.ROOT)),
                        "",
                        ColorUtil.named("<gold>", "click  ·  instant regen is inside")
                ));
    }

    public static void handleClick(@NotNull final GuiManager gm, @NotNull final Player player,
                                   final int slot, final int page) {
        if (slot == SLOT_BACK) {
            gm.openMain(player);
            return;
        }
        if (slot == SLOT_PREV && page > 0) {
            gm.openArenaManager(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT) {
            final int pages = Math.max(1, (gm.arenaManager().count() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page + 1 < pages) {
                gm.openArenaManager(player, page + 1);
            }
            return;
        }
        int cardIndex = -1;
        for (int i = 0; i < CARD_SLOTS.length; i++) {
            if (CARD_SLOTS[i] == slot) {
                cardIndex = i;
                break;
            }
        }
        if (cardIndex < 0) {
            return;
        }
        final List<Arena> arenas = new ArrayList<>(gm.arenaManager().all());
        arenas.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        final int index = page * PAGE_SIZE + cardIndex;
        if (index < 0 || index >= arenas.size()) {
            return;
        }
        gm.openArenaEdit(player, arenas.get(index).name());
    }
}
