/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.hooks.worldedit;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Selection;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Optional WorldEdit integration via {@code SessionManager} (WE 7.x).
 *
 * <p>The old {@code getSelectionManager} API does not exist on modern WorldEdit.
 * This hook uses {@code WorldEdit.getInstance().getSessionManager().get(actor)}
 * then {@code LocalSession.getSelection()}.</p>
 *
 * @author DravoraX
 */
public final class WorldEditHook {

    private static volatile boolean enabled;

    private WorldEditHook() {
    }

    public static void enable(@NotNull final ArenaRegenDXPlugin plugin) {
        enabled = true;
        plugin.getSLF4JLogger().info("WorldEdit hook ready. Incomplete wand boxes fall back to the WE selection.");
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * Read the player's current WorldEdit selection, if available.
     */
    public static @Nullable Selection getSelection(@NotNull final Player player) {
        if (!enabled) return null;
        try {
            final Class<?> weClass = Class.forName("com.sk89q.worldedit.WorldEdit");
            final Object we = weClass.getMethod("getInstance").invoke(null);
            final Object sessions = weClass.getMethod("getSessionManager").invoke(we);
            final Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            final Object actor = bukkitAdapter.getMethod("adapt", Player.class).invoke(null, player);
            final Object session = invokeGet(sessions, actor);
            if (session == null) return null;

            Object region = invokeNoArg(session, "getSelection");
            if (region == null) {
                final Object weWorld = invokeNoArg(session, "getSelectionWorld");
                if (weWorld == null) return null;
                region = invokeOneArg(session, "getSelection", weWorld);
            }
            if (region == null) return null;

            Object weWorld = invokeNoArg(region, "getWorld");
            if (weWorld == null) {
                weWorld = invokeNoArg(session, "getSelectionWorld");
            }
            if (weWorld == null) return null;

            final org.bukkit.World world = (org.bukkit.World) bukkitAdapter
                    .getMethod("adapt", Class.forName("com.sk89q.worldedit.world.World"))
                    .invoke(null, weWorld);
            if (world == null) return null;

            final Object min = region.getClass().getMethod("getMinimumPoint").invoke(region);
            final Object max = region.getClass().getMethod("getMaximumPoint").invoke(region);
            final Selection sel = new Selection();
            sel.pos1 = toBukkit(world, min);
            sel.pos2 = toBukkit(world, max);
            return sel.isComplete() ? sel : null;
        } catch (final Throwable t) {
            return null;
        }
    }

    private static @Nullable Object invokeGet(@NotNull final Object sessions, @NotNull final Object actor) {
        for (final Method m : sessions.getClass().getMethods()) {
            if (!m.getName().equals("get") || m.getParameterCount() != 1) continue;
            try {
                if (m.getParameterTypes()[0].isInstance(actor)) {
                    return m.invoke(sessions, actor);
                }
            } catch (final Throwable ignored) {
            }
        }
        return null;
    }

    private static @Nullable Object invokeNoArg(@NotNull final Object target, @NotNull final String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static @Nullable Object invokeOneArg(@NotNull final Object target, @NotNull final String name,
                                                 @NotNull final Object arg) {
        for (final Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
            try {
                if (m.getParameterTypes()[0].isInstance(arg)) {
                    return m.invoke(target, arg);
                }
            } catch (final Throwable ignored) {
            }
        }
        return null;
    }

    private static @NotNull Location toBukkit(@NotNull final org.bukkit.World world, @NotNull final Object blockVector) {
        try {
            final int x = (int) blockVector.getClass().getMethod("getBlockX").invoke(blockVector);
            final int y = (int) blockVector.getClass().getMethod("getBlockY").invoke(blockVector);
            final int z = (int) blockVector.getClass().getMethod("getBlockZ").invoke(blockVector);
            return new Location(world, x, y, z);
        } catch (final ReflectiveOperationException e) {
            return new Location(world, 0, 0, 0);
        }
    }
}
