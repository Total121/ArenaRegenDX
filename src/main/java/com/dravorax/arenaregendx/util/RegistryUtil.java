/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2026 DravoraX. All rights reserved.
 */

package com.dravorax.arenaregendx.util;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bridges deprecated enum-based Bukkit APIs to the modern Registry-based API.
 *
 * <p>Since Paper 1.21+, many enums (Sound, PotionEffectType, etc.) had their
 * {@code name()} and {@code valueOf(String)} methods deprecated in favour of
 * the {@link Registry} system with {@link NamespacedKey}s. This class provides
 * clean lookup helpers that avoid deprecation warnings while maintaining
 * backward compatibility with config files that use the old enum-name format.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class RegistryUtil {

    private RegistryUtil() {
        // utility
    }

    /** Lazily built index: legacy enum-style names and namespaced keys → Sound. */
    private static volatile Map<String, Sound> soundIndex;

    /**
     * Look up a Sound by name. Accepts the legacy enum format
     * ({@code "BLOCK_NOTE_BLOCK_PLING"}) and namespaced keys
     * ({@code "minecraft:block.note_block.pling"} or {@code "block.note_block.pling"}).
     *
     * <p>The previous implementation turned every underscore into a dot, so
     * {@code BLOCK_NOTE_BLOCK_PLING} became {@code block.note.block.pling} and
     * most configured sounds silently never played. This version indexes the
     * registry once and maps each key back to its legacy constant name.</p>
     *
     * @param name the sound name.
     * @return the Sound, or null if not found.
     */
    public static @Nullable Sound soundFromString(@Nullable final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        final String trimmed = name.trim();
        final NamespacedKey key = trimmed.indexOf('.') >= 0 || trimmed.indexOf(':') >= 0
                ? NamespacedKey.fromString(trimmed.toLowerCase(Locale.ROOT))
                : null;
        if (key != null) {
            final Sound direct = Registry.SOUNDS.get(key);
            if (direct != null) {
                return direct;
            }
        }
        return soundIndex().get(trimmed.toUpperCase(Locale.ROOT));
    }

    private static @NotNull Map<String, Sound> soundIndex() {
        Map<String, Sound> index = soundIndex;
        if (index == null) {
            final Map<String, Sound> built = new HashMap<>(2048);
            for (final Sound sound : Registry.SOUNDS) {
                final NamespacedKey key = Registry.SOUNDS.getKey(sound);
                if (key == null) {
                    continue;
                }
                // block.note_block.pling -> BLOCK_NOTE_BLOCK_PLING (Bukkit's legacy naming)
                built.putIfAbsent(key.getKey().replace('.', '_').toUpperCase(Locale.ROOT), sound);
            }
            index = Map.copyOf(built);
            soundIndex = index;
        }
        return index;
    }

    /**
     * Convert a Sound to a string for config storage. Uses the modern
     * namespaced-key format via the Registry.
     *
     * @param sound the sound.
     * @return the string representation.
     */
    @SuppressWarnings("deprecation")
    public static @NotNull String soundToString(@NotNull final Sound sound) {
        return Registry.SOUNDS.getKey(sound).toString();
    }

    /**
     * Look up a PotionEffectType by name. Accepts both old format
     * ({@code "SPEED"}) and new format ({@code "minecraft:speed"}).
     *
     * @param name the effect name.
     * @return the PotionEffectType, or null.
     */
    public static @Nullable PotionEffectType potionEffectFromString(@Nullable final String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        final String lower = name.trim().toLowerCase(Locale.ROOT);
        final NamespacedKey key = lower.contains(":") ? NamespacedKey.fromString(lower) : NamespacedKey.minecraft(lower);
        return key == null ? null : Registry.POTION_EFFECT_TYPE.get(key);
    }

    /**
     * Convert a PotionEffectType to a string.
     */
    public static @NotNull String potionEffectToString(@NotNull final PotionEffectType effect) {
        return effect.getKey().toString();
    }

    /**
     * Get the plugin version using the modern {@code getPluginMeta()} API
     * (Paper 1.20+), with a fallback to the deprecated {@code getDescription()}.
     *
     * @param plugin the plugin.
     * @return the version string.
     */
    @SuppressWarnings("deprecation")
    public static @NotNull String getPluginVersion(@NotNull final org.bukkit.plugin.Plugin plugin) {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (final NoSuchMethodError | NoClassDefFoundError e) {
            // Pre-1.20 fallback.
            return plugin.getDescription().getVersion();
        }
    }
}
