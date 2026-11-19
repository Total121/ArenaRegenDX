/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 */

package com.dravorax.arenaregendx.regen;

import org.jetbrains.annotations.NotNull;

/**
 * Regen throughput presets and clamping helpers.
 *
 * <p>{@code -1} means inherit the global {@code performance.yml} value.</p>
 */
public final class RegenSpeed {

    private RegenSpeed() {
    }

    public static final int INHERIT = -1;
    public static final int MIN = 10;
    public static final int MAX = 500;
    public static final int DEFAULT_GLOBAL = 100;

    public static final int SLOW = 40;
    public static final int NORMAL = 100;
    public static final int FAST = 200;
    public static final int MAX_PRESET = 350;

    public static int clampStored(final int percent) {
        if (percent < 0) {
            return INHERIT;
        }
        return Math.max(MIN, Math.min(MAX, percent));
    }

    public static int clampEffective(final int percent) {
        return Math.max(MIN, Math.min(MAX, percent));
    }

    public static int resolve(final int arenaPercent, final int globalPercent) {
        final int chosen = arenaPercent < 0 ? globalPercent : arenaPercent;
        return clampEffective(chosen);
    }

    public static int parsePresetOrPercent(@NotNull final String raw) {
        final String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "inherit", "default", "global" -> INHERIT;
            case "slow" -> SLOW;
            case "normal", "medium" -> NORMAL;
            case "fast" -> FAST;
            case "max", "ultra", "insanely-fast", "instant" -> MAX_PRESET;
            default -> {
                try {
                    yield clampStored(Integer.parseInt(s));
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid speed '" + raw + "'. Use slow/normal/fast/max/inherit or 10-500.");
                }
            }
        };
    }

    public static @NotNull String label(final int storedPercent) {
        if (storedPercent < 0) {
            return "inherit";
        }
        return switch (storedPercent) {
            case SLOW -> "slow (" + SLOW + "%)";
            case NORMAL -> "normal (" + NORMAL + "%)";
            case FAST -> "fast (" + FAST + "%)";
            case MAX_PRESET -> "max (" + MAX_PRESET + "%)";
            default -> storedPercent + "%";
        };
    }

    /** Cycle arena presets: inherit → slow → normal → fast → max → inherit. */
    public static int nextArenaPreset(final int current) {
        if (current < 0) return SLOW;
        if (current <= SLOW) return NORMAL;
        if (current <= NORMAL) return FAST;
        if (current <= FAST) return MAX_PRESET;
        return INHERIT;
    }

    /** Cycle global presets: slow → normal → fast → max → slow. */
    public static int nextGlobalPreset(final int current) {
        if (current < NORMAL) return NORMAL;
        if (current < FAST) return FAST;
        if (current < MAX_PRESET) return MAX_PRESET;
        return SLOW;
    }
}
