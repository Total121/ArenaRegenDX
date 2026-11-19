/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.gui;

import com.dravorax.arenaregendx.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Spacious inventory chrome + custom heads. Interior slots stay empty on purpose.
 */
public final class GuiItems {

    public static final String BRAND =
            "<gradient:#7af0d8:#f7c948>" + ColorUtil.smallCaps("arena regen dx") + "</gradient>";

    private GuiItems() {
    }

    public static @NotNull ItemStack filler() {
        return pane(Material.BLACK_STAINED_GLASS_PANE);
    }

    public static @NotNull ItemStack header() {
        return pane(Material.CYAN_STAINED_GLASS_PANE);
    }

    public static @NotNull ItemStack accent() {
        return pane(Material.ORANGE_STAINED_GLASS_PANE);
    }

    public static @NotNull ItemStack dark() {
        return pane(Material.GRAY_STAINED_GLASS_PANE);
    }

    public static @NotNull ItemStack button(@NotNull final Material material,
                                            @NotNull final String name,
                                            @Nullable final List<String> lore) {
        return decorate(new ItemStack(material), name, lore, false);
    }

    public static @NotNull ItemStack head(@NotNull final String textureHash,
                                          @NotNull final String name,
                                          @Nullable final List<String> lore) {
        return decorate(GuiHeads.of(textureHash), name, lore, false);
    }

    public static @NotNull ItemStack named(@NotNull final String color,
                                           @NotNull final String text,
                                           @NotNull final String textureHash,
                                           @NotNull final String... lore) {
        final List<String> lines = new ArrayList<>(lore.length);
        for (final String line : lore) {
            lines.add(ColorUtil.loreLine(line));
        }
        return head(textureHash, ColorUtil.named(color, text), lines);
    }

    /**
     * Thin frame only. The centre of the menu is left empty so it does not look packed.
     */
    public static void frame(@NotNull final org.bukkit.inventory.Inventory inv) {
        final int size = inv.getSize();
        final int rows = size / 9;
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, header());
            inv.setItem(size - 9 + i, dark());
        }
        for (int row = 1; row < rows - 1; row++) {
            inv.setItem(row * 9, filler());
            inv.setItem(row * 9 + 8, filler());
        }
        inv.setItem(0, accent());
        inv.setItem(8, accent());
        inv.setItem(size - 9, accent());
        inv.setItem(size - 1, accent());
    }

    public static void fillBackground(@NotNull final org.bukkit.inventory.Inventory inv) {
        // Intentionally empty — cramped menus fill every hole with glass.
    }

    public static void fillBackground(@NotNull final org.bukkit.inventory.Inventory inv,
                                      @NotNull final GuiManager.GuiHolder holder) {
        fillBackground(inv);
    }

    public static void headerRow(@NotNull final org.bukkit.inventory.Inventory inv) {
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, header());
        }
        inv.setItem(0, accent());
        inv.setItem(8, accent());
    }

    public static void footerRow(@NotNull final org.bukkit.inventory.Inventory inv, final int start) {
        for (int i = 0; i < 9; i++) {
            inv.setItem(start + i, dark());
        }
        inv.setItem(start, accent());
        inv.setItem(start + 8, accent());
    }

    private static @NotNull ItemStack decorate(@NotNull final ItemStack item,
                                               @NotNull final String name,
                                               @Nullable final List<String> lore,
                                               final boolean hideTooltip) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(ColorUtil.mini(name));
        if (lore != null && !lore.isEmpty()) {
            final List<Component> loreComp = new ArrayList<>(lore.size());
            for (final String line : lore) {
                loreComp.add(ColorUtil.mini(line));
            }
            meta.lore(loreComp);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_PLACED_ON);
        if (hideTooltip) {
            meta.setHideTooltip(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static @NotNull ItemStack pane(@NotNull final Material material) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }
}
