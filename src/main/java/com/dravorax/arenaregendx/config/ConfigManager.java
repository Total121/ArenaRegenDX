/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.config;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads, caches, and reloads all ArenaRegenDX configuration files.
 *
 * <p>Managed files: {@code config.yml}, {@code messages.yml}, {@code arenas.yml},
 * {@code gui.yml}, {@code performance.yml}, {@code storage.yml},
 * {@code particles.yml}, {@code sounds.yml}.</p>
 *
 * <p>Each file ships with a default inside the jar. Missing files are extracted
 * on first load; existing files are read with the bundled file as defaults, so
 * keys added by a plugin update work without editing the user file.</p>
 *
 * <p>Typed settings are immutable snapshots published through volatile fields,
 * so region threads always see a consistent view after {@code /ardx reload}.
 * All writes go through a temp file + atomic move, so a crash mid-save can
 * never truncate a config.</p>
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
    private final Map<String, FileConfiguration> configs = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    /** Files that failed to parse; never overwritten so the user's edits are not lost. */
    private final java.util.Set<String> unparsable = ConcurrentHashMap.newKeySet();

    private volatile Settings.GeneralSettings general;
    private volatile Settings.PerformanceSettings performance;
    private volatile Settings.StorageSettings storage;
    private volatile Settings.ParticleSettings particles;
    private volatile Settings.SoundSettings sounds;
    private volatile Settings.GuiSettings gui;

    public ConfigManager(@NotNull final ArenaRegenDXPlugin plugin) {
        this.plugin = plugin;
    }

    /** Load every config file from disk, merging bundled defaults. */
    public void loadAll() {
        for (final String name : FILES) {
            configs.put(name, loadWithDefaults(name));
        }
        rebuildTypedSettings();
    }

    /**
     * Reload runtime YAML. {@code arenas.yml} is owned by the arena manager
     * (the in-memory arena map is the source of truth) and is never re-read here.
     */
    public void reload() {
        for (final String name : FILES) {
            if (!"arenas.yml".equals(name)) {
                configs.put(name, loadWithDefaults(name));
            }
        }
        rebuildTypedSettings();
    }

    private void rebuildTypedSettings() {
        this.general = Settings.GeneralSettings.from(require("config.yml"));
        this.performance = Settings.PerformanceSettings.from(require("performance.yml"));
        this.storage = Settings.StorageSettings.from(require("storage.yml"));
        this.particles = Settings.ParticleSettings.from(require("particles.yml"));
        this.sounds = Settings.SoundSettings.from(require("sounds.yml"));
        this.gui = Settings.GuiSettings.from(require("gui.yml"));
    }

    /** @return the cached configuration, or {@code null} if not loaded. */
    public @Nullable FileConfiguration config(@NotNull final String name) {
        return configs.get(name);
    }

    private @NotNull FileConfiguration require(@NotNull final String name) {
        final FileConfiguration c = configs.get(name);
        return c != null ? c : new YamlConfiguration();
    }

    /**
     * @param key the dotted key in messages.yml.
     * @return the raw MiniMessage template, or the key itself if missing.
     */
    public @NotNull String message(@NotNull final String key) {
        final FileConfiguration m = configs.get("messages.yml");
        if (m == null) {
            return key;
        }
        // getString(path) consults the bundled defaults; getString(path, def) would not,
        // so keys added in an update would show up raw on servers with an older file.
        final String value = m.getString(key);
        return value != null ? value : key;
    }

    /** @return a list message (one line per entry); a plain string becomes one line. */
    public @NotNull java.util.List<String> messageList(@NotNull final String key) {
        final FileConfiguration m = configs.get("messages.yml");
        if (m == null) {
            return java.util.List.of(key);
        }
        if (m.isList(key)) {
            return m.getStringList(key);
        }
        final String single = m.getString(key);
        return java.util.List.of(single != null ? single : key);
    }

    public @NotNull Settings.GeneralSettings general() { return general; }
    public @NotNull Settings.PerformanceSettings performance() { return performance; }
    public @NotNull Settings.StorageSettings storage() { return storage; }
    public @NotNull Settings.ParticleSettings particles() { return particles; }
    public @NotNull Settings.SoundSettings sounds() { return sounds; }
    public @NotNull Settings.GuiSettings gui() { return gui; }

    public void setAutoRegenOnExplosion(final boolean enabled) {
        setAndSave("config.yml", "auto-regen.on-explosion", enabled);
        this.general = Settings.GeneralSettings.from(require("config.yml"));
    }

    public void setAutoRegenOnFire(final boolean enabled) {
        setAndSave("config.yml", "auto-regen.on-fire", enabled);
        this.general = Settings.GeneralSettings.from(require("config.yml"));
    }

    public void setParticlesEnabled(final boolean enabled) {
        setAndSave("particles.yml", "enabled", enabled);
        this.particles = Settings.ParticleSettings.from(require("particles.yml"));
    }

    public void setRegenSpeedPercent(final int percent) {
        setAndSave("performance.yml", "regen-speed-percent", Math.max(10, Math.min(500, percent)));
        this.performance = Settings.PerformanceSettings.from(require("performance.yml"));
    }

    private void setAndSave(@NotNull final String fileName, @NotNull final String path, @NotNull final Object value) {
        final FileConfiguration c = configs.get(fileName);
        if (c == null) return;
        if (unparsable.contains(fileName)) {
            plugin.getSLF4JLogger().warn("Not saving {}: it has YAML errors. Fix it and run /ardx reload.", fileName);
            c.set(path, value); // still apply in memory for this session
            return;
        }
        synchronized (writeLock) {
            c.set(path, value);
            writeAtomically(fileName, c.saveToString());
        }
    }

    /**
     * Atomically replace a file in the data folder with {@code content}.
     *
     * @return true on success.
     */
    public boolean writeAtomically(@NotNull final String fileName, @NotNull final String content) {
        if (unparsable.contains(fileName)) {
            plugin.getSLF4JLogger().error("Refusing to overwrite {}: it had YAML errors at load. Fix it and restart.", fileName);
            return false;
        }
        final Path target = file(fileName).toPath();
        final Path tmp = target.resolveSibling(fileName + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("Could not save {}", fileName, e);
            return false;
        }
    }

    /** @return the data folder, creating it if necessary. */
    @NotNull File dataFolder() {
        final File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getSLF4JLogger().error("Could not create data folder {}", dir.getAbsolutePath());
        }
        return dir;
    }

    @NotNull File file(@NotNull final String name) {
        return new File(dataFolder(), name);
    }

    /** Load a user config file with the bundled jar copy as defaults. */
    private @NotNull FileConfiguration loadWithDefaults(@NotNull final String name) {
        final File disk = file(name);
        if (!disk.exists()) {
            try (InputStream in = plugin.getResource(name)) {
                if (in != null) {
                    Files.copy(in, disk.toPath());
                }
            } catch (final IOException e) {
                plugin.getSLF4JLogger().error("Could not extract default config {}", name, e);
            }
        }

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

        final YamlConfiguration user = new YamlConfiguration();
        unparsable.remove(name);
        if (disk.exists()) {
            try {
                user.load(disk);
            } catch (final Exception e) {
                unparsable.add(name);
                plugin.getSLF4JLogger().error("Could not parse {} - using bundled defaults. Fix the YAML and run /ardx reload.",
                        name, e);
            }
        }
        user.setDefaults(defaults);
        return user;
    }
}
