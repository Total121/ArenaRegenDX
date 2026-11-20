/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx;

import com.dravorax.arenaregendx.arena.ArenaManager;
import com.dravorax.arenaregendx.arena.ArenaSelectionManager;
import com.dravorax.arenaregendx.arena.ChangeTracker;
import com.dravorax.arenaregendx.commands.ArdxCommand;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.config.Settings;
import com.dravorax.arenaregendx.gui.GuiManager;
import com.dravorax.arenaregendx.hooks.HookManager;
import com.dravorax.arenaregendx.listeners.BlockInteractListener;
import com.dravorax.arenaregendx.listeners.ChangeTrackerListener;
import com.dravorax.arenaregendx.listeners.SelectionWandListener;
import com.dravorax.arenaregendx.nms.NmsAccess;
import com.dravorax.arenaregendx.particles.SelectionVisualizer;
import com.dravorax.arenaregendx.regen.AutoResetManager;
import com.dravorax.arenaregendx.regen.FastBlockWriter;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.scheduler.SchedulerProvider;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import com.dravorax.arenaregendx.util.Banner;
import com.dravorax.arenaregendx.util.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Main entry point for ArenaRegenDX.
 *
 * <p>Subsystems are wired once in {@link #onEnable()} with constructor
 * injection and torn down in reverse order in {@link #onDisable()}.</p>
 *
 * <ul>
 *   <li>Folia-safe: all scheduling goes through one {@link SchedulerAdapter}.</li>
 *   <li>Async-first: only block and entity access touch region / main threads.</li>
 *   <li>No telemetry, no external network calls.</li>
 * </ul>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ArenaRegenDXPlugin extends JavaPlugin {

    private static volatile ArenaRegenDXPlugin instance;

    private SchedulerAdapter scheduler;
    private TPSMonitor tpsMonitor;
    private ConfigManager configManager;
    private Messages messages;
    private SnapshotManager snapshotManager;
    private ArenaManager arenaManager;
    private ChangeTracker changeTracker;
    private RegenManager regenManager;
    private AutoResetManager autoResetManager;
    private ArenaSelectionManager selectionManager;
    private SelectionVisualizer selectionVisualizer;
    private GuiManager guiManager;
    private HookManager hookManager;
    private BlockInteractListener blockInteractListener;

    @Override
    public void onEnable() {
        final long startNanos = System.nanoTime();
        instance = this;

        // Configuration first: everything else reads it.
        this.configManager = new ConfigManager(this);
        this.configManager.loadAll();
        if (configManager.general().banner) {
            Banner.print(getSLF4JLogger(), getPluginMeta().getVersion());
        }

        final boolean folia = SchedulerProvider.isFolia();
        this.scheduler = SchedulerProvider.create(this, folia);
        this.messages = new Messages(configManager, scheduler);

        this.tpsMonitor = new TPSMonitor(this, scheduler);
        this.tpsMonitor.start();

        this.snapshotManager = new SnapshotManager(this, configManager, scheduler);
        this.arenaManager = new ArenaManager(this, configManager, snapshotManager, scheduler);
        this.changeTracker = new ChangeTracker();
        this.changeTracker.bind(arenaManager::findContaining);

        this.regenManager = new RegenManager(this, snapshotManager, arenaManager, tpsMonitor, configManager, scheduler);
        this.autoResetManager = new AutoResetManager(this, arenaManager, regenManager, scheduler);
        this.selectionVisualizer = new SelectionVisualizer(this, scheduler, configManager);
        this.selectionManager = new ArenaSelectionManager(selectionVisualizer);
        this.guiManager = new GuiManager(this, arenaManager, snapshotManager, regenManager, tpsMonitor, configManager);

        // Arenas load synchronously (YAML is already parsed) so nothing observes a half-loaded state.
        arenaManager.load();
        applyRuntimeSettings();

        regenManager.start();
        autoResetManager.start();
        autoResetManager.initializeAll();
        selectionVisualizer.start();

        registerListeners();
        registerCommand();

        this.hookManager = new HookManager(this, arenaManager, regenManager, autoResetManager, tpsMonitor);
        this.hookManager.enableAll();

        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        getSLF4JLogger().info("ArenaRegenDX {} enabled in {} ms on {} | writer: {} | dirty tracking: {}",
                getPluginMeta().getVersion(), elapsedMs, scheduler.implementationName(),
                NmsAccess.status(), configManager.performance().dirtyTracking);
    }

    @Override
    public void onDisable() {
        // Reverse of enable order. Each step is guarded so one failure never blocks the rest.
        safely("hooks", () -> hookManager.disableAll(), hookManager);
        safely("regen queue", () -> regenManager.shutdown(), regenManager);
        safely("auto-reset", () -> autoResetManager.shutdown(), autoResetManager);
        safely("visualizer", () -> selectionVisualizer.shutdown(), selectionVisualizer);
        safely("selections", () -> selectionManager.clearAll(), selectionManager);
        safely("GUI", () -> guiManager.shutdown(), guiManager);
        safely("arena metadata", () -> arenaManager.shutdown(), arenaManager);
        safely("snapshot storage", () -> snapshotManager.shutdown(), snapshotManager);
        safely("TPS monitor", () -> tpsMonitor.stop(), tpsMonitor);
        safely("scheduler", () -> scheduler.cancelOwnedTasks(), scheduler);
        instance = null;
        getSLF4JLogger().info("ArenaRegenDX disabled.");
    }

    private void safely(@NotNull final String what, @NotNull final Runnable step, @Nullable final Object target) {
        if (target == null) {
            return;
        }
        try {
            step.run();
        } catch (final Throwable t) {
            getSLF4JLogger().error("Error while shutting down {}", what, t);
        }
    }

    private void registerListeners() {
        final PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new SelectionWandListener(this, selectionManager, configManager), this);
        this.blockInteractListener = new BlockInteractListener(this, arenaManager, regenManager);
        pm.registerEvents(blockInteractListener, this);
        pm.registerEvents(new ChangeTrackerListener(changeTracker), this);
        pm.registerEvents(guiManager, this);
        pm.registerEvents(selectionManager, this);
    }

    private void registerCommand() {
        final PluginCommand cmd = getCommand("ardx");
        if (cmd == null) {
            getSLF4JLogger().error("Command 'ardx' missing from plugin.yml - commands are disabled.");
            return;
        }
        final ArdxCommand handler = new ArdxCommand(this);
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);
    }

    /** Re-apply settings that hot paths cache (called on enable and after /ardx reload). */
    public void applyRuntimeSettings() {
        final Settings.PerformanceSettings p = configManager.performance();
        FastBlockWriter.setNmsAllowed(p.nmsWrites);
        NmsAccess.setNbtEnabled(p.nbtTiles);
        tpsMonitor.configure(p.maxBlockBudget, p.tpsFull, p.tpsThrottle, p.tpsCrawl, p.maxTickMillis);
        changeTracker.setFullThreshold(p.dirtyFullThreshold);
        changeTracker.setEnabled(p.dirtyTracking);
        selectionVisualizer.restart();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** @return the plugin instance, or {@code null} while disabled. */
    public static @Nullable ArenaRegenDXPlugin getInstance() {
        return instance;
    }

    public @NotNull SchedulerAdapter scheduler() { return scheduler; }
    public @NotNull TPSMonitor tpsMonitor() { return tpsMonitor; }
    public @NotNull ConfigManager configManager() { return configManager; }
    public @NotNull Messages messages() { return messages; }
    public @NotNull SnapshotManager snapshotManager() { return snapshotManager; }
    public @NotNull ArenaManager arenaManager() { return arenaManager; }
    public @NotNull ChangeTracker changeTracker() { return changeTracker; }
    public @NotNull RegenManager regenManager() { return regenManager; }
    public @NotNull AutoResetManager autoResetManager() { return autoResetManager; }
    public @NotNull ArenaSelectionManager selectionManager() { return selectionManager; }
    public @NotNull SelectionVisualizer selectionVisualizer() { return selectionVisualizer; }
    public @NotNull GuiManager guiManager() { return guiManager; }
    public @NotNull HookManager hookManager() { return hookManager; }
    public @Nullable BlockInteractListener blockInteractListener() { return blockInteractListener; }
}
