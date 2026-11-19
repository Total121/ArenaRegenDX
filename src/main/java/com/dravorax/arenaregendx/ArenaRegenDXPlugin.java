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
import com.dravorax.arenaregendx.gui.GuiManager;
import com.dravorax.arenaregendx.hooks.HookManager;
import com.dravorax.arenaregendx.listeners.BlockInteractListener;
import com.dravorax.arenaregendx.listeners.ChangeTrackerListener;
import com.dravorax.arenaregendx.listeners.SelectionWandListener;
import com.dravorax.arenaregendx.nms.NmsAccess;
import com.dravorax.arenaregendx.regen.FastBlockWriter;
import com.dravorax.arenaregendx.particles.SelectionVisualizer;
import com.dravorax.arenaregendx.regen.RegenManager;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.scheduler.SchedulerProvider;
import com.dravorax.arenaregendx.snapshot.SnapshotManager;
import com.dravorax.arenaregendx.tps.TPSMonitor;
import com.dravorax.arenaregendx.util.Banner;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Main entry point for ArenaRegenDX.
 *
 * <p>This class is responsible for the deterministic, ordered bootstrapping of all
 * subsystems using constructor-based dependency injection. Managers are wired once
 * during {@link #onEnable()} and torn down cleanly in {@link #onDisable()}.</p>
 *
 * <p>The plugin is engineered for:</p>
 * <ul>
 *   <li>Folia-safe execution through a single {@link SchedulerAdapter} abstraction.</li>
 *   <li>Async-first design: only block placement touches the server thread.</li>
 *   <li>Enterprise-grade stability: no static state, deterministic cleanup, weak refs.</li>
 *   <li>Zero telemetry, zero backdoors, zero external calls — runs 100% locally.</li>
 * </ul>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ArenaRegenDXPlugin extends JavaPlugin {

    // Singleton reference — held only for API access, never for business logic.
    private static ArenaRegenDXPlugin instance;

    // ---- Core subsystems (final after onEnable) ----
    private SchedulerAdapter scheduler;
    private TPSMonitor tpsMonitor;
    private ConfigManager configManager;
    private SnapshotManager snapshotManager;
    private ArenaManager arenaManager;
    private ChangeTracker changeTracker;
    private RegenManager regenManager;
    private com.dravorax.arenaregendx.regen.AutoResetManager autoResetManager;
    private ArenaSelectionManager selectionManager;
    private SelectionVisualizer selectionVisualizer;
    private GuiManager guiManager;
    private HookManager hookManager;
    private ArdxCommand commandHandler;
    private BlockInteractListener blockInteractListener;

    @Override
    public void onEnable() {
        final long startNanos = System.nanoTime();
        instance = this;

        // ---- Phase 1: Print the console banner (early, before any logic). ----
        Banner.print(getSLF4JLogger());

        // ---- Phase 2: Detect Folia vs Paper & wire the scheduler abstraction. ----
        final boolean folia = SchedulerProvider.isFolia();
        getSLF4JLogger().info("Detected server runtime: {}", folia ? "Folia" : "Paper (or compatible)");
        this.scheduler = SchedulerProvider.create(this, folia);
        getSLF4JLogger().info("Scheduler adapter: {}", scheduler.implementationName());

        // ---- Phase 3: TPS monitor (must be ready before regen engine). ----
        this.tpsMonitor = new TPSMonitor(this, scheduler);
        this.tpsMonitor.start();

        // ---- Phase 4: Configuration (loaded sync, fast). ----
        this.configManager = new ConfigManager(this);
        this.configManager.loadAll();

        // ---- Phase 5: Storage + snapshot engine. ----
        this.snapshotManager = new SnapshotManager(this, configManager, scheduler);

        // ---- Phase 6: Arena manager (depends on snapshots). ----
        this.arenaManager = new ArenaManager(this, configManager, snapshotManager, scheduler);

        // ---- Phase 6b: Dirty-voxel tracker (bind after arenas exist). ----
        this.changeTracker = new ChangeTracker();
        this.changeTracker.bind(arenaManager::findContaining);
        applyRuntimeSettings();

        // ---- Phase 7: Regeneration engine (depends on snapshots + TPS + arenas). ----
        this.regenManager = new RegenManager(this, snapshotManager, arenaManager, tpsMonitor,
                configManager, scheduler);
        this.regenManager.start();
        this.autoResetManager = new com.dravorax.arenaregendx.regen.AutoResetManager(
                this, arenaManager, regenManager, scheduler);
        this.autoResetManager.start();

        // ---- Phase 8: Selection system + particle visualizer. ----
        this.selectionVisualizer = new SelectionVisualizer(this, scheduler, configManager);
        this.selectionVisualizer.start();
        this.selectionManager = new ArenaSelectionManager(this, configManager, selectionVisualizer);

        // ---- Phase 9: GUI manager. ----
        this.guiManager = new GuiManager(this, arenaManager, snapshotManager, regenManager,
                tpsMonitor, configManager);

        // ---- Phase 10: Async load of arenas & snapshots. ----
        snapshotManager.initializeAsync();
        arenaManager.initializeAsync();

        // ---- Phase 11: Register event listeners. ----
        registerListeners();

        // ---- Phase 12: Register commands. ----
        this.commandHandler = new ArdxCommand(this, arenaManager, snapshotManager,
                regenManager, autoResetManager, selectionManager, guiManager, configManager);
        registerCommand(commandHandler);

        // ---- Phase 13: Optional hooks (PAPI, WorldEdit, WorldGuard). ----
        this.hookManager = new HookManager(this, arenaManager, regenManager, tpsMonitor, scheduler);
        this.hookManager.enableAll();

        // ---- Phase 14: Final banner line + timing. ----
        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        getSLF4JLogger().info("ArenaRegenDX v{} enabled in {} ms.",
                com.dravorax.arenaregendx.util.RegistryUtil.getPluginVersion(this), elapsedMs);
        getSLF4JLogger().info("Block writer: {}  |  dirty tracking: {}",
                NmsAccess.status(), configManager.performance().dirtyTracking);
        getSLF4JLogger().info("Scheduler: {}  |  Folia: {}",
                scheduler.implementationName(), folia);
        getSLF4JLogger().info("Licensed to DravoraX. Restore engine ready.");
    }

    @Override
    public void onDisable() {
        // Deterministic, ordered teardown — reverse of enable order.
        getSLF4JLogger().info("ArenaRegenDX shutting down...");

        // 1. Stop accepting new regen tasks; finish/persist in-flight ones synchronously.
        if (regenManager != null) {
            regenManager.shutdown();
        }
        // 1b. Stop auto-reset timers.
        if (autoResetManager != null) {
            autoResetManager.shutdown();
        }
        // 2. Flush snapshot writes to disk (async) and wait briefly.
        if (snapshotManager != null) {
            snapshotManager.shutdown();
        }
        // 3. Persist arena metadata.
        if (arenaManager != null) {
            arenaManager.shutdown();
        }
        // 4. Visualizer / selection cleanup.
        if (selectionVisualizer != null) {
            selectionVisualizer.shutdown();
        }
        if (selectionManager != null) {
            selectionManager.clearAll();
        }
        // 5. GUI close.
        if (guiManager != null) {
            guiManager.shutdown();
        }
        // 6. Hooks.
        if (hookManager != null) {
            hookManager.disableAll();
        }
        // 7. TPS monitor.
        if (tpsMonitor != null) {
            tpsMonitor.stop();
        }
        // 8. Cancel any remaining scheduler tasks owned by us.
        if (scheduler != null) {
            scheduler.cancelOwnedTasks();
        }
        // 9. Static caches outlive the plugin instance on reload.
        FastBlockWriter.clearCache();

        instance = null;
        getSLF4JLogger().info("ArenaRegenDX disabled. Goodbye!");
    }

    /**
     * Register all Bukkit event listeners.
     */
    private void registerListeners() {
        final PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new SelectionWandListener(this, selectionManager, configManager), this);
        this.blockInteractListener = new BlockInteractListener(this, arenaManager, regenManager);
        pm.registerEvents(blockInteractListener, this);
        pm.registerEvents(new ChangeTrackerListener(changeTracker), this);
        pm.registerEvents(guiManager, this);
        pm.registerEvents(selectionManager, this);
    }

    /**
     * Register the root command, handling the case where the command alias is missing.
     */
    private void registerCommand(@NotNull final ArdxCommand handler) {
        var cmd = getCommand("ardx");
        if (cmd == null) {
            cmd = getCommand("sregen");
        }
        if (cmd == null) {
            getSLF4JLogger().error("Command 'ardx' not found in plugin.yml — commands will not work!");
            return;
        }
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);
    }

    // -----------------------------------------------------------------------
    // Public API accessors (for the developer API + internal cross-references).
    // -----------------------------------------------------------------------

    /** @return the singleton plugin instance, or {@code null} after disable. */
    public static ArenaRegenDXPlugin getInstance() {
        return instance;
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    public TPSMonitor tpsMonitor() {
        return tpsMonitor;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public SnapshotManager snapshotManager() {
        return snapshotManager;
    }

    public ArenaManager arenaManager() {
        return arenaManager;
    }

    public ChangeTracker changeTracker() {
        return changeTracker;
    }

    /** Apply performance toggles that hot paths read as volatiles. */
    public void applyRuntimeSettings() {
        final var p = configManager.performance();
        FastBlockWriter.setNmsAllowed(p.nmsWrites);
        if (changeTracker != null) {
            changeTracker.setEnabled(p.dirtyTracking);
            changeTracker.setFullThreshold(p.dirtyFullThreshold);
        }
        if (selectionVisualizer != null) {
            selectionVisualizer.restart();
        }
    }

    public RegenManager regenManager() {
        return regenManager;
    }

    public com.dravorax.arenaregendx.regen.AutoResetManager autoResetManager() {
        return autoResetManager;
    }

    public ArenaSelectionManager selectionManager() {
        return selectionManager;
    }

    public SelectionVisualizer selectionVisualizer() {
        return selectionVisualizer;
    }

    public GuiManager guiManager() {
        return guiManager;
    }

    public HookManager hookManager() {
        return hookManager;
    }

    public @Nullable BlockInteractListener blockInteractListener() {
        return blockInteractListener;
    }
}
