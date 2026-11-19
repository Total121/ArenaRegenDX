/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.util;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Look up a Sound by name. Accepts both old enum format
     * ({@code "BLOCK_NOTE_BLOCK_PLING"}) and new namespaced format
     * ({@code "minecraft:block.note_block.pling"}).
     *
     * @param name the sound name.
     * @return the Sound, or null if not found.
     */
    public static @Nullable Sound soundFromString(@Nullable final String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        // Try namespaced-key format first (modern).
        final String lower = name.toLowerCase();
        if (lower.contains(":")) {
            final NamespacedKey key = NamespacedKey.fromString(name.toLowerCase());
            if (key != null) {
                return Registry.SOUNDS.get(key);
            }
        }
        // Try converting old enum name to namespaced key.
        // "BLOCK_NOTE_BLOCK_PLING" → "block.note_block.pling"
        final String converted = name.toLowerCase().replace('_', '.');
        final NamespacedKey key = NamespacedKey.minecraft(converted);
        return Registry.SOUNDS.get(key);
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
        final String lower = name.toLowerCase();
        if (lower.contains(":")) {
            final NamespacedKey key = NamespacedKey.fromString(lower);
            if (key != null) {
                return Registry.POTION_EFFECT_TYPE.get(key);
            }
        }
        final NamespacedKey key = NamespacedKey.minecraft(lower);
        return Registry.POTION_EFFECT_TYPE.get(key);
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
