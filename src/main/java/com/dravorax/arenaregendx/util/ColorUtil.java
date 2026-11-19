/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Centralised text utilities built on the Adventure API.
 *
 * <p>All user-facing strings in ArenaRegenDX are authored as MiniMessage
 * templates (e.g. {@code <gold>Arena <yellow><name> <reset>created!}). This
 * class provides safe parsing helpers and a legacy fallback for components that
 * must be embedded inside legacy-aware APIs (e.g. inventory titles pre-1.20.5).</p>
 *
 * <p>A single shared {@link MiniMessage} instance is used for caching/perf.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class ColorUtil {

    /** Shared MiniMessage instance with strict parsing (bad tags are stripped, not thrown). */
    private static final MiniMessage MINI = MiniMessage.builder()
            .strict(false)
            .build();

    /** Legacy serializer for the rare legacy-only APIs. Uses the ampersand section char. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private static final char[] SMALL_CAPS = new char[128];

    static {
        final String from = "abcdefghijklmnopqrstuvwxyz";
        final String to = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
        for (int i = 0; i < from.length(); i++) {
            SMALL_CAPS[from.charAt(i)] = to.charAt(i);
            SMALL_CAPS[Character.toUpperCase(from.charAt(i))] = to.charAt(i);
        }
    }

    private ColorUtil() {
        // utility class
    }

    /**
     * Parse a MiniMessage string into a Component.
     *
     * @param input the MiniMessage template (may be {@code null} → returns empty).
     * @return the parsed component, never {@code null}.
     */
    public static @NotNull Component mini(@Nullable final String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(input);
    }

    /**
     * Parse a MiniMessage template with tag resolvers (for variable substitution).
     *
     * @param input     the template.
     * @param resolvers tag resolvers (e.g. {@code Placeholder.component("name", ...)}).
     * @return the parsed component.
     */
    public static @NotNull Component mini(@Nullable final String input, final TagResolver @NotNull ... resolvers) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(input, resolvers);
    }

    /**
     * Convert a Component to a legacy-section string. Use sparingly — prefer Components.
     *
     * @param component the component.
     * @return the legacy string.
     */
    public static @NotNull String legacy(@NotNull final Component component) {
        return LEGACY.serialize(component);
    }

    /**
     * Strip all MiniMessage / legacy formatting from a string, returning plain text.
     * Useful for log-safe output and filename validation.
     *
     * @param input the input.
     * @return plain text.
     */
    public static @NotNull String plain(@Nullable final String input) {
        if (input == null) {
            return "";
        }
        // Strip legacy ampersand codes & MiniMessage tags.
        String s = input.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        s = s.replaceAll("<[^>]*>", "");
        return s;
    }

    /**
     * Convert Latin letters to Unicode small-caps, leaving MiniMessage tags,
     * digits and punctuation untouched.
     */
    public static @NotNull String smallCaps(@Nullable final String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(input.length());
        boolean inTag = false;
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            if (c == '<') {
                inTag = true;
                out.append(c);
                continue;
            }
            if (c == '>') {
                inTag = false;
                out.append(c);
                continue;
            }
            if (!inTag && c < SMALL_CAPS.length && SMALL_CAPS[c] != 0) {
                out.append(SMALL_CAPS[c]);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static @NotNull String named(@NotNull final String color, @NotNull final String text) {
        return color + smallCaps(text);
    }

    public static @NotNull String loreLine(@NotNull final String text) {
        return "<gray>" + smallCaps(text);
    }

    /**
     * Sanitize a name for use as a filesystem-safe identifier.
     *
     * @param raw the raw name.
     * @return a sanitized name (lowercase alnum + dash + underscore).
     */
    public static @NotNull String sanitizeName(@Nullable final String raw) {
        if (raw == null) {
            return "unnamed";
        }
        final String plain = plain(raw).trim().toLowerCase();
        final StringBuilder sb = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            final char c = plain.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        final String out = sb.toString();
        return out.isEmpty() ? "unnamed" : out;
    }
}
