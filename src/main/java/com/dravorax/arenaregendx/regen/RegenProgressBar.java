/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.regen;

import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-job boss bar shown to nearby players and the operator.
 *
 * <p>All Adventure show/hide calls hop onto the player entity scheduler so Folia
 * never touches player UI state from a foreign region thread.</p>
 *
 * @author DravoraX
 */
public final class RegenProgressBar {

    private final BossBar bar;
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private final @Nullable SchedulerAdapter scheduler;

    public RegenProgressBar(@NotNull final String arenaName) {
        this(arenaName, null);
    }

    public RegenProgressBar(@NotNull final String arenaName, @Nullable final SchedulerAdapter scheduler) {
        this.scheduler = scheduler;
        this.bar = BossBar.bossBar(
                Component.text("Restoring " + arenaName),
                0f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS);
    }

    public void show(@NotNull final Collection<? extends Player> players) {
        for (final Player p : players) {
            if (p == null || !p.isOnline()) continue;
            if (!viewers.add(p.getUniqueId())) continue;
            runAtPlayer(p, () -> {
                try {
                    if (p.isOnline()) {
                        p.showBossBar(bar);
                    }
                } catch (final Throwable ignored) {
                    viewers.remove(p.getUniqueId());
                }
            });
        }
    }

    public void progress(final float fraction) {
        bar.progress(Math.max(0f, Math.min(1f, fraction)));
    }

    public void hide(@NotNull final Collection<? extends Player> players) {
        for (final Player p : players) {
            if (p == null) continue;
            viewers.remove(p.getUniqueId());
            runAtPlayer(p, () -> {
                try {
                    p.hideBossBar(bar);
                } catch (final Throwable ignored) {
                }
            });
        }
        hideRemaining();
    }

    public void hideRemaining() {
        for (final UUID id : Set.copyOf(viewers)) {
            final Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) {
                runAtPlayer(p, () -> {
                    try {
                        p.hideBossBar(bar);
                    } catch (final Throwable ignored) {
                    }
                });
            }
        }
        viewers.clear();
    }

    private void runAtPlayer(@NotNull final Player player, @NotNull final Runnable action) {
        final org.bukkit.plugin.Plugin owner = org.bukkit.Bukkit.getPluginManager().getPlugin("ArenaRegenDX");
        if (scheduler != null && owner != null && owner.isEnabled()) {
            scheduler.runAtEntity(player, action);
        } else {
            // Disabling: schedulers reject new tasks, but bars must still be removed client-side.
            action.run();
        }
    }
}
