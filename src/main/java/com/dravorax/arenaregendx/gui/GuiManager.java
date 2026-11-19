/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.gui;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.gui.menus.ArenaManagerMenu;
import com.dravorax.arenaregendx.gui.menus.HelpMenu;
import com.dravorax.arenaregendx.gui.menus.MainMenu;
import com.dravorax.arenaregendx.gui.menus.PerformanceMenu;
import com.dravorax.arenaregendx.gui.menus.SettingsMenu;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central GUI dispatcher.
 *
 * <p>Every ArenaRegenDX inventory uses a {@link GuiHolder} (a custom
 * {@link InventoryHolder}) that carries the menu type and pagination state. The
 * {@link #onInventoryClick(InventoryClickEvent)} handler inspects the holder and
 * routes the click to the appropriate menu builder, which owns the click
 * semantics for its items.</p>
 *
 * <h2>Theme</h2>
 * <p>Unicode small-caps, custom heads from minecraft-heads.com, teal / amber
 * frame, empty interior. No packed glass fill.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class GuiManager implements Listener {

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final SnapshotManager snapshotManager;
    private final RegenManager regenManager;
    private final TPSMonitor tpsMonitor;
    private final ConfigManager configManager;

    /** Per-player open GUI session. */
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
    /** Click debounce to prevent double-fire from lag / rapid clicks. */
    private final Map<UUID, Long> lastClickNanos = new ConcurrentHashMap<>();
    private static final long CLICK_DEBOUNCE_NS = 250_000_000L; // 250ms

    public GuiManager(@NotNull final ArenaRegenDXPlugin plugin,
                      @NotNull final ArenaManager arenaManager,
                      @NotNull final SnapshotManager snapshotManager,
                      @NotNull final RegenManager regenManager,
                      @NotNull final TPSMonitor tpsMonitor,
                      @NotNull final ConfigManager configManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.snapshotManager = snapshotManager;
        this.regenManager = regenManager;
        this.tpsMonitor = tpsMonitor;
        this.configManager = configManager;
    }

    // -----------------------------------------------------------------------
    // Menu openers (called by commands)
    // -----------------------------------------------------------------------

    public void openMain(@NotNull final Player player) {
        final Inventory inv = MainMenu.build(this, player);
        player.openInventory(inv);
        sessions.put(player.getUniqueId(), new GuiSession(GuiType.MAIN, 0));
        click(player);
    }

    public void openArenaEdit(@NotNull final Player player, @NotNull final String arenaName) {
        final Inventory inv = com.dravorax.arenaregendx.gui.menus.ArenaEditMenu.build(this, player, arenaName);
        player.openInventory(inv);
        sessions.put(player.getUniqueId(), new GuiSession(GuiType.ARENA_EDIT, 0, arenaName));
        click(player);
    }

    public void openArenaManager(@NotNull final Player player, final int page) {
        final Inventory inv = ArenaManagerMenu.build(this, player, page);
        player.openInventory(inv);
        sessions.put(player.getUniqueId(), new GuiSession(GuiType.ARENA_MANAGER, page));
        click(player);
    }

    public void openSettings(@NotNull final Player player) {
        final Inventory inv = SettingsMenu.build(this, player);
        player.openInventory(inv);
        sessions.put(player.getUniqueId(), new GuiSession(GuiType.SETTINGS, 0));
        click(player);
    }

    public void openPerformance(@NotNull final Player player) {
        final Inventory inv = PerformanceMenu.build(this, player);
        player.openInventory(inv);
        sessions.put(player.getUniqueId(), new GuiSession(GuiType.PERFORMANCE, 0));
        click(player);
    }

    public void openHelp(@NotNull final Player player) {
        final Inventory inv = HelpMenu.build(this, player);
        player.openInventory(inv);
        sessions.put(player.getUniqueId(), new GuiSession(GuiType.HELP, 0));
        click(player);
    }

    private static void click(@NotNull final Player player) {
        try {
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.35f, 1.35f);
        } catch (final Throwable ignored) {
        }
    }

    // -----------------------------------------------------------------------
    // Click routing
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(@NotNull final InventoryClickEvent event) {
        final Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true); // our GUIs are read/action-only, never item-movable
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Number-key / drop / double-click / shift-collect must never trigger actions.
        final ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return; // clicked in player inventory — ignore actions, still cancelled
        }
        final long now = System.nanoTime();
        final Long prev = lastClickNanos.put(player.getUniqueId(), now);
        if (prev != null && now - prev < CLICK_DEBOUNCE_NS) {
            return;
        }
        final int slot = event.getSlot();
        switch (holder.type()) {
            case MAIN -> MainMenu.handleClick(this, player, slot);
            case ARENA_MANAGER -> ArenaManagerMenu.handleClick(this, player, slot, holder.page());
            case SETTINGS -> SettingsMenu.handleClick(this, player, slot);
            case PERFORMANCE -> PerformanceMenu.handleClick(this, player, slot);
            case ARENA_EDIT -> com.dravorax.arenaregendx.gui.menus.ArenaEditMenu.handleClick(this, player, slot, holder.arenaName());
            case HELP -> HelpMenu.handleClick(this, player, slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryCreative(@NotNull final InventoryCreativeEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(@NotNull final PlayerSwapHandItemsEvent event) {
        final var open = event.getPlayer().getOpenInventory().getTopInventory();
        if (open.getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player
                && event.getView().getTopInventory().getHolder() instanceof GuiHolder holder
                && holder.type() == GuiType.ARENA_EDIT) {
            com.dravorax.arenaregendx.gui.menus.ArenaEditMenu.clearPending(player.getUniqueId());
        }
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull final PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        lastClickNanos.remove(event.getPlayer().getUniqueId());
        com.dravorax.arenaregendx.gui.menus.ArenaEditMenu.clearPending(event.getPlayer().getUniqueId());
    }

    // -----------------------------------------------------------------------
    // Accessors for menu builders
    // -----------------------------------------------------------------------

    public @NotNull ArenaRegenDXPlugin plugin() { return plugin; }
    public @NotNull ArenaManager arenaManager() { return arenaManager; }
    public @NotNull SnapshotManager snapshotManager() { return snapshotManager; }
    public @NotNull RegenManager regenManager() { return regenManager; }
    public @NotNull TPSMonitor tpsMonitor() { return tpsMonitor; }
    public @NotNull ConfigManager configManager() { return configManager; }
    public @Nullable GuiSession session(@NotNull final Player p) { return sessions.get(p.getUniqueId()); }

    /**
     * Shutdown: close all open GUIs.
     */
    public void shutdown() {
        for (final UUID id : sessions.keySet()) {
            final Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                plugin.scheduler().runAtEntity(p, p::closeInventory);
            }
        }
        sessions.clear();
        lastClickNanos.clear();
    }

    // -----------------------------------------------------------------------
    // Holder + session types
    // -----------------------------------------------------------------------

    /** Menu type discriminator. */
    public enum GuiType { MAIN, ARENA_MANAGER, SETTINGS, PERFORMANCE, ARENA_EDIT, HELP }

    /** Custom InventoryHolder that marks an inventory as ours + carries type/page/arena. */
    public static final class GuiHolder implements InventoryHolder {
        private final GuiType type;
        private final int page;
        private final String arenaName;
        private Inventory inventory;

        public GuiHolder(@NotNull final GuiType type, final int page) {
            this(type, page, null);
        }

        public GuiHolder(@NotNull final GuiType type, final int page, @Nullable final String arenaName) {
            this.type = type;
            this.page = page;
            this.arenaName = arenaName;
        }

        public @NotNull GuiType type() { return type; }
        public int page() { return page; }
        public @Nullable String arenaName() { return arenaName; }

        public void inventory(@NotNull final Inventory inv) { this.inventory = inv; }

        @Override
        public @Nullable Inventory getInventory() {
            return inventory;
        }
    }

    /** Per-player session state. */
    public static final class GuiSession {
        public final GuiType type;
        public final int page;
        public final String arenaName;
        public GuiSession(@NotNull final GuiType type, final int page) {
            this(type, page, null);
        }
        public GuiSession(@NotNull final GuiType type, final int page, final String arenaName) {
            this.type = type;
            this.page = page;
            this.arenaName = arenaName;
        }
    }
}
