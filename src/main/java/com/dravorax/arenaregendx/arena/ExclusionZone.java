/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.arena;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A volume inside an arena that is never restored.
 *
 * <p>Typical use: a protected spawn island in the middle of a warzone. Select
 * the warzone as the arena, then add this spawn as an exclusion. Regeneration
 * rewrites the warzone and leaves the spawn (and anything inside it) untouched.</p>
 *
 * <p>Shapes:</p>
 * <ul>
 *   <li>{@link Shape#CUBOID} — inclusive AABB from a wand selection.</li>
 *   <li>{@link Shape#SPHERE} — centre + radius in blocks.</li>
 *   <li>{@link Shape#CYLINDER} — vertical column, XZ radius, Y from min to max.</li>
 * </ul>
 *
 * @author DravoraX
 */
public final class ExclusionZone {

    public enum Shape {
        CUBOID, SPHERE, CYLINDER
    }

    private final String name;
    private final Shape shape;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final double cx, cy, cz;
    private final double radius;
    private final double radiusSq;

    private ExclusionZone(@NotNull final String name, @NotNull final Shape shape,
                          final int minX, final int minY, final int minZ,
                          final int maxX, final int maxY, final int maxZ,
                          final double cx, final double cy, final double cz,
                          final double radius) {
        this.name = name;
        this.shape = shape;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.radius = radius;
        this.radiusSq = radius * radius;
    }

    public static @NotNull ExclusionZone cuboid(@NotNull final String name,
                                                @NotNull final Location a,
                                                @NotNull final Location b) {
        final int minX = Math.min(a.getBlockX(), b.getBlockX());
        final int minY = Math.min(a.getBlockY(), b.getBlockY());
        final int minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        final int maxX = Math.max(a.getBlockX(), b.getBlockX());
        final int maxY = Math.max(a.getBlockY(), b.getBlockY());
        final int maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        return new ExclusionZone(name, Shape.CUBOID,
                minX, minY, minZ, maxX, maxY, maxZ,
                (minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5,
                0.0);
    }

    public static @NotNull ExclusionZone sphere(@NotNull final String name,
                                                @NotNull final Location center,
                                                final double radius) {
        final double r = Math.max(0.5, radius);
        final int rr = (int) Math.ceil(r);
        final int cx = center.getBlockX();
        final int cy = center.getBlockY();
        final int cz = center.getBlockZ();
        return new ExclusionZone(name, Shape.SPHERE,
                cx - rr, cy - rr, cz - rr, cx + rr, cy + rr, cz + rr,
                center.getX(), center.getY(), center.getZ(), r);
    }

    public static @NotNull ExclusionZone cylinder(@NotNull final String name,
                                                  @NotNull final Location center,
                                                  final double radius,
                                                  final int minY, final int maxY) {
        final double r = Math.max(0.5, radius);
        final int rr = (int) Math.ceil(r);
        final int cx = center.getBlockX();
        final int cz = center.getBlockZ();
        final int y0 = Math.min(minY, maxY);
        final int y1 = Math.max(minY, maxY);
        return new ExclusionZone(name, Shape.CYLINDER,
                cx - rr, y0, cz - rr, cx + rr, y1, cz + rr,
                center.getX(), (y0 + y1) * 0.5, center.getZ(), r);
    }

    public @NotNull String name() { return name; }
    public @NotNull Shape shape() { return shape; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
    public double cx() { return cx; }
    public double cy() { return cy; }
    public double cz() { return cz; }
    public double radius() { return radius; }

    /**
     * Fast volume test. AABB is always checked first so spheres/cylinders
     * reject most warzone blocks in a handful of integer compares.
     */
    public boolean contains(final int x, final int y, final int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return false;
        }
        return switch (shape) {
            case CUBOID -> true;
            case SPHERE -> {
                final double dx = (x + 0.5) - cx;
                final double dy = (y + 0.5) - cy;
                final double dz = (z + 0.5) - cz;
                yield (dx * dx + dy * dy + dz * dz) <= radiusSq;
            }
            case CYLINDER -> {
                final double dx = (x + 0.5) - cx;
                final double dz = (z + 0.5) - cz;
                yield (dx * dx + dz * dz) <= radiusSq;
            }
        };
    }

    public @NotNull Location centerLocation(@NotNull final World world) {
        return new Location(world, cx, cy, cz);
    }

    public void writeTo(@NotNull final ConfigurationSection section) {
        section.set("shape", shape.name());
        section.set("min.x", minX);
        section.set("min.y", minY);
        section.set("min.z", minZ);
        section.set("max.x", maxX);
        section.set("max.y", maxY);
        section.set("max.z", maxZ);
        section.set("center.x", cx);
        section.set("center.y", cy);
        section.set("center.z", cz);
        section.set("radius", radius);
    }

    public static @Nullable ExclusionZone readFrom(@NotNull final String name,
                                                   @NotNull final ConfigurationSection section) {
        try {
            final Shape shape = Shape.valueOf(section.getString("shape", "CUBOID").toUpperCase());
            return new ExclusionZone(
                    name, shape,
                    section.getInt("min.x"), section.getInt("min.y"), section.getInt("min.z"),
                    section.getInt("max.x"), section.getInt("max.y"), section.getInt("max.z"),
                    section.getDouble("center.x"), section.getDouble("center.y"), section.getDouble("center.z"),
                    section.getDouble("radius", 0.0)
            );
        } catch (final Exception e) {
            return null;
        }
    }

    @Override
    public @NotNull String toString() {
        return switch (shape) {
            case CUBOID -> name + " cuboid [" + minX + "," + minY + "," + minZ
                    + " -> " + maxX + "," + maxY + "," + maxZ + "]";
            case SPHERE -> name + " sphere r=" + (int) radius + " @ "
                    + (int) cx + "," + (int) cy + "," + (int) cz;
            case CYLINDER -> name + " cylinder r=" + (int) radius
                    + " y=" + minY + ".." + maxY;
        };
    }
}
