/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.hooks.worldguard;

import com.dravorax.arenaregendx.arena.Arena;
import org.jetbrains.annotations.NotNull;

/**
 * Optional WorldGuard integration.
 *
 * <p>When WorldGuard is installed, arenas can be linked to WG protected regions
 * so accidental griefing between matches is prevented. The hook is reflective
 * to keep the jar portable.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class WorldGuardHook {

    private static volatile boolean enabled;

    private WorldGuardHook() {
        // static
    }

    /**
     * Enable the hook.
     *
     * @param plugin the plugin.
     * @param arenaManager the arena manager.
     */
    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    /**
     * Create (or replace) a WorldGuard protected cuboid region matching an arena.
     *
     * @param arena the arena to protect.
     * @return true on success, false if WG is absent or the call failed.
     */
    public static boolean protectArena(@NotNull final Arena arena) {
        if (!enabled) return false;
        try {
            // WorldGuard 7+ API: WorldGuard.getInstance().getPlatform().getRegionContainer()
            //   .get(BukkitAdapter.adapt(world)).addRegion(ProtectedCuboidRegion)
            // Reflective to avoid compile-time dependency.
            final org.bukkit.World world = org.bukkit.Bukkit.getWorld(arena.worldName());
            if (world == null) return false;

            final Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            final Object wg = wgClass.getMethod("getInstance").invoke(null);
            final Object platform = wgClass.getMethod("getPlatform").invoke(wg);
            final Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            final Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            final Object weWorld = bukkitAdapter.getMethod("adapt", org.bukkit.World.class).invoke(null, world);
            final Object regionManager = container.getClass().getMethod("get", weWorld.getClass().getInterfaces()[0])
                    .invoke(container, weWorld);
            if (regionManager == null) return false;

            // Build the cuboid region.
            final Class<?> blockVec3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            final Object min = blockVec3.getMethod("at", double.class, double.class, double.class)
                    .invoke(null, (double) arena.originX(), (double) arena.originY(), (double) arena.originZ());
            final Object max = blockVec3.getMethod("at", double.class, double.class, double.class)
                    .invoke(null, (double) (arena.originX() + arena.sizeX() - 1),
                            (double) (arena.originY() + arena.sizeY() - 1),
                            (double) (arena.originZ() + arena.sizeZ() - 1));
            final Class<?> protectedCuboid = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion");
            final Object region = protectedCuboid.getConstructor(String.class, min.getClass(), max.getClass())
                    .newInstance("ardx_" + arena.name(), min, max);

            regionManager.getClass().getMethod("addRegion",
                    Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion"))
                    .invoke(regionManager, region);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }
}
