/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.commands;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.arena.ExclusionZone;
import com.dravorax.arenaregendx.arena.Selection;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.listeners.SelectionWandListener;
import com.dravorax.arenaregendx.nms.NmsAccess;
import com.dravorax.arenaregendx.regen.RegenMode;
import com.dravorax.arenaregendx.regen.RegenSpeed;
import com.dravorax.arenaregendx.scheduler.SchedulerProvider;
import com.dravorax.arenaregendx.snapshot.SnapshotMetadata;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import com.dravorax.arenaregendx.util.ColorUtil;
import com.dravorax.arenaregendx.util.Messages;
import com.dravorax.arenaregendx.util.RegistryUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Root command {@code /ardx}. All output comes from {@code messages.yml}.
 *
 * @author DravoraX
 */
public final class ArdxCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "list", "info", "create", "delete", "rename", "save", "load", "snapshots", "regen", "cancel",
            "speed", "autoreset", "setspawn", "exclude", "preview", "wand", "gui", "reload", "status", "debug", "help");
    private static final List<String> ARENA_ARG = List.of(
            "info", "delete", "rename", "save", "load", "snapshots", "regen", "cancel", "speed", "autoreset",
            "setspawn", "preview");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final long DELETE_CONFIRM_MS = 20_000L;

    private final ArenaRegenDXPlugin plugin;
    private final ArenaManager arenaManager;
    private final ConfigManager configManager;
    private final Messages messages;
    /** Sender id → pending delete confirmation. */
    private final Map<String, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();

    private record PendingDelete(@NotNull String arena, long expiresAt) {
    }

    public ArdxCommand(@NotNull final ArenaRegenDXPlugin plugin) {
        this.plugin = plugin;
        this.arenaManager = plugin.arenaManager();
        this.configManager = plugin.configManager();
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command,
                             @NotNull final String label, @NotNull final String @NotNull [] args) {
        if (args.length == 0) {
            messages.sendList(sender, "help", "label", label, "version", plugin.getPluginMeta().getVersion());
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "list" -> list(sender);
                case "info" -> info(sender, args);
                case "create" -> create(sender, args);
                case "delete" -> delete(sender, args);
                case "rename" -> rename(sender, args);
                case "save" -> save(sender, args);
                case "load" -> load(sender, args);
                case "snapshots" -> snapshots(sender, args);
                case "regen" -> regen(sender, args);
                case "cancel" -> cancel(sender, args);
                case "speed" -> speed(sender, args);
                case "autoreset" -> autoReset(sender, args);
                case "setspawn" -> setSpawn(sender, args);
                case "exclude" -> exclude(sender, args, label);
                case "preview" -> preview(sender, args);
                case "wand" -> wand(sender);
                case "gui" -> gui(sender);
                case "reload" -> reload(sender);
                case "status" -> status(sender);
                case "debug" -> debug(sender);
                case "help" -> messages.sendList(sender, "help", "label", label,
                        "version", plugin.getPluginMeta().getVersion());
                default -> messages.send(sender, "general.unknown-command", "sub", args[0], "label", label);
            }
        } catch (final IllegalArgumentException | IllegalStateException e) {
            messages.send(sender, "general.error", "reason", e.getMessage());
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Arena lifecycle
    // -----------------------------------------------------------------------

    private void list(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.use")) return;
        final List<Arena> arenas = new ArrayList<>(arenaManager.all());
        if (arenas.isEmpty()) {
            messages.send(sender, "arena.list-empty");
            return;
        }
        arenas.sort(Comparator.comparing(Arena::name));
        messages.send(sender, "arena.list-header", "count", arenas.size());
        for (final Arena a : arenas) {
            messages.send(sender, "arena.list-entry", "name", a.name(), "status", status(a),
                    "size", size(a), "world", a.worldName());
        }
    }

    private void info(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.use")) return;
        if (!usage(sender, args, 2, "/ardx info <arena>")) return;
        final Arena a = requireArena(sender, args[1]);
        if (a == null) return;
        final long remaining = plugin.autoResetManager().getRemaining(a.name());
        final float progress = plugin.regenManager().progress(a.name());
        messages.sendList(sender, "arena.info",
                "name", a.name(),
                "status", status(a) + (progress >= 0f ? " " + Math.round(progress * 100f) + "%" : ""),
                "world", a.worldName(),
                "origin", a.originX() + ", " + a.originY() + ", " + a.originZ(),
                "size", size(a),
                "blocks", String.format(Locale.ROOT, "%,d", a.blockCount()),
                "snapshot", a.latestSnapshotId() > 0 ? "#" + a.latestSnapshotId() : "none",
                "last-regen", a.lastRegeneratedAt() > 0 ? DATE_FMT.format(Instant.ofEpochMilli(a.lastRegeneratedAt())) : "never",
                "auto-reset", a.autoResetInterval() > 0
                        ? a.autoResetInterval() + "s" + (remaining > 0 ? " (next in " + remaining + "s)" : "")
                        : "off",
                "speed", RegenSpeed.label(a.regenSpeedPercent()),
                "exclusions", a.exclusions().size(),
                "spawn", a.hasSpawn() ? String.format(Locale.ROOT, "%.1f, %.1f, %.1f", a.spawnX(), a.spawnY(), a.spawnZ()) : "none",
                "dirty", plugin.changeTracker().trusted(a.name())
                        ? plugin.changeTracker().dirtyCount(a.name()) + " voxels"
                        : "untracked (next restore is full)");
    }

    private void create(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.create")) return;
        final Player player = requirePlayer(sender);
        if (player == null) return;
        if (!usage(sender, args, 2, "/ardx create <name> [autoResetSeconds] [async:on|off]")) return;
        final Selection sel = plugin.selectionManager().resolve(player, configManager.general().preferWorldEditSelection);
        final Location min = sel == null ? null : sel.min();
        final Location max = sel == null ? null : sel.max();
        if (min == null || max == null || min.getWorld() == null) {
            messages.send(sender, "selection.missing");
            return;
        }
        int autoReset = 0;
        if (args.length >= 3) {
            autoReset = parseSeconds(args[2]);
            if (autoReset < 0) {
                messages.send(sender, "general.error", "reason", "Invalid auto-reset time: " + args[2]);
                return;
            }
        }
        final boolean asyncRegen = args.length < 4 || !List.of("off", "false", "0", "no").contains(args[3].toLowerCase(Locale.ROOT));
        final Arena arena = arenaManager.createArena(args[1], min.getWorld(), min, max, autoReset, asyncRegen);
        messages.send(sender, "arena.created", "name", arena.name(), "size", size(arena));
        plugin.selectionVisualizer().showArenaPreview(player, arena);
        plugin.selectionManager().clear(player);
        playSound(player, "BLOCK_NOTE_BLOCK_PLING");
        messages.send(sender, "snapshot.saving", "name", arena.name());
        final long start = System.currentTimeMillis();
        arenaManager.captureSnapshot(arena).whenComplete((meta, err) -> {
            if (err != null) {
                messages.send(sender, "snapshot.save-failed", "reason", reason(err));
                plugin.getSLF4JLogger().error("Initial snapshot of '{}' failed", arena.name(), unwrap(err));
                return;
            }
            sendSaved(sender, meta, start);
            messages.send(sender, "arena.exclude-tip", "name", arena.name());
            plugin.autoResetManager().startCountdown(arena);
        });
    }

    private void delete(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.delete")) return;
        if (!usage(sender, args, 2, "/ardx delete <arena> [confirm]")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        final String key = sender instanceof Player p ? p.getUniqueId().toString() : "console";
        final long now = System.currentTimeMillis();
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            pendingDeletes.values().removeIf(pd -> pd.expiresAt() < now);
            pendingDeletes.put(key, new PendingDelete(arena.name(), now + DELETE_CONFIRM_MS));
            messages.send(sender, "arena.delete-confirm", "name", arena.name());
            return;
        }
        final PendingDelete pending = pendingDeletes.remove(key);
        if (pending == null || !pending.arena().equals(arena.name()) || pending.expiresAt() < now) {
            messages.send(sender, "arena.delete-expired", "name", arena.name());
            return;
        }
        if (arenaManager.deleteArena(arena.name())) {
            messages.send(sender, "arena.deleted", "name", arena.name());
        } else {
            messages.send(sender, "general.arena-not-found", "name", arena.name());
        }
    }

    private void rename(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.admin")) return;
        if (!usage(sender, args, 3, "/ardx rename <old> <new>")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        final String oldName = arena.name();
        arenaManager.renameArena(oldName, args[2]);
        messages.send(sender, "arena.renamed", "old", oldName, "new", ColorUtil.sanitizeName(args[2]));
    }

    private void setSpawn(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.admin")) return;
        if (!usage(sender, args, 2, "/ardx setspawn <arena> [clear]")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
            arena.clearSpawn();
            arenaManager.saveArena(arena);
            messages.send(sender, "arena.spawn-cleared", "name", arena.name());
            return;
        }
        final Player player = requirePlayer(sender);
        if (player == null) return;
        final Location loc = player.getLocation();
        if (!loc.getWorld().getName().equals(arena.worldName())) {
            messages.send(sender, "arena.wrong-world", "world", arena.worldName());
            return;
        }
        arena.setSpawn(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        arenaManager.saveArena(arena);
        messages.send(sender, "arena.spawn-set", "name", arena.name(),
                "location", String.format(Locale.ROOT, "%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ()));
        playSound(player, "BLOCK_NOTE_BLOCK_BELL");
    }

    private void autoReset(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.admin")) return;
        if (!usage(sender, args, 3, "/ardx autoreset <arena> <seconds|off>")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        final int seconds = parseSeconds(args[2]);
        if (seconds < 0) {
            messages.send(sender, "general.error", "reason", "Use a number of seconds (e.g. 300, 5m) or off.");
            return;
        }
        arena.setAutoResetInterval(seconds);
        arenaManager.saveArena(arena);
        plugin.autoResetManager().startCountdown(arena);
        messages.send(sender, seconds > 0 ? "arena.autoreset-set" : "arena.autoreset-off",
                "name", arena.name(), "seconds", seconds);
    }

    // -----------------------------------------------------------------------
    // Snapshots
    // -----------------------------------------------------------------------

    private void save(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.save")) return;
        if (!usage(sender, args, 2, "/ardx save <arena>")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        messages.send(sender, "snapshot.saving", "name", arena.name());
        final long start = System.currentTimeMillis();
        arenaManager.captureSnapshot(arena).whenComplete((meta, err) -> {
            if (err != null) {
                messages.send(sender, "snapshot.save-failed", "reason", reason(err));
                final Throwable cause = unwrap(err);
                if (!(cause instanceof IllegalStateException)) {
                    plugin.getSLF4JLogger().error("Snapshot save failed for '{}'", arena.name(), cause);
                }
                if (sender instanceof Player p) playSound(p, configManager.sounds().error);
                return;
            }
            sendSaved(sender, meta, start);
            if (sender instanceof Player p) playSound(p, configManager.sounds().snapshotSaved);
        });
    }

    private void load(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.load")) return;
        if (!usage(sender, args, 2, "/ardx load <arena> [snapshotId]")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        final long snapshotId;
        if (args.length >= 3) {
            try {
                snapshotId = Long.parseLong(args[2].startsWith("#") ? args[2].substring(1) : args[2]);
            } catch (final NumberFormatException e) {
                messages.send(sender, "general.error", "reason", "Invalid snapshot id: " + args[2]);
                return;
            }
        } else {
            snapshotId = arena.latestSnapshotId();
        }
        if (snapshotId <= 0) {
            messages.send(sender, "snapshot.none", "name", arena.name());
            return;
        }
        if (plugin.regenManager().isRegenerating(arena.name())) {
            messages.send(sender, "general.error", "reason", "Arena is regenerating; cancel it first.");
            return;
        }
        messages.send(sender, "snapshot.loading", "id", snapshotId);
        arenaManager.selectSnapshot(arena, snapshotId).whenComplete((ignored, err) -> {
            if (err != null) {
                messages.send(sender, "snapshot.load-failed", "reason", reason(err));
                return;
            }
            messages.send(sender, "snapshot.selected", "id", snapshotId, "name", arena.name());
            if (sender instanceof Player p) playSound(p, configManager.sounds().snapshotLoaded);
        });
    }

    private void snapshots(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.load")) return;
        if (!usage(sender, args, 2, "/ardx snapshots <arena>")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        plugin.snapshotManager().listSnapshots(arena.name()).whenComplete((list, err) -> {
            if (err != null) {
                messages.send(sender, "snapshot.load-failed", "reason", reason(err));
                return;
            }
            if (list.isEmpty()) {
                messages.send(sender, "snapshot.none", "name", arena.name());
                return;
            }
            messages.send(sender, "snapshot.list-header", "name", arena.name(), "count", list.size());
            for (final SnapshotMetadata m : list) {
                messages.send(sender, "snapshot.list-entry",
                        "id", m.snapshotId(),
                        "date", DATE_FMT.format(Instant.ofEpochMilli(m.createdAt())),
                        "size", m.sizeX() + "x" + m.sizeY() + "x" + m.sizeZ(),
                        "file-size", humanSize(m.fileSize()),
                        "active", m.snapshotId() == arena.latestSnapshotId()
                                ? messages.render("snapshot.active-marker") : "");
            }
        });
    }

    private void sendSaved(@NotNull final CommandSender sender, @NotNull final SnapshotMetadata meta, final long start) {
        messages.send(sender, "snapshot.saved",
                "id", meta.snapshotId(),
                "blocks", String.format(Locale.ROOT, "%,d", meta.blockCount()),
                "size", humanSize(meta.fileSize()),
                "ms", System.currentTimeMillis() - start);
    }

    // -----------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------

    private void regen(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.regen")) return;
        if (!usage(sender, args, 2, "/ardx regen <arena> [instant|chunk_by_chunk|layer_by_layer|animated]")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        RegenMode mode = RegenMode.INSTANT;
        if (args.length >= 3) {
            try {
                mode = RegenMode.valueOf(args[2].toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (final IllegalArgumentException e) {
                messages.send(sender, "regen.unknown-mode",
                        "modes", String.join(", ", Arrays.stream(RegenMode.values()).map(m -> m.name().toLowerCase(Locale.ROOT)).toList()));
                return;
            }
        }
        final boolean safe = configManager.general().safeModeDefault && !sender.hasPermission("arenaregendx.bypass");
        messages.send(sender, "regen.started", "name", arena.name(), "mode", mode.name().toLowerCase(Locale.ROOT));
        final long start = System.currentTimeMillis();
        plugin.regenManager().regen(arena.name(), mode, safe).whenComplete((task, err) -> {
            if (err != null) {
                messages.send(sender, "regen.failed", "reason", reason(err));
                return;
            }
            messages.send(sender, "regen.done",
                    "name", arena.name(),
                    "ms", System.currentTimeMillis() - start,
                    "written", String.format(Locale.ROOT, "%,d", task.blocksApplied()),
                    "skipped", String.format(Locale.ROOT, "%,d", task.blocksSkipped()),
                    "excluded", String.format(Locale.ROOT, "%,d", task.blocksExcluded()));
        });
    }

    private void cancel(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.cancel")) return;
        if (args.length < 2) {
            plugin.regenManager().cancelAll();
            messages.send(sender, "regen.cancelled-all");
            return;
        }
        final String name = ColorUtil.sanitizeName(args[1]);
        if (plugin.regenManager().cancel(name)) {
            messages.send(sender, "regen.cancelled", "name", name);
        } else {
            messages.send(sender, "regen.not-running", "name", args[1]);
        }
    }

    private void speed(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.admin")) return;
        if (!usage(sender, args, 2, "/ardx speed <global|arena> [slow|normal|fast|max|inherit|10-500]")) return;
        final int global = configManager.performance().regenSpeedPercent;
        if (args[1].equalsIgnoreCase("global")) {
            if (args.length < 3) {
                messages.send(sender, "speed.global", "speed", RegenSpeed.label(global));
                return;
            }
            final int pct = RegenSpeed.parsePresetOrPercent(args[2]);
            if (pct < 0) {
                messages.send(sender, "speed.no-inherit");
                return;
            }
            configManager.setRegenSpeedPercent(pct);
            messages.send(sender, "speed.global-set", "speed", RegenSpeed.label(configManager.performance().regenSpeedPercent));
            return;
        }
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        if (args.length >= 3) {
            arena.setRegenSpeedPercent(RegenSpeed.parsePresetOrPercent(args[2]));
            arenaManager.saveArena(arena);
        }
        messages.send(sender, args.length >= 3 ? "speed.arena-set" : "speed.arena",
                "name", arena.name(),
                "speed", RegenSpeed.label(arena.regenSpeedPercent()),
                "effective", RegenSpeed.resolve(arena.regenSpeedPercent(), global));
    }

    // -----------------------------------------------------------------------
    // Exclusions
    // -----------------------------------------------------------------------

    private void exclude(@NotNull final CommandSender sender, @NotNull final String[] args, @NotNull final String label) {
        if (!checkPerm(sender, "arenaregendx.create")) return;
        if (args.length < 2) {
            messages.sendList(sender, "exclude.help", "label", label);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> excludeCuboid(sender, args);
            case "sphere", "cylinder" -> excludeRound(sender, args, args[1].equalsIgnoreCase("sphere"));
            case "remove" -> excludeRemove(sender, args);
            case "list" -> excludeList(sender, args);
            case "show" -> {
                final Player player = requirePlayer(sender);
                if (player == null || !usage(sender, args, 3, "/ardx exclude show <arena>")) return;
                final Arena arena = requireArena(sender, args[2]);
                if (arena == null) return;
                plugin.selectionVisualizer().showArenaPreview(player, arena);
                messages.send(sender, "exclude.show");
            }
            case "hide" -> {
                if (sender instanceof Player p) plugin.selectionVisualizer().hidePreview(p);
                messages.send(sender, "exclude.hidden");
            }
            default -> messages.sendList(sender, "exclude.help", "label", label);
        }
    }

    private void excludeCuboid(@NotNull final CommandSender sender, @NotNull final String[] args) {
        final Player player = requirePlayer(sender);
        if (player == null || !usage(sender, args, 4, "/ardx exclude add <arena> <zone>")) return;
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return;
        final String zoneName = zoneName(sender, args[3]);
        if (zoneName == null) return;
        final Selection sel = plugin.selectionManager().resolve(player, configManager.general().preferWorldEditSelection);
        final Location min = sel == null ? null : sel.min();
        final Location max = sel == null ? null : sel.max();
        if (min == null || max == null) {
            messages.send(sender, "exclude.select-first");
            return;
        }
        if (!min.getWorld().getName().equals(arena.worldName())) {
            messages.send(sender, "arena.wrong-world", "world", arena.worldName());
            return;
        }
        addZone(player, arena, ExclusionZone.cuboid(zoneName, min, max));
        plugin.selectionManager().clear(player);
    }

    private void excludeRound(@NotNull final CommandSender sender, @NotNull final String[] args, final boolean sphere) {
        final Player player = requirePlayer(sender);
        if (player == null) return;
        if (!usage(sender, args, sphere ? 4 : 5, sphere
                ? "/ardx exclude sphere <arena> <zone> [radius]"
                : "/ardx exclude cylinder <arena> <zone> <radius>")) return;
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return;
        final String zoneName = zoneName(sender, args[3]);
        if (zoneName == null) return;
        double radius = 8.0;
        if (args.length >= 5) {
            try {
                radius = Double.parseDouble(args[4]);
            } catch (final NumberFormatException e) {
                radius = -1;
            }
        }
        if (!(radius >= 0.5 && radius <= 512.0)) {
            messages.send(sender, "exclude.invalid-radius");
            return;
        }
        final Location at = player.getLocation();
        if (!at.getWorld().getName().equals(arena.worldName())) {
            messages.send(sender, "arena.wrong-world", "world", arena.worldName());
            return;
        }
        addZone(player, arena, sphere
                ? ExclusionZone.sphere(zoneName, at, radius)
                : ExclusionZone.cylinder(zoneName, at, radius, arena.originY(), arena.originY() + arena.sizeY() - 1));
    }

    private void addZone(@NotNull final Player player, @NotNull final Arena arena, @NotNull final ExclusionZone zone) {
        arena.addExclusion(zone);
        arenaManager.onExclusionsChanged(arena);
        plugin.selectionVisualizer().showArenaPreview(player, arena);
        messages.send(player, "exclude.added", "zone", zone.toString(), "name", arena.name());
    }

    private void excludeRemove(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!usage(sender, args, 4, "/ardx exclude remove <arena> <zone>")) return;
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return;
        if (arena.removeExclusion(args[3])) {
            arenaManager.onExclusionsChanged(arena);
            messages.send(sender, "exclude.removed", "zone", args[3], "name", arena.name());
            if (sender instanceof Player p) plugin.selectionVisualizer().showArenaPreview(p, arena);
        } else {
            messages.send(sender, "exclude.not-found", "zone", args[3], "name", arena.name());
        }
    }

    private void excludeList(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!usage(sender, args, 3, "/ardx exclude list <arena>")) return;
        final Arena arena = requireArena(sender, args[2]);
        if (arena == null) return;
        if (arena.exclusions().isEmpty()) {
            messages.send(sender, "exclude.list-empty", "name", arena.name());
            return;
        }
        messages.send(sender, "exclude.list-header", "name", arena.name());
        for (final ExclusionZone z : arena.exclusions()) {
            messages.send(sender, "exclude.list-entry", "zone", z.toString());
        }
    }

    private @Nullable String zoneName(@NotNull final CommandSender sender, @NotNull final String raw) {
        final String name = ColorUtil.sanitizeName(raw);
        if (!ColorUtil.isValidName(name)) {
            messages.send(sender, "general.error", "reason", "Invalid zone name '" + raw + "' (a-z, 0-9, - and _).");
            return null;
        }
        return name;
    }

    // -----------------------------------------------------------------------
    // Tools
    // -----------------------------------------------------------------------

    private void preview(@NotNull final CommandSender sender, @NotNull final String[] args) {
        if (!checkPerm(sender, "arenaregendx.wand")) return;
        final Player player = requirePlayer(sender);
        if (player == null || !usage(sender, args, 2, "/ardx preview <arena>")) return;
        final Arena arena = requireArena(sender, args[1]);
        if (arena == null) return;
        plugin.selectionVisualizer().showArenaPreview(player, arena);
        messages.send(sender, "preview.showing", "name", arena.name());
    }

    private void wand(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.wand")) return;
        final Player player = requirePlayer(sender);
        if (player == null) return;
        final var leftover = player.getInventory().addItem(SelectionWandListener.createWand(plugin));
        leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        messages.sendList(sender, "selection.wand-given");
        playSound(player, "ITEM_ARMOR_EQUIP_GOLD");
    }

    private void gui(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.gui")) return;
        final Player player = requirePlayer(sender);
        if (player != null) {
            plugin.guiManager().openMain(player);
        }
    }

    private void reload(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.reload")) return;
        configManager.reload();
        plugin.applyRuntimeSettings();
        messages.send(sender, "general.reloaded");
    }

    private void status(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.admin")) return;
        final TPSMonitor tps = plugin.tpsMonitor();
        final Runtime rt = Runtime.getRuntime();
        final int speed = configManager.performance().regenSpeedPercent;
        messages.sendList(sender, "status",
                "tps", fmt(tps.currentTPS()), "tps5", fmt(tps.tps5m()), "tps15", fmt(tps.tps15m()),
                "mspt", fmt(tps.averageTickTimeMs()),
                "budget", tps.adaptiveBlockBudget(),
                "budget-ms", fmt(tps.adaptiveNanosBudget() / 1_000_000.0),
                "speed", RegenSpeed.label(speed),
                "memory", ((rt.totalMemory() - rt.freeMemory()) >> 20) + "MB / " + (rt.maxMemory() >> 20) + "MB",
                "cache", plugin.snapshotManager().cachedCount() + " (" + humanSize(plugin.snapshotManager().cachedBytes()) + ")",
                "writer", NmsAccess.status(),
                "dirty", plugin.changeTracker().enabled() ? "on" : "off",
                "arenas", arenaManager.count(),
                "queue", plugin.regenManager().queueSize(),
                "running", plugin.regenManager().isRegenerating() ? "yes" : "no");
    }

    private void debug(@NotNull final CommandSender sender) {
        if (!checkPerm(sender, "arenaregendx.debug")) return;
        messages.sendList(sender, "debug",
                "scheduler", plugin.scheduler().implementationName(),
                "folia", SchedulerProvider.isFolia(),
                "java", System.getProperty("java.version"),
                "server", Bukkit.getVersion(),
                "writer", NmsAccess.status(),
                "nbt", NmsAccess.nbtAvailable());
        for (final Arena a : arenaManager.all()) {
            messages.send(sender, "debug-arena",
                    "name", a.name(), "size", size(a), "snapshot", a.latestSnapshotId(),
                    "exclusions", a.exclusions().size(),
                    "dirty", plugin.changeTracker().trusted(a.name()) ? plugin.changeTracker().dirtyCount(a.name()) : "-",
                    "status", status(a));
        }
    }

    // -----------------------------------------------------------------------
    // Tab completion
    // -----------------------------------------------------------------------

    @Override
    public @NotNull List<String> onTabComplete(@NotNull final CommandSender sender, @NotNull final Command command,
                                               @NotNull final String label, @NotNull final String @NotNull [] args) {
        if (args.length == 1) {
            return filter(args[0], SUBCOMMANDS);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("speed")) {
                final List<String> out = new ArrayList<>(filter(args[1], List.of("global")));
                out.addAll(arenaNames(args[1]));
                return out;
            }
            if (ARENA_ARG.contains(sub)) {
                return arenaNames(args[1]);
            }
            if (sub.equals("exclude")) {
                return filter(args[1], List.of("add", "sphere", "cylinder", "remove", "list", "show", "hide"));
            }
            return List.of();
        }
        if (args.length == 3) {
            return switch (sub) {
                case "delete" -> filter(args[2], List.of("confirm"));
                case "regen" -> filter(args[2], Arrays.stream(RegenMode.values())
                        .map(m -> m.name().toLowerCase(Locale.ROOT)).toList());
                case "speed" -> filter(args[2], List.of("slow", "normal", "fast", "max", "inherit"));
                case "autoreset" -> filter(args[2], List.of("off", "300", "600", "1800", "5m", "10m", "30m"));
                case "setspawn" -> filter(args[2], List.of("clear"));
                case "create" -> filter(args[2], List.of("0", "300", "600", "10m"));
                case "load" -> {
                    final Arena a = arenaManager.getArena(args[1]);
                    yield a == null || a.latestSnapshotId() <= 0 ? List.of() : List.of(Long.toString(a.latestSnapshotId()));
                }
                case "exclude" -> args[1].equalsIgnoreCase("hide") ? List.of() : arenaNames(args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("exclude")) {
            final Arena arena = arenaManager.getArena(args[2]);
            if (args[1].equalsIgnoreCase("remove")) {
                return arena == null ? List.of() : filter(args[3], arena.exclusions().stream().map(ExclusionZone::name).toList());
            }
            if (List.of("add", "sphere", "cylinder").contains(args[1].toLowerCase(Locale.ROOT))) {
                return filter(args[3], List.of("spawn", "lobby", "safe"));
            }
        }
        if (args.length == 4 && sub.equals("create")) {
            return filter(args[3], List.of("on", "off"));
        }
        if (args.length == 5 && sub.equals("exclude")
                && (args[1].equalsIgnoreCase("sphere") || args[1].equalsIgnoreCase("cylinder"))) {
            return filter(args[4], List.of("8", "12", "16", "24", "32"));
        }
        return List.of();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private @Nullable Arena requireArena(@NotNull final CommandSender sender, @NotNull final String raw) {
        final Arena arena = arenaManager.getArena(raw);
        if (arena == null) {
            messages.send(sender, "general.arena-not-found", "name", raw);
        }
        return arena;
    }

    private @Nullable Player requirePlayer(@NotNull final CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "general.player-only");
        return null;
    }

    private boolean usage(@NotNull final CommandSender sender, @NotNull final String[] args, final int min,
                          @NotNull final String usage) {
        if (args.length >= min) {
            return true;
        }
        messages.send(sender, "general.usage", "usage", usage);
        return false;
    }

    private boolean checkPerm(@NotNull final CommandSender sender, @NotNull final String perm) {
        if (sender.hasPermission(perm) || sender.hasPermission("arenaregendx.admin")
                || sender.hasPermission("sregen.admin")) {
            return true;
        }
        messages.send(sender, "general.no-permission", "perm", perm);
        return false;
    }

    private void playSound(@NotNull final Player player, @NotNull final String soundName) {
        final var sounds = configManager.sounds();
        if (!sounds.enabled) return;
        final Sound s = RegistryUtil.soundFromString(soundName);
        if (s != null) {
            plugin.scheduler().runAtEntity(player, () -> player.playSound(player.getLocation(), s, sounds.volume, sounds.pitch));
        }
    }

    /** Parse "300", "5m", "1h", "off" → seconds; -1 if invalid. */
    static int parseSeconds(@NotNull final String raw) {
        final String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("off") || s.equals("none") || s.equals("disable")) {
            return 0;
        }
        try {
            final long value;
            if (s.endsWith("h")) {
                value = Long.parseLong(s.substring(0, s.length() - 1)) * 3600L;
            } else if (s.endsWith("m")) {
                value = Long.parseLong(s.substring(0, s.length() - 1)) * 60L;
            } else if (s.endsWith("s")) {
                value = Long.parseLong(s.substring(0, s.length() - 1));
            } else {
                value = Long.parseLong(s);
            }
            return value < 0 || value > 7L * 24 * 3600 ? -1 : (int) value;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static @NotNull Throwable unwrap(@NotNull final Throwable t) {
        Throwable cur = t;
        while (cur instanceof CompletionException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static @NotNull String reason(@NotNull final Throwable t) {
        final Throwable cause = unwrap(t);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static @NotNull String status(@NotNull final Arena a) {
        return a.status().name().toLowerCase(Locale.ROOT);
    }

    private static @NotNull String size(@NotNull final Arena a) {
        return a.sizeX() + "x" + a.sizeY() + "x" + a.sizeZ();
    }

    private static @NotNull String fmt(final double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static @NotNull String humanSize(final long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private @NotNull List<String> arenaNames(@NotNull final String prefix) {
        final String p = prefix.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final Arena a : arenaManager.all()) {
            if (a.name().startsWith(p)) {
                out.add(a.name());
            }
        }
        out.sort(null);
        return out;
    }

    private static @NotNull List<String> filter(@NotNull final String prefix, @NotNull final List<String> options) {
        final String p = prefix.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }
}
