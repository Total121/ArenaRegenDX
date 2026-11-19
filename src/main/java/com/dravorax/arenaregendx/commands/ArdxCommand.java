/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.commands;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.arena.ArenaSelectionManager;
import com.dravorax.arenaregendx.arena.ArenaStatus;
import com.dravorax.arenaregendx.arena.ExclusionZone;
import com.dravorax.arenaregendx.arena.Selection;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.gui.GuiManager;
import com.dravorax.arenaregendx.listeners.SelectionWandListener;
import com.dravorax.arenaregendx.regen.AutoResetManager;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.regen.RegenMode;
import com.dravorax.arenaregendx.regen.RegenSpeed;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import com.dravorax.arenaregendx.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Root command {@code /ardx}.
 *
 * @author DravoraX
 */
public final class ArdxCommand implements TabExecutor {

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final SnapshotManager snapshotManager;
    private final RegenManager regenManager;
    private final AutoResetManager autoResetManager;
    private final ArenaSelectionManager selectionManager;
    private final GuiManager guiManager;
    private final ConfigManager configManager;
    /** sender uuid/name → arena pending confirm-delete (expires in 20s). */
    private final Map<String, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();

    private record PendingDelete(@NotNull String arena, long expiresAt) {}

    public ArdxCommand(@NotNull final ArenaRegenDXPlugin plugin,
                       @NotNull final ArenaManager arenaManager,
                       @NotNull final SnapshotManager snapshotManager,
                       @NotNull final RegenManager regenManager,
                       @NotNull final AutoResetManager autoResetManager,
                       @NotNull final ArenaSelectionManager selectionManager,
                       @NotNull final GuiManager guiManager,
                       @NotNull final ConfigManager configManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.snapshotManager = snapshotManager;
        this.regenManager = regenManager;
        this.autoResetManager = autoResetManager;
        this.selectionManager = selectionManager;
        this.guiManager = guiManager;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command,
                             @NotNull final String label, @NotNull final String @NotNull [] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "cancel" -> handleCancel(sender, args);
            case "create" -> handleCreate(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "rename" -> handleRename(sender, args);
            case "save" -> handleSave(sender, args);
            case "load" -> handleLoad(sender, args);
            case "regen" -> handleRegen(sender, args);
            case "speed" -> handleSpeed(sender, args);
            case "setspawn" -> handleSetSpawn(sender, args);
            case "exclude" -> handleExclude(sender, args, label);
            case "preview" -> handlePreview(sender, args);
            case "wand" -> handleWand(sender);
            case "gui" -> handleGui(sender);
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "debug" -> handleDebug(sender);
            case "help" -> { sendHelp(sender, label); yield true; }
            default -> {
                msg(sender, "<red>Unknown subcommand: <yellow>" + sub + "<red>. Try <gold>/" + label + " help");
                yield true;
            }
        };
    }

