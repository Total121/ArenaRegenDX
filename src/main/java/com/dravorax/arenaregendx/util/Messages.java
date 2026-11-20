/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.util;

import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * User-facing text from {@code messages.yml}.
 *
 * <p>Templates are MiniMessage. Placeholders are passed as name/value pairs and
 * inserted as <b>literal</b> text ({@link Placeholder#unparsed}), so arena
 * names or command arguments can never inject click / hover / colour tags.
 * Values that are already {@link Component}s are inserted as-is.
 * {@code <prefix>} expands to the configured prefix. Missing keys fall back to
 * the bundled defaults, then to the key itself.</p>
 *
 * <p>Player messages are delivered on the player's own thread (Folia-safe).</p>
 *
 * @author DravoraX
 */
public final class Messages {

    private final ConfigManager config;
    private final SchedulerAdapter scheduler;

    public Messages(@NotNull final ConfigManager config, @NotNull final SchedulerAdapter scheduler) {
        this.config = config;
        this.scheduler = scheduler;
    }

    /** Render {@code key} with {@code name, value, name, value, ...} placeholders. */
    public @NotNull Component render(@NotNull final String key, @NotNull final Object... placeholders) {
        return format(config.message(key), placeholders);
    }

    /** Render an inline MiniMessage template with the same placeholder rules. */
    public @NotNull Component format(@NotNull final String template, @NotNull final Object... placeholders) {
        final List<TagResolver> resolvers = new ArrayList<>(placeholders.length / 2 + 1);
        resolvers.add(Placeholder.parsed("prefix", config.message("prefix")));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            final String name = String.valueOf(placeholders[i]);
            final Object value = placeholders[i + 1];
            // Components were rendered by us (trusted); everything else is literal text.
            resolvers.add(value instanceof ComponentLike component
                    ? Placeholder.component(name, component)
                    : Placeholder.unparsed(name, String.valueOf(value)));
        }
        return ColorUtil.mini(template, resolvers.toArray(new TagResolver[0]));
    }

    /** @return the raw template for {@code key}. */
    public @NotNull String raw(@NotNull final String key) {
        return config.message(key);
    }

    public void send(@NotNull final CommandSender to, @NotNull final String key, @NotNull final Object... placeholders) {
        final String template = config.message(key);
        if (template.isEmpty()) {
            return; // an empty message disables it
        }
        deliver(to, format(template, placeholders));
    }

    /** Send every line of a list message (e.g. help). */
    public void sendList(@NotNull final CommandSender to, @NotNull final String key, @NotNull final Object... placeholders) {
        for (final String line : config.messageList(key)) {
            deliver(to, format(line, placeholders));
        }
    }

    /** Send pre-rendered text to a sender on the right thread. */
    public void deliver(@NotNull final CommandSender to, @NotNull final Component component) {
        if (to instanceof Player player && !Bukkit.isOwnedByCurrentRegion(player)) {
            scheduler.runAtEntity(player, () -> player.sendMessage(component));
        } else {
            to.sendMessage(component);
        }
    }

    public void actionBar(@NotNull final Player player, @NotNull final String key, @NotNull final Object... placeholders) {
        final String template = config.message(key);
        if (template.isEmpty()) {
            return;
        }
        final Component component = format(template, placeholders);
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            player.sendActionBar(component);
        } else {
            scheduler.runAtEntity(player, () -> player.sendActionBar(component));
        }
    }
}
