/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.config;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads, caches, and reloads all ArenaRegenDX configuration files.
 *
 * <p>Eight YAML files are managed:</p>
 * <ul>
 *   <li>{@code config.yml} — general settings</li>
 *   <li>{@code messages.yml} — user-facing MiniMessage strings</li>
 *   <li>{@code arenas.yml} — arena metadata (managed by ArenaManager)</li>
 *   <li>{@code gui.yml} — GUI theme/layout</li>
 *   <li>{@code performance.yml} — TPS thresholds & block budgets</li>
 *   <li>{@code storage.yml} — compression & delta storage</li>
 *   <li>{@code particles.yml} — selection visuals</li>
 *   <li>{@code sounds.yml} — sound effects</li>
 * </ul>
 *
 * <p>Each file ships with a default inside the jar's {@code resources/} directory.
 * On first load the default is extracted to the plugin data folder; on subsequent
 * loads user values are merged over the bundled defaults (so new keys appear
 * automatically after plugin updates).</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ConfigManager {

    private static final String[] FILES = {
            "config.yml", "messages.yml", "arenas.yml", "gui.yml",
            "performance.yml", "storage.yml", "particles.yml", "sounds.yml"
    };

    private final ArenaRegenDXPlugin plugin;
    private final Map<String, FileConfiguration> configs = new HashMap<>();

    // Typed settings holders (recomputed on reload).
    private Settings.GeneralSettings general;
    private Settings.PerformanceSettings performance;
    private Settings.StorageSettings storage;
    private Settings.ParticleSettings particles;
    private Settings.SoundSettings sounds;
    private Settings.GuiSettings gui;

    public ConfigManager(@NotNull final ArenaRegenDXPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Load (or reload) every config file from disk, merging bundled defaults.
     */
    public void loadAll() {
        configs.clear();
        for (final String name : FILES) {
            configs.put(name, loadWithDefaults(name));
        }
        rebuildTypedSettings();
    }

    /**
     * Reload runtime YAML without swapping {@code arenas.yml}.
     * Arena metadata is owned by {@link com.dravorax.arenaregendx.arena.ArenaManager}
     * and must not race with in-flight {@code saveArena} writes.
     */
    public void reload() {
        final FileConfiguration arenas = configs.get("arenas.yml");
        for (final String name : FILES) {
            if ("arenas.yml".equals(name)) {
                continue;
            }
            configs.put(name, loadWithDefaults(name));
        }
        if (arenas != null) {
            configs.put("arenas.yml", arenas);
        } else if (!configs.containsKey("arenas.yml")) {
            configs.put("arenas.yml", loadWithDefaults("arenas.yml"));
        }
        rebuildTypedSettings();
    }

    private void rebuildTypedSettings() {
        this.general = Settings.GeneralSettings.from(config("config.yml"));
        this.performance = Settings.PerformanceSettings.from(config("performance.yml"));
        this.storage = Settings.StorageSettings.from(config("storage.yml"));
        this.particles = Settings.ParticleSettings.from(config("particles.yml"));
        this.sounds = Settings.SoundSettings.from(config("sounds.yml"));
        this.gui = Settings.GuiSettings.from(config("gui.yml"));
    }

    /**
     * Save the arenas.yml file (used by ArenaManager).
     */
    public void saveArenas(@NotNull final FileConfiguration arenasConfig) {
        try {
            arenasConfig.save(file("arenas.yml"));
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().error("Could not save arenas.yml", e);
        }
    }

    /**
     * @param name the file name (one of {@link #FILES}).
     * @return the cached configuration, or {@code null} if not loaded.
     */
    public @Nullable FileConfiguration config(@NotNull final String name) {
        return configs.get(name);
    }

    /**
     * Convenience accessor for a message string with MiniMessage formatting.
     *
     * @param key the dotted key.
     * @return the raw string (defaults to the key if missing).
     */
    public @NotNull String message(@NotNull final String key) {
        final FileConfiguration m = configs.get("messages.yml");
        if (m == null) {
            return key;
        }
        return m.getString(key, key);
    }

    public @NotNull Settings.GeneralSettings general() { return general; }
    public @NotNull Settings.PerformanceSettings performance() { return performance; }
    public @NotNull Settings.StorageSettings storage() { return storage; }
    public @NotNull Settings.ParticleSettings particles() { return particles; }
    public @NotNull Settings.SoundSettings sounds() { return sounds; }
    public @NotNull Settings.GuiSettings gui() { return gui; }

    public void setAutoRegenOnExplosion(final boolean enabled) {
        setAndSave("config.yml", "auto-regen.on-explosion", enabled);
        setAndSave("performance.yml", "auto-match-regen", enabled);
        this.general = Settings.GeneralSettings.from(config("config.yml"));
        this.performance = Settings.PerformanceSettings.from(config("performance.yml"));
    }

    public void setAutoRegenOnFire(final boolean enabled) {
        setAndSave("config.yml", "auto-regen.on-fire", enabled);
        this.general = Settings.GeneralSettings.from(config("config.yml"));
    }

    public void setParticlesEnabled(final boolean enabled) {
        setAndSave("particles.yml", "enabled", enabled);
        this.particles = Settings.ParticleSettings.from(config("particles.yml"));
    }

    public void setRegenSpeedPercent(final int percent) {
        final int clamped = Math.max(10, Math.min(500, percent));
        setAndSave("performance.yml", "regen-speed-percent", clamped);
        this.performance = Settings.PerformanceSettings.from(config("performance.yml"));
    }

    private void setAndSave(@NotNull final String fileName, @NotNull final String path, @NotNull final Object value) {
        final FileConfiguration c = configs.get(fileName);
        if (c == null) return;
        c.set(path, value);
        try {
            c.save(file(fileName));
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().error("Could not save {}", fileName, e);
        }
    }

    /**
     * @return the data folder, creating it if necessary.
     */
    @NotNull File dataFolder() {
        final File dir = plugin.getDataFolder();
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    @NotNull File file(@NotNull final String name) {
        return new File(dataFolder(), name);
    }

    /**
     * Load a user config file, merged over the bundled default inside the jar.
     */
    private @NotNull FileConfiguration loadWithDefaults(@NotNull final String name) {
        // Extract default to disk if missing.
        final File disk = file(name);
        if (!disk.exists()) {
            try (InputStream in = plugin.getResource(name)) {
                if (in != null) {
                    //noinspection ResultOfMethodCallIgnored
                    disk.getParentFile().mkdirs();
                    java.nio.file.Files.copy(in, disk.toPath());
                } else {
                    // No bundled default — create an empty file.
                    //noinspection ResultOfMethodCallIgnored
                    disk.createNewFile();
                }
            } catch (final java.io.IOException e) {
                plugin.getSLF4JLogger().error("Could not extract default config: {}", name, e);
            }
        }

        // Load the bundled default from the jar (for merge baseline).
        final YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    defaults.load(reader);
                }
            }
        } catch (final Exception e) {
            plugin.getSLF4JLogger().warn("Could not load bundled default for {}", name, e);
        }

        // Load the user file.
        final YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(disk);
        } catch (final Exception e) {
            plugin.getSLF4JLogger().error("Could not load {} — using defaults only", name, e);
        }
        // Merge: user values override defaults.
        user.setDefaults(defaults);
        // Trigger options copy so missing keys get written back on next save.
        return user;
    }
}
