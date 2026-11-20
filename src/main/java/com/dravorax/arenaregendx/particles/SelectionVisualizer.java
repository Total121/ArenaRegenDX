/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.particles;

import com.dravorax.arenaregendx.ArenaRegenDXPlugin;
import com.dravorax.arenaregendx.arena.Arena;
import com.dravorax.arenaregendx.arena.ExclusionZone;
import com.dravorax.arenaregendx.arena.Selection;
import com.dravorax.arenaregendx.config.ConfigManager;
import com.dravorax.arenaregendx.config.Settings;
import com.dravorax.arenaregendx.scheduler.SchedulerAdapter;
import com.dravorax.arenaregendx.scheduler.TaskHandle;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player dust overlay of wand selections, arena bounds and exclusions.
 *
 * <p>Only geometry within {@code view-distance-blocks} of the player is
 * iterated: faces are clipped to the view cube and edges to the view sphere,
 * so a 1000-block warzone costs the same as a small duel pit. Particles are
 * sent to the viewing player only, capped per refresh.</p>
 */
public final class SelectionVisualizer {

    private static final Particle.DustOptions POS1 = new Particle.DustOptions(Color.fromRGB(80, 255, 80), 1.35f);
    private static final Particle.DustOptions POS2 = new Particle.DustOptions(Color.fromRGB(80, 200, 255), 1.35f);
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.15f);
    private static final Particle.DustOptions CYAN = new Particle.DustOptions(Color.fromRGB(70, 230, 255), 1.05f);
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(255, 55, 55), 1.15f);
    private static final Particle.DustOptions ORANGE = new Particle.DustOptions(Color.fromRGB(255, 150, 50), 1.05f);

    private static final int MAX_PARTICLES = 4200;
    private static final long PREVIEW_MILLIS = 45_000L;

    private final ArenaRegenDXPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final ConfigManager configManager;

    private final Map<UUID, Selection> active = new ConcurrentHashMap<>();
    private final Map<UUID, Preview> previews = new ConcurrentHashMap<>();

    private volatile TaskHandle refreshTask;
    private volatile float phase;

    public SelectionVisualizer(@NotNull final ArenaRegenDXPlugin plugin,
                               @NotNull final SchedulerAdapter scheduler,
                               @NotNull final ConfigManager configManager) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.configManager = configManager;
    }

    public synchronized void start() {
        if (refreshTask != null || !configManager.particles().enabled) {
            return;
        }
        final long period = Math.max(4L, configManager.particles().refreshIntervalTicks);
        refreshTask = scheduler.runAsyncRepeating(this::refreshAll, period, period);
    }

    /** Re-read settings (period / enabled) without forgetting who is viewing what. */
    public synchronized void restart() {
        stopTask();
        start();
    }

    private synchronized void stopTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    public void showSelection(@NotNull final Player player, @NotNull final Selection selection) {
        if (!configManager.particles().enabled) return;
        active.put(player.getUniqueId(), selection);
        start();
    }

    public void hideSelection(@NotNull final Player player) {
        active.remove(player.getUniqueId());
    }

    public void showArenaPreview(@NotNull final Player player, @NotNull final Arena arena) {
        if (!configManager.particles().enabled) return;
        previews.put(player.getUniqueId(), new Preview(arena, System.currentTimeMillis() + PREVIEW_MILLIS));
        start();
    }

    public void hidePreview(@NotNull final Player player) {
        previews.remove(player.getUniqueId());
    }

    /** Drop all state for a player (quit). */
    public void forget(@NotNull final UUID playerId) {
        active.remove(playerId);
        previews.remove(playerId);
    }

    public void shutdown() {
        stopTask();
        active.clear();
        previews.clear();
    }

    private void refreshAll() {
        if (active.isEmpty() && previews.isEmpty()) return;
        final Settings.ParticleSettings ps = configManager.particles();
        if (!ps.enabled) return;
        phase = (phase + 0.08f) % 1f;
        final float ph = ps.animatedBorders ? phase : 0f;

        for (final Map.Entry<UUID, Selection> entry : active.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                active.remove(entry.getKey());
                continue;
            }
            final Selection sel = entry.getValue();
            scheduler.runAtEntity(player, () -> drawSelection(player, sel, ps, ph));
        }

        final long now = System.currentTimeMillis();
        for (final Map.Entry<UUID, Preview> entry : previews.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || now > entry.getValue().until) {
                previews.remove(entry.getKey());
                continue;
            }
            final Arena arena = entry.getValue().arena;
            scheduler.runAtEntity(player, () -> drawPreview(player, arena, ps, ph));
        }
    }

    // -----------------------------------------------------------------------
    // Scenes
    // -----------------------------------------------------------------------

    private void drawSelection(@NotNull final Player player, @NotNull final Selection sel,
                               @NotNull final Settings.ParticleSettings ps, final float phase) {
        final View v = new View(player, ps);
        if (sel.isComplete()) {
            final Location min = sel.min();
            final Location max = sel.max();
            if (min == null || max == null || min.getWorld() != player.getWorld()) return;
            drawCuboid(v, min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                    max.getBlockX() + 1, max.getBlockY() + 1, max.getBlockZ() + 1, GOLD, CYAN, ps, phase);
        }
        drawCornerPillar(v, sel.pos1, POS1);
        drawCornerPillar(v, sel.pos2, POS2);
    }

    private void drawPreview(@NotNull final Player player, @NotNull final Arena arena,
                             @NotNull final Settings.ParticleSettings ps, final float phase) {
        if (!player.getWorld().getName().equals(arena.worldName())) return;
        final View v = new View(player, ps);
        drawCuboid(v, arena.originX(), arena.originY(), arena.originZ(),
                arena.originX() + arena.sizeX(), arena.originY() + arena.sizeY(), arena.originZ() + arena.sizeZ(),
                GOLD, ORANGE, ps, phase);
        for (final ExclusionZone zone : arena.exclusions()) {
            switch (zone.shape()) {
                case SPHERE -> drawSphere(v, zone.cx(), zone.cy(), zone.cz(), zone.radius(), RED, phase);
                case CYLINDER -> drawCylinder(v, zone.cx(), zone.cz(), zone.minY(), zone.maxY() + 1,
                        zone.radius(), RED, phase);
                default -> drawCuboid(v, zone.minX(), zone.minY(), zone.minZ(),
                        zone.maxX() + 1, zone.maxY() + 1, zone.maxZ() + 1, RED, RED, ps, phase);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Primitives
    // -----------------------------------------------------------------------

    private void drawCuboid(@NotNull final View v,
                            final int x1, final int y1, final int z1, final int x2, final int y2, final int z2,
                            @NotNull final Particle.DustOptions edge, @NotNull final Particle.DustOptions fill,
                            @NotNull final Settings.ParticleSettings ps, final float phase) {
        final double maxDim = Math.max(x2 - x1, Math.max(y2 - y1, z2 - z1));
        final double density = Math.max(6, ps.borderDensity);
        final double step = Math.max(0.45, Math.min(1.15, maxDim / (density * 10.0)));

        // 12 edges
        drawEdge(v, edge, x1, y1, z1, x2, y1, z1, step, phase);
        drawEdge(v, edge, x1, y1, z2, x2, y1, z2, step, phase);
        drawEdge(v, edge, x1, y2, z1, x2, y2, z1, step, phase);
        drawEdge(v, edge, x1, y2, z2, x2, y2, z2, step, phase);
        drawEdge(v, edge, x1, y1, z1, x1, y2, z1, step, phase);
        drawEdge(v, edge, x2, y1, z1, x2, y2, z1, step, phase);
        drawEdge(v, edge, x1, y1, z2, x1, y2, z2, step, phase);
        drawEdge(v, edge, x2, y1, z2, x2, y2, z2, step, phase);
        drawEdge(v, edge, x1, y1, z1, x1, y1, z2, step, phase);
        drawEdge(v, edge, x2, y1, z1, x2, y1, z2, step, phase);
        drawEdge(v, edge, x1, y2, z1, x1, y2, z2, step, phase);
        drawEdge(v, edge, x2, y2, z1, x2, y2, z2, step, phase);

        // 8 corners
        for (final int cx : new int[]{x1, x2}) {
            for (final int cy : new int[]{y1, y2}) {
                for (final int cz : new int[]{z1, z2}) {
                    v.dust(edge, cx, cy, cz, 4, 0.04);
                }
            }
        }

        // 6 faces, each clipped to the view cube around the eye
        final double faceStep = Math.max(0.65, Math.min(1.25, step * 1.15));
        drawFace(v, fill, Axis.Z, z1, x1, x2, y1, y2, faceStep);
        drawFace(v, fill, Axis.Z, z2, x1, x2, y1, y2, faceStep);
        drawFace(v, fill, Axis.X, x1, z1, z2, y1, y2, faceStep);
        drawFace(v, fill, Axis.X, x2, z1, z2, y1, y2, faceStep);
        drawFace(v, fill, Axis.Y, y1, x1, x2, z1, z2, faceStep);
        drawFace(v, fill, Axis.Y, y2, x1, x2, z1, z2, faceStep);
    }

    private enum Axis { X, Y, Z }

    /**
     * Draw a grid on the plane {@code normal = plane}, spanning {@code [a1,a2] x [b1,b2]}
     * in the two other axes, limited to the part within view distance.
     */
    private void drawFace(@NotNull final View v, @NotNull final Particle.DustOptions dust, @NotNull final Axis normal,
                          final double plane, final double a1, final double a2, final double b1, final double b2,
                          final double step) {
        final double eyeN = switch (normal) { case X -> v.ex; case Y -> v.ey; case Z -> v.ez; };
        final double dn = Math.abs(eyeN - plane);
        if (dn > v.view) {
            return;
        }
        final double reach = Math.sqrt(v.viewSq - dn * dn);
        // Map grid axes (a, b) to world axes.
        final double eyeA = normal == Axis.X ? v.ez : v.ex;
        final double eyeB = normal == Axis.Y ? v.ez : v.ey;
        final double aStart = alignedStart(a1, Math.max(a1, eyeA - reach), step);
        final double aEnd = Math.min(a2, eyeA + reach);
        final double bStart = alignedStart(b1, Math.max(b1, eyeB - reach), step);
        final double bEnd = Math.min(b2, eyeB + reach);
        for (double a = aStart; a <= aEnd + 0.001; a += step) {
            for (double b = bStart; b <= bEnd + 0.001; b += step) {
                final double x;
                final double y;
                final double z;
                switch (normal) {
                    case X -> { x = plane; y = b; z = a; }
                    case Y -> { x = a; y = plane; z = b; }
                    default -> { x = a; y = b; z = plane; }
                }
                if (v.near(x, y, z) && !v.dust(dust, x, y, z, 1, 0.0)) {
                    return;
                }
            }
        }
    }

    /** First grid coordinate >= {@code from} on the lattice {@code origin + k*step} (stable, no shimmer). */
    private static double alignedStart(final double origin, final double from, final double step) {
        final double k = Math.ceil((from - origin) / step - 1e-9);
        return origin + Math.max(0.0, k) * step;
    }

    private void drawEdge(@NotNull final View v, @NotNull final Particle.DustOptions dust,
                          final double x1, final double y1, final double z1,
                          final double x2, final double y2, final double z2,
                          final double step, final float phase) {
        final double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) return;
        final double ux = dx / len, uy = dy / len, uz = dz / len;
        // Clip the segment to the view sphere.
        final double px = v.ex - x1, py = v.ey - y1, pz = v.ez - z1;
        final double s = px * ux + py * uy + pz * uz;
        final double perpSq = px * px + py * py + pz * pz - s * s;
        if (perpSq > v.viewSq) return;
        final double half = Math.sqrt(v.viewSq - perpSq);
        final double tMin = Math.max(0.0, s - half);
        final double tMax = Math.min(len, s + half);
        final double offset = (phase * step) % step;
        for (double t = alignedStart(offset, tMin, step); t <= tMax; t += step) {
            if (!v.dust(dust, x1 + ux * t, y1 + uy * t, z1 + uz * t, 1, 0.0)) {
                return;
            }
        }
    }

    private void drawSphere(@NotNull final View v, final double cx, final double cy, final double cz,
                            final double radius, @NotNull final Particle.DustOptions dust, final float phase) {
        if (!v.within(cx, cy, cz, radius)) return;
        final int rings = Math.max(8, Math.min(24, (int) (radius * 2.0)));
        final double step = Math.max(0.45, radius / 12.0);
        for (int i = 0; i < rings; i++) {
            final double t = (i + phase) / rings * Math.PI;
            final double ringR = Math.sin(t) * radius;
            final double y = cy + Math.cos(t) * radius;
            final int dots = Math.max(10, (int) (ringR * Math.PI * 2.0 / step));
            for (int d = 0; d < dots; d++) {
                final double a = (d / (double) dots) * Math.PI * 2.0 + phase;
                final double x = cx + Math.cos(a) * ringR;
                final double z = cz + Math.sin(a) * ringR;
                if (v.near(x, y, z) && !v.dust(dust, x, y, z, 1, 0.0)) return;
            }
        }
    }

    private void drawCylinder(@NotNull final View v, final double cx, final double cz,
                              final int minY, final int maxY, final double radius,
                              @NotNull final Particle.DustOptions dust, final float phase) {
        final double step = Math.max(0.5, radius / 10.0);
        final int dots = Math.max(14, (int) (radius * Math.PI * 2.0 / step));
        final int yStep = Math.max(1, (int) Math.ceil(step));
        final int y0 = Math.max(minY, (int) Math.floor(v.ey - v.view));
        final int y1 = Math.min(maxY, (int) Math.ceil(v.ey + v.view));
        for (int y = y0; y <= y1; y += yStep) {
            for (int d = 0; d < dots; d++) {
                final double a = (d / (double) dots) * Math.PI * 2.0 + phase;
                final double x = cx + Math.cos(a) * radius;
                final double z = cz + Math.sin(a) * radius;
                if (v.near(x, y, z) && !v.dust(dust, x, y, z, 1, 0.0)) return;
            }
        }
    }

    private void drawCornerPillar(@NotNull final View v, @Nullable final Location loc,
                                  @NotNull final Particle.DustOptions dust) {
        if (loc == null || loc.getWorld() != v.player.getWorld()) return;
        for (int i = 0; i <= 4; i++) {
            final double x = loc.getBlockX() + 0.5;
            final double y = loc.getBlockY() + i;
            final double z = loc.getBlockZ() + 0.5;
            if (v.near(x, y, z)) {
                v.dust(dust, x, y, z, 3, 0.05);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Per-draw context
    // -----------------------------------------------------------------------

    /** Viewer eye position and particle budget for one refresh (thread-confined). */
    private static final class View {
        final Player player;
        final double ex, ey, ez;
        final double view, viewSq;
        int remaining = MAX_PARTICLES;

        View(@NotNull final Player player, @NotNull final Settings.ParticleSettings ps) {
            this.player = player;
            final Location eye = player.getEyeLocation();
            this.ex = eye.getX();
            this.ey = eye.getY();
            this.ez = eye.getZ();
            this.view = Math.max(24.0, ps.viewDistanceBlocks);
            this.viewSq = view * view;
        }

        boolean near(final double x, final double y, final double z) {
            final double dx = ex - x, dy = ey - y, dz = ez - z;
            return dx * dx + dy * dy + dz * dz <= viewSq;
        }

        /** @return true if a sphere of {@code radius} around the point can be within view. */
        boolean within(final double x, final double y, final double z, final double radius) {
            final double reach = view + radius;
            final double dx = ex - x, dy = ey - y, dz = ez - z;
            return dx * dx + dy * dy + dz * dz <= reach * reach;
        }

        /** @return false once the per-refresh particle budget is exhausted. */
        boolean dust(@NotNull final Particle.DustOptions dust, final double x, final double y, final double z,
                     final int count, final double spread) {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            player.spawnParticle(Particle.DUST, x, y, z, count, spread, spread, spread, 0.0, dust);
            return true;
        }
    }

    private record Preview(@NotNull Arena arena, long until) {
    }
}