    private boolean handleCreate(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.create")) return true;
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>This command can only be run by a player.");
            return true;
        }
        if (args.length < 2) {
            msg(sender, "<red>Usage: <gold>/ardx create <name> [autoResetSeconds] [async:true/false]");
            return true;
        }
        final String name = ColorUtil.sanitizeName(args[1]);
        final Selection wand = selectionManager.getSelection(player);
        Selection sel = (wand != null && wand.isComplete()) ? wand : null;
        if (sel == null) {
            sel = com.dravorax.arenaregendx.hooks.worldedit.WorldEditHook.getSelection(player);
        }
        if (sel == null || !sel.isComplete()) {
            msg(sender, "<red>Select a region first with <gold>/ardx wand<red> (or WorldEdit).");
            return true;
        }
        final Location min = sel.min(), max = sel.max();
        if (min == null || max == null || min.getWorld() == null || !min.getWorld().equals(max.getWorld())) {
            msg(sender, "<red>Both corners must be in the same world.");
            return true;
        }
        int autoReset = 0;
        if (args.length >= 3) {
            try {
                autoReset = Math.max(0, Integer.parseInt(args[2]));
            } catch (final NumberFormatException e) {
                msg(sender, "<red>Invalid auto-reset time: <yellow>" + args[2]);
                return true;
            }
        }
        boolean asyncRegen = true;
        if (args.length >= 4) {
            final String asyncStr = args[3].toLowerCase(Locale.ROOT);
            if (asyncStr.startsWith("off") || asyncStr.equals("false") || asyncStr.equals("0")) {
                asyncRegen = false;
            }
        }
        final World world = min.getWorld();
        final int finalAutoReset = autoReset;
        try {
            final Arena arena = arenaManager.createArena(name, world, min, max, finalAutoReset, asyncRegen);
            msg(sender, "<dark_gray>» <gold>Arena <yellow>" + arena.name()
                    + " <dark_gray>(<yellow>" + arena.sizeX() + "x" + arena.sizeY() + "x" + arena.sizeZ()
                    + "<dark_gray>) <green>created.");
            plugin.selectionVisualizer().showArenaPreview(player, arena);
            selectionManager.clear(player);
            playSound(player, "BLOCK_NOTE_BLOCK_PLING");
            msg(sender, "<dark_gray>» <gold>Capturing snapshot...");
            snapshotManager.saveSnapshot(name, world, min, max).thenAccept(meta -> {
                arena.onSnapshotSaved(meta.snapshotId());
                arenaManager.saveArena(arena);
                msg(sender, "<dark_gray>» <green>Snapshot saved <dark_gray>(<yellow>#"
                        + meta.snapshotId() + "<dark_gray>, <yellow>" + meta.blockCount() + " blocks<dark_gray>).");
                msg(sender, "<dark_gray>» Protect spawn with <gold>/ardx exclude add " + name + " spawn");
                if (finalAutoReset > 0) {
                    autoResetManager.startCountdown(arena);
                }
            }).exceptionally(t -> {
                msg(sender, "<red>Auto-save failed: <yellow>" + t.getMessage());
                plugin.getSLF4JLogger().error("Auto-save failed for {}", name, t);
                return null;
            });
        } catch (final IllegalArgumentException e) {
            msg(sender, "<red>" + e.getMessage());
        }
        return true;
    }

    private boolean handleExclude(@NotNull final CommandSender sender, @NotNull final String[] args,
                                  @NotNull final String label) {
        if (!checkPerm(sender, "arenaregendx.create")) return true;
        if (args.length < 2) {
            msg(sender, "<gold>Exclusion zones <dark_gray>— blocks inside these volumes never regen.");
            msg(sender, "  <gold>/" + label + " exclude add <arena> <zone> <dark_gray>← cuboid from wand");
            msg(sender, "  <gold>/" + label + " exclude sphere <arena> <zone> [radius]");
            msg(sender, "  <gold>/" + label + " exclude cylinder <arena> <zone> <radius>");
            msg(sender, "  <gold>/" + label + " exclude remove <arena> <zone>");
            msg(sender, "  <gold>/" + label + " exclude list <arena>");
            msg(sender, "  <gold>/" + label + " exclude show <arena>");
            return true;
        }
        final String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "add" -> excludeAddCuboid(sender, args);
            case "sphere" -> excludeSphere(sender, args);
            case "cylinder" -> excludeCylinder(sender, args);
            case "remove" -> excludeRemove(sender, args);
            case "list" -> excludeList(sender, args);
            case "show" -> excludeShow(sender, args);
            case "hide" -> {
                if (sender instanceof Player p) plugin.selectionVisualizer().hidePreview(p);
                msg(sender, "<dark_gray>» Preview hidden.");
                yield true;
            }
            default -> {
                msg(sender, "<red>Unknown exclude action. Use add, sphere, cylinder, remove, list, show.");
                yield true;
            }
        };
    }

    private boolean excludeAddCuboid(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.");
            return true;
        }
        if (args.length < 4) {
            msg(sender, "<red>Usage: <gold>/ardx exclude add <arena> <zone>");
            msg(sender, "<gray>Select the protected spawn with the wand, then run this.");
            return true;
        }
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return true;
        final Selection wand = selectionManager.getSelection(player);
        Selection sel = (wand != null && wand.isComplete()) ? wand : null;
        if (sel == null) {
            sel = com.dravorax.arenaregendx.hooks.worldedit.WorldEditHook.getSelection(player);
        }
        if (sel == null || !sel.isComplete()) {
            msg(sender, "<red>Wand-select the spawn (or any volume you do <white>not<red> want restored).");
            return true;
        }
        final Location min = sel.min();
        final Location max = sel.max();
        if (min == null || max == null || min.getWorld() == null
                || !min.getWorld().getName().equals(arena.worldName())) {
            msg(sender, "<red>Exclusion must be in the arena world (<yellow>" + arena.worldName() + "<red>).");
            return true;
        }
        final String zoneName = ColorUtil.sanitizeName(args[3]);
        final ExclusionZone zone = ExclusionZone.cuboid(zoneName, min, max);
        arena.addExclusion(zone);
        arenaManager.saveArena(arena);
        plugin.selectionVisualizer().showArenaPreview(player, arena);
        selectionManager.clear(player);
        msg(sender, "<dark_gray>» <green>Exclusion <yellow>" + zone + " <green>added to <yellow>" + arena.name());
        msg(sender, "<gray>Warzone around it will regen. This volume will not.");
        return true;
    }

    private boolean excludeSphere(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.");
            return true;
        }
        if (args.length < 4) {
            msg(sender, "<red>Usage: <gold>/ardx exclude sphere <arena> <zone> [radius]");
            return true;
        }
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return true;
        double radius = 8.0;
        if (args.length >= 5) {
            try {
                radius = Double.parseDouble(args[4]);
            } catch (final NumberFormatException e) {
                msg(sender, "<red>Invalid radius.");
                return true;
            }
        }
        final String zoneName = ColorUtil.sanitizeName(args[3]);
        final ExclusionZone zone = ExclusionZone.sphere(zoneName, player.getLocation(), radius);
        arena.addExclusion(zone);
        arenaManager.saveArena(arena);
        plugin.selectionVisualizer().showArenaPreview(player, arena);
        msg(sender, "<dark_gray>» <green>Sphere exclusion <yellow>" + zone + " <green>added.");
        return true;
    }

    private boolean excludeCylinder(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.");
            return true;
        }
        if (args.length < 5) {
            msg(sender, "<red>Usage: <gold>/ardx exclude cylinder <arena> <zone> <radius>");
            return true;
        }
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return true;
        final double radius;
        try {
            radius = Double.parseDouble(args[4]);
        } catch (final NumberFormatException e) {
            msg(sender, "<red>Invalid radius.");
            return true;
        }
        final String zoneName = ColorUtil.sanitizeName(args[3]);
        final ExclusionZone zone = ExclusionZone.cylinder(zoneName, player.getLocation(), radius,
                arena.originY(), arena.originY() + arena.sizeY() - 1);
        arena.addExclusion(zone);
        arenaManager.saveArena(arena);
        plugin.selectionVisualizer().showArenaPreview(player, arena);
        msg(sender, "<dark_gray>» <green>Cylinder exclusion <yellow>" + zone + " <green>added.");
        return true;
    }

    private boolean excludeRemove(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (args.length < 4) {
            msg(sender, "<red>Usage: <gold>/ardx exclude remove <arena> <zone>");
            return true;
        }
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return true;
        if (arena.removeExclusion(args[3])) {
            arenaManager.saveArena(arena);
            msg(sender, "<dark_gray>» <green>Removed exclusion <yellow>" + args[3]);
            if (sender instanceof Player p) plugin.selectionVisualizer().showArenaPreview(p, arena);
        } else {
            msg(sender, "<red>No exclusion named <yellow>" + args[3]);
        }
        return true;
    }

    private boolean excludeList(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (args.length < 3) {
            msg(sender, "<red>Usage: <gold>/ardx exclude list <arena>");
            return true;
        }
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return true;
        if (arena.exclusions().isEmpty()) {
            msg(sender, "<dark_gray>» <yellow>" + arena.name() + " <gray>has no exclusions. Whole cuboid will regen.");
            return true;
        }
        msg(sender, "<dark_gray>» <gold>Exclusions for <yellow>" + arena.name());
        for (final ExclusionZone z : arena.exclusions()) {
            msg(sender, "  <red>■ <yellow>" + z);
        }
        return true;
    }

    private boolean excludeShow(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.");
            return true;
        }
        if (args.length < 3) {
            msg(sender, "<red>Usage: <gold>/ardx exclude show <arena>");
            return true;
        }
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return true;
        plugin.selectionVisualizer().showArenaPreview(player, arena);
        msg(sender, "<dark_gray>» <gold>Gold dust <gray>= arena (will regen)  <red>red dust <gray>= excluded (won't regen)");
        return true;
    }

    private boolean handlePreview(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.wand")) return true;
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.");
            return true;
        }
        if (args.length < 2) {
            msg(sender, "<red>Usage: <gold>/ardx preview <arena>");
            return true;
        }
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return true;
        plugin.selectionVisualizer().showArenaPreview(player, arena);
        msg(sender, "<dark_gray>» Showing <yellow>" + arena.name()
                + " <gray>for 30s. Gold = regen, red = protected.");
        return true;
    }

    private boolean handleSetSpawn(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.admin")) return true;
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.");
            return true;
        }
        if (args.length < 2) {
            msg(sender, "<red>Usage: <gold>/ardx setspawn <arena>");
            return true;
        }
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return true;
        final Location loc = player.getLocation();
        if (loc.getWorld() == null || !loc.getWorld().getName().equals(arena.worldName())) {
            msg(sender, "<red>You must be in the arena world (<yellow>" + arena.worldName() + "<red>).");
            return true;
        }
        arena.setSpawn(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        arenaManager.saveArena(arena);
        msg(sender, "<dark_gray>» <green>Spawn set for <yellow>" + arena.name()
                + " <gray>at <yellow>" + String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ()));
        playSound(player, "BLOCK_NOTE_BLOCK_BELL");
        return true;
    }

    private boolean handleDelete(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.delete")) return true;
        if (args.length < 2) {
            msg(sender, "<red>Usage: <gold>/ardx delete <name> confirm");
            return true;
        }
        final String name = ColorUtil.sanitizeName(args[1]);
        if (arenaManager.getArena(name) == null) {
            msg(sender, "<red>Arena not found: <yellow>" + name);
            return true;
        }
        final boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        final String key = sender instanceof Player p ? p.getUniqueId().toString() : "console";
        if (!confirmed) {
            pendingDeletes.entrySet().removeIf(e -> e.getValue().expiresAt() < System.currentTimeMillis());
            pendingDeletes.put(key, new PendingDelete(name, System.currentTimeMillis() + 20_000L));
            msg(sender, "<red>This permanently deletes <yellow>" + name + " <red>and all snapshots.");
            msg(sender, "<gray>Type <gold>/ardx delete " + name + " confirm <gray>within 20s to proceed.");
            return true;
        }
        final PendingDelete pending = pendingDeletes.remove(key);
        if (pending == null || !pending.arena.equalsIgnoreCase(name) || pending.expiresAt < System.currentTimeMillis()) {
            msg(sender, "<red>Confirmation expired. Run <gold>/ardx delete " + name + " <red>again first.");
            return true;
        }
        if (arenaManager.deleteArena(name)) {
            msg(sender, "<dark_gray>» <gold>Arena <yellow>" + name + " <red>deleted.");
        } else {
            msg(sender, "<red>Arena not found: <yellow>" + name);
        }
        return true;
    }

    private boolean handleCancel(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.cancel")) return true;
        if (args.length < 2) {
            regenManager.cancelAll();
            msg(sender, "<dark_gray>» <gold>Cancelled all restore jobs.");
            return true;
        }
        final String name = ColorUtil.sanitizeName(args[1]);
        if (regenManager.cancel(name)) {
            msg(sender, "<dark_gray>» <gold>Cancelled restore of <yellow>" + name);
        } else {
            msg(sender, "<red>No running restore for <yellow>" + name);
        }
        return true;
    }

    private boolean handleRename(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.admin")) return true;
        if (args.length < 3) {
            msg(sender, "<red>Usage: <gold>/ardx rename <old> <new>");
            return true;
        }
        try {
            arenaManager.renameArena(ColorUtil.sanitizeName(args[1]), ColorUtil.sanitizeName(args[2]));
            msg(sender, "<dark_gray>» <green>Renamed <yellow>" + args[1] + " <green>to <yellow>" + args[2]);
        } catch (final IllegalArgumentException e) {
            msg(sender, "<red>" + e.getMessage());
        }
        return true;
    }

    private boolean handleSave(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.save")) return true;
        if (args.length < 2) {
            msg(sender, "<red>Usage: <gold>/ardx save <name>");
            return true;
        }
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return true;
        if (regenManager.isRegenerating(arena.name())
                || arena.status() == ArenaStatus.REGENERATING
                || arena.status() == ArenaStatus.SAVING) {
            msg(sender, "<red>Cannot save while the arena is regenerating or already saving.");
            return true;
        }
        final World world = Bukkit.getWorld(arena.worldName());
        if (world == null) {
            msg(sender, "<red>World not loaded: <yellow>" + arena.worldName());
            return true;
        }
        final Location min = new Location(world, arena.originX(), arena.originY(), arena.originZ());
        final Location max = new Location(world,
                arena.originX() + arena.sizeX() - 1,
                arena.originY() + arena.sizeY() - 1,
                arena.originZ() + arena.sizeZ() - 1);
        arena.setStatus(ArenaStatus.SAVING);
        msg(sender, "<dark_gray>» <gold>Saving <yellow>" + arena.name() + "<gold>...");
        final long start = System.currentTimeMillis();
        snapshotManager.saveSnapshot(arena.name(), world, min, max).thenAccept(meta -> {
            plugin.scheduler().runGlobal(() -> {
                if (arenaManager.getArena(arena.name()) == null) return;
                arena.onSnapshotSaved(meta.snapshotId());
                arena.setStatus(ArenaStatus.READY);
                arenaManager.saveArena(arena);
            });
            msg(sender, "<dark_gray>» <green>Saved <dark_gray>(<yellow>#" + meta.snapshotId()
                    + "<dark_gray>, <yellow>" + meta.blockCount() + " blocks<dark_gray>, <yellow>"
                    + humanSize(meta.fileSize()) + "<dark_gray>, <yellow>"
                    + (System.currentTimeMillis() - start) + "ms<dark_gray>).");
            snapshotManager.pruneOldSnapshots(arena.name());
        }).exceptionally(t -> {
            plugin.scheduler().runGlobal(() -> {
                if (arenaManager.getArena(arena.name()) != null) {
                    arena.setStatus(ArenaStatus.ERROR);
                }
            });
            msg(sender, "<red>Save failed: <yellow>" + t.getMessage());
            plugin.getSLF4JLogger().error("Snapshot save failed for {}", arena.name(), t);
            return null;
        });
        return true;
    }

    private boolean handleLoad(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.load")) return true;
        if (args.length < 2) {
            msg(sender, "<red>Usage: <gold>/ardx load <name> [snapshotId]");
            return true;
        }
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return true;
        final long snapshotId;
        if (args.length >= 3) {
            try {
                snapshotId = Long.parseLong(args[2]);
            } catch (final NumberFormatException e) {
                msg(sender, "<red>Invalid snapshot id.");
                return true;
            }
        } else {
            snapshotId = arena.latestSnapshotId();
        }
        if (snapshotId <= 0) {
            msg(sender, "<red>No snapshot. Run <gold>/ardx save " + arena.name());
            return true;
        }
        msg(sender, "<dark_gray>» Loading snapshot <yellow>#" + snapshotId + "<dark_gray>...");
        snapshotManager.loadSnapshot(arena.name(), snapshotId).thenAccept(s -> {
            s.resolvePalette();
            msg(sender, "<dark_gray>» <green>Loaded <yellow>" + s.blockCount()
                    + " <green>blocks. Restore with <gold>/ardx regen " + arena.name());
        }).exceptionally(t -> {
            msg(sender, "<red>Load failed: <yellow>" + t.getMessage());
            return null;
        });
        return true;
    }

    private boolean handleRegen(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.regen")) return true;
        if (args.length < 2) {
            msg(sender, "<red>Usage: <gold>/ardx regen <name> [mode]");
            return true;
        }
        final String name = ColorUtil.sanitizeName(args[1]);
        final RegenMode mode;
        if (args.length >= 3) {
            try {
                mode = RegenMode.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                msg(sender, "<red>Unknown mode. Use INSTANT, CHUNK_BY_CHUNK, LAYER_BY_LAYER, ANIMATED.");
                return true;
            }
        } else {
            mode = RegenMode.INSTANT;
        }
        final boolean safe = !sender.hasPermission("arenaregendx.bypass");
        msg(sender, "<dark_gray>» <gold>Restoring <yellow>" + name
                + " <dark_gray>(<yellow>" + mode.name() + "<dark_gray>)...");
        regenManager.regen(name, mode, safe).thenAccept(task -> {
            msg(sender, "<dark_gray>» <green>Done. <yellow>" + task.blocksApplied()
                    + " <green>written, <yellow>" + task.blocksSkipped()
                    + " <green>skipped, <yellow>" + task.blocksExcluded() + " <green>excluded.");
        }).exceptionally(t -> {
            msg(sender, "<red>Regen failed: <yellow>" + t.getMessage());
            return null;
        });
        return true;
    }

    private boolean handleSpeed(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.admin")) return true;
        if (args.length < 2) {
            msg(sender, "<red>Usage: <gold>/ardx speed global <slow|normal|fast|max|%>");
            msg(sender, "<red>   or: <gold>/ardx speed <arena> <slow|normal|fast|max|inherit|%>");
            return true;
        }
        try {
            if (args[1].equalsIgnoreCase("global")) {
                if (args.length < 3) {
                    msg(sender, "<dark_gray>» <gold>Global speed: <yellow>"
                            + RegenSpeed.label(configManager.performance().regenSpeedPercent));
                    return true;
                }
                final int pct = RegenSpeed.parsePresetOrPercent(args[2]);
                if (pct < 0) {
                    msg(sender, "<red>Global speed cannot be inherit. Use a percent or slow/normal/fast/max.");
                    return true;
                }
                configManager.setRegenSpeedPercent(pct);
                msg(sender, "<dark_gray>» <green>Global regen speed set to <yellow>"
                        + RegenSpeed.label(configManager.performance().regenSpeedPercent));
                return true;
            }
            final Arena arena = requireArena(sender, args[1]);
            if (arena == null) return true;
            if (args.length < 3) {
                final int effective = RegenSpeed.resolve(
                        arena.regenSpeedPercent(), configManager.performance().regenSpeedPercent);
                msg(sender, "<dark_gray>» <gold>" + arena.name() + " <yellow>speed: <white>"
                        + RegenSpeed.label(arena.regenSpeedPercent())
                        + " <dark_gray>(effective <gold>" + effective + "%<dark_gray>)");
                return true;
            }
            final int pct = RegenSpeed.parsePresetOrPercent(args[2]);
            arena.setRegenSpeedPercent(pct);
            arenaManager.saveArena(arena);
            final int effective = RegenSpeed.resolve(
                    arena.regenSpeedPercent(), configManager.performance().regenSpeedPercent);
            msg(sender, "<dark_gray>» <green>" + arena.name() + " speed → <yellow>"
                    + RegenSpeed.label(arena.regenSpeedPercent())
                    + " <dark_gray>(effective <gold>" + effective + "%<dark_gray>)");
        } catch (final IllegalArgumentException e) {
            msg(sender, "<red>" + e.getMessage());
        }
        return true;
    }

    private boolean handleWand(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.wand")) return true;
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.");
            return true;
        }
        final ItemStack wand = SelectionWandListener.createWand(plugin);
        player.getInventory().addItem(wand);
        msg(sender, "<dark_gray>» <gold>Selection wand given.");
        msg(sender, "<gray>Left click = pos 1 (lime dust). Right click = pos 2 (aqua dust).");
        msg(sender, "<gray>Complete box = gold dust outline + cyan faces.");
        playSound(player, "ITEM_ARMOR_EQUIP_GOLD");
        return true;
    }

    private boolean handleGui(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.gui")) return true;
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Players only.");
            return true;
        }
        guiManager.openMain(player);
        return true;
    }

    private boolean handleReload(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.reload")) return true;
        configManager.reload();
        plugin.applyRuntimeSettings();
        final var p = configManager.performance();
        plugin.tpsMonitor().configure(p.maxBlockBudget, p.tpsFull, p.tpsThrottle, p.tpsCrawl, p.maxTickMillis);
        msg(sender, "<dark_gray>» <green>Configuration reloaded.");
        return true;
    }

    private boolean handleStatus(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.admin")) return true;
        final var tps = plugin.tpsMonitor();
        final var mem = Runtime.getRuntime();
        final long used = (mem.totalMemory() - mem.freeMemory()) / (1024 * 1024);
        final long max = mem.maxMemory() / (1024 * 1024);
        msg(sender, "<dark_gray>» <gold>ArenaRegenDX <dark_gray>status");
        msg(sender, "<dark_gray>• <yellow>TPS: <gold>" + String.format("%.2f", tps.currentTPS())
                + " <dark_gray>(5m <gold>" + String.format("%.2f", tps.tps5m())
                + "<dark_gray>, 15m <gold>" + String.format("%.2f", tps.tps15m()) + "<dark_gray>)");
        msg(sender, "<dark_gray>• <yellow>Tick: <gold>" + String.format("%.2f", tps.averageTickTimeMs())
                + "ms <dark_gray>| budget <gold>" + tps.adaptiveBlockBudget()
                + " <dark_gray>blocks / <gold>" + String.format("%.2f", tps.adaptiveNanosBudget() / 1_000_000.0) + "ms");
        msg(sender, "<dark_gray>• <yellow>Regen speed: <gold>"
                + RegenSpeed.label(configManager.performance().regenSpeedPercent)
                + " <dark_gray>(effective budget <gold>"
                + Math.max(64, (int) (tps.adaptiveBlockBudget()
                * (configManager.performance().regenSpeedPercent / 100.0)))
                + "<dark_gray>)");
        msg(sender, "<dark_gray>• <yellow>Memory: <gold>" + used + "MB <dark_gray>/ <gold>" + max + "MB");
        msg(sender, "<dark_gray>• <yellow>Writer: <gold>" + com.dravorax.arenaregendx.nms.NmsAccess.status()
                + " <dark_gray>| dirty <gold>" + plugin.changeTracker().enabled());
        msg(sender, "<dark_gray>• <yellow>Arenas: <gold>" + arenaManager.count()
                + " <dark_gray>| queue <gold>" + regenManager.queueSize()
                + " <dark_gray>| running <gold>" + (regenManager.isRegenerating() ? "yes" : "no"));
        return true;
    }

    private boolean handleDebug(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.debug")) return true;
        msg(sender, "<dark_gray>» <gold>Debug");
        msg(sender, "<dark_gray>• Scheduler: <gold>" + plugin.scheduler().implementationName());
        msg(sender, "<dark_gray>• Folia: <gold>" + com.dravorax.arenaregendx.scheduler.SchedulerProvider.isFolia());
        msg(sender, "<dark_gray>• Java: <gold>" + System.getProperty("java.version"));
        msg(sender, "<dark_gray>• Server: <gold>" + Bukkit.getVersion());
        msg(sender, "<dark_gray>• Writer: <gold>" + com.dravorax.arenaregendx.nms.NmsAccess.status());
        msg(sender, "<dark_gray>• NMS tiles: <gold>" + com.dravorax.arenaregendx.nms.NmsAccess.nbtAvailable());
        for (final Arena a : arenaManager.all()) {
            msg(sender, "<dark_gray>  - <yellow>" + a.name() + " <dark_gray>"
                    + a.sizeX() + "x" + a.sizeY() + "x" + a.sizeZ()
                    + " snap=<gold>" + a.latestSnapshotId()
                    + " <dark_gray>excl=<gold>" + a.exclusions().size()
                    + " <dark_gray>dirty=<gold>" + plugin.changeTracker().dirtyCount(a.name())
                    + " <dark_gray>status=<gold>" + a.status());
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull final CommandSender sender, @NotNull final Command command,
                                                @NotNull final String label, @NotNull final String @NotNull [] args) {
        if (args.length == 1) {
            return filter(args[0], List.of(
                    "create", "delete", "rename", "save", "load", "regen", "speed", "cancel", "setspawn",
                    "exclude", "preview", "wand", "gui", "reload", "status", "debug", "help"));
        }
        if (args.length == 2) {
            final String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("delete", "regen", "cancel", "setspawn", "rename", "save", "load", "preview", "speed").contains(sub)) {
                if (sub.equals("speed")) {
                    final List<String> names = new java.util.ArrayList<>(arenaNames(args[1]));
                    names.addAll(filter(args[1], List.of("global")));
                    return names;
                }
                return arenaNames(args[1]);
            }
            if (sub.equals("exclude")) {
                return filter(args[1], List.of("add", "sphere", "cylinder", "remove", "list", "show", "hide"));
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("delete")) {
                return filter(args[2], List.of("confirm"));
            }
            if (args[0].equalsIgnoreCase("regen")) {
                return filter(args[2], Arrays.stream(RegenMode.values()).map(Enum::name).toList());
            }
            if (args[0].equalsIgnoreCase("speed")) {
                return filter(args[2], List.of("slow", "normal", "fast", "max", "inherit", "40", "100", "200", "350"));
            }
            if (args[0].equalsIgnoreCase("create")) {
                return List.of("0", "60", "300", "600");
            }
            if (args[0].equalsIgnoreCase("exclude") && !args[1].equalsIgnoreCase("hide")) {
                return arenaNames(args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("exclude")
                && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("add")
                || args[1].equalsIgnoreCase("sphere") || args[1].equalsIgnoreCase("cylinder"))) {
            final Arena arena = arenaManager.getArena(ColorUtil.sanitizeName(args[2]));
            if (arena != null && args[1].equalsIgnoreCase("remove")) {
                return filter(args[3], arena.exclusions().stream().map(ExclusionZone::name).toList());
            }
            return List.of("spawn", "lobby", "safe");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            return List.of("on", "off");
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("exclude")
                && (args[1].equalsIgnoreCase("sphere") || args[1].equalsIgnoreCase("cylinder"))) {
            return List.of("8", "12", "16", "24", "32");
        }
        return List.of();
    }

    private void sendHelp(@NotNull final CommandSender sender, @NotNull final String label) {
        msg(sender, "");
        msg(sender, "<gold>ArenaRegenDX <gray>v2.1.0  <dark_gray>by <yellow>DravoraX");
        msg(sender, "<dark_gray>────────────────────────────────────");
        msg(sender, "<gold>/" + label + " wand <gray>selection wand (dust outline)");
        msg(sender, "<gold>/" + label + " create <name> <gray>arena from selection + snapshot");
        msg(sender, "<gold>/" + label + " exclude add <arena> <zone> <gray>protect spawn / any volume");
        msg(sender, "<gold>/" + label + " exclude sphere <arena> <zone> [r] <gray>sphere at your feet");
        msg(sender, "<gold>/" + label + " exclude show <arena> <gray>gold = regen, red = protected");
        msg(sender, "<gold>/" + label + " save <name> <gray>recapture snapshot");
        msg(sender, "<gold>/" + label + " regen <name> [mode] <gray>restore (skips exclusions)");
        msg(sender, "<gold>/" + label + " speed global|arena <value> <gray>slow/normal/fast/max/%");
        msg(sender, "<gold>/" + label + " cancel [name] <gray>abort a running restore");
        msg(sender, "<gold>/" + label + " setspawn <arena> <gray>teleport players here on regen");
        msg(sender, "<gold>/" + label + " preview <arena> <gray>show bounds in dust");
        msg(sender, "<gold>/" + label + " gui <gray>menus");
        msg(sender, "<dark_gray>────────────────────────────────────");
        msg(sender, "<gray>Warzone tip: select the whole map, create the arena, then");
        msg(sender, "<gray>wand-select the middle spawn and <white>exclude add <gray>it.");
        msg(sender, "");
    }

    private @Nullable Arena requireArena(@NotNull final CommandSender sender, @NotNull final String raw) {
        final Arena arena = arenaManager.getArena(ColorUtil.sanitizeName(raw));
        if (arena == null) {
            msg(sender, "<red>Arena not found: <yellow>" + raw);
        }
        return arena;
    }

    private boolean checkPerm(@NotNull final CommandSender sender, @NotNull final String perm) {
        if (sender.hasPermission(perm)
                || sender.hasPermission("arenaregendx.admin")
                || sender.hasPermission("arenaregendx.*")
                || sender.hasPermission("ardx.*")
                || sender.hasPermission("sregen.admin")
                || sender.hasPermission("sregen.*")) {
            return true;
        }
        msg(sender, "<red>Missing permission: <yellow>" + perm);
        return false;
    }

    private void msg(@NotNull final CommandSender sender, @NotNull final String miniMessage) {
        final var component = ColorUtil.mini(miniMessage);
        if (sender instanceof Player player) {
            plugin.scheduler().runAtEntity(player, () -> player.sendMessage(component));
        } else {
            sender.sendMessage(component);
        }
    }

    private void playSound(@NotNull final Player player, @NotNull final String soundName) {
        final var sounds = configManager.sounds();
        if (!sounds.enabled) return;
        final org.bukkit.Sound s = com.dravorax.arenaregendx.util.RegistryUtil.soundFromString(soundName);
        if (s != null) {
            plugin.scheduler().runAtEntity(player, () ->
                    player.playSound(player.getLocation(), s, sounds.volume, sounds.pitch));
        }
    }

    private static @NotNull String humanSize(final long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }

    private @NotNull List<String> arenaNames(@NotNull final String prefix) {
        final String p = prefix.toLowerCase(Locale.ROOT);
        return arenaManager.all().stream()
                .map(Arena::name)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p))
                .collect(Collectors.toList());
    }

    private static @NotNull List<String> filter(@NotNull final String prefix, @NotNull final List<String> options) {
        final String p = prefix.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static final Class<Component> GUARD = Component.class;
}
