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
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player dust overlay of wand selections, arena bounds, and exclusions.
 * Nearby walls are drawn as a full face grid; distant faces are culled so
 * the local volume still looks solid.
 */
public final class SelectionVisualizer {

    private static final Particle.DustOptions POS1 = new Particle.DustOptions(Color.fromRGB(80, 255, 80), 1.35f);
    private static final Particle.DustOptions POS2 = new Particle.DustOptions(Color.fromRGB(80, 200, 255), 1.35f);
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.15f);
    private static final Particle.DustOptions CYAN = new Particle.DustOptions(Color.fromRGB(70, 230, 255), 1.05f);
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(255, 55, 55), 1.15f);
    private static final Particle.DustOptions ORANGE = new Particle.DustOptions(Color.fromRGB(255, 150, 50), 1.05f);

    private static final int MAX_PARTICLES = 4200;

    private final ArenaRegenDXPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final ConfigManager configManager;

    private final Map<UUID, Selection> active = new ConcurrentHashMap<>();
    private final Map<UUID, Preview> previews = new ConcurrentHashMap<>();

    private volatile TaskHandle refreshTask;
    private volatile float phase = 0f;

    public SelectionVisualizer(@NotNull final ArenaRegenDXPlugin plugin,
                               @NotNull final SchedulerAdapter scheduler,
                               @NotNull final ConfigManager configManager) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.configManager = configManager;
    }

    public void start() {
        if (refreshTask != null) return;
        final Settings.ParticleSettings ps = configManager.particles();
        if (!ps.enabled) {
            plugin.getSLF4JLogger().info("Selection particles disabled in config.");
            return;
        }
        final long period = Math.max(4L, ps.refreshIntervalTicks);
        refreshTask = scheduler.runAsyncRepeating(this::refreshAll, period, period);
    }

    public void restart() {
        shutdown();
        start();
    }

    public void showSelection(@NotNull final Player player, @NotNull final Selection selection) {
        if (!configManager.particles().enabled) return;
        active.put(player.getUniqueId(), selection);
        if (refreshTask == null) start();
    }

    public void hideSelection(@NotNull final Player player) {
        active.remove(player.getUniqueId());
    }

    public void showArenaPreview(@NotNull final Player player, @NotNull final Arena arena) {
        if (!configManager.particles().enabled) return;
        previews.put(player.getUniqueId(), new Preview(arena, System.currentTimeMillis() + 45_000L));
        if (refreshTask == null) start();
    }

    public void hidePreview(@NotNull final Player player) {
        previews.remove(player.getUniqueId());
    }

    private void refreshAll() {
        if (active.isEmpty() && previews.isEmpty()) return;
        final Settings.ParticleSettings ps = configManager.particles();
        if (!ps.enabled) return;
        phase = (phase + 0.08f) % 1f;
        final float ph = ps.animatedBorders ? phase : 0f;

        for (final Map.Entry<UUID, Selection> entry : active.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                active.remove(entry.getKey());
                continue;
            }
            final Selection sel = entry.getValue();
            if (sel == null) continue;
            scheduler.runAtEntity(player, () -> drawSelection(player, sel, ps, ph));
        }

        final long now = System.currentTimeMillis();
        for (final Map.Entry<UUID, Preview> entry : previews.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || now > entry.getValue().until) {
                previews.remove(entry.getKey());
                continue;
            }
            scheduler.runAtEntity(player, () -> drawPreview(player, entry.getValue().arena, ps, ph));
        }
    }

    private void drawSelection(@NotNull final Player player, @NotNull final Selection sel,
                               @NotNull final Settings.ParticleSettings ps, final float phase) {
        final ParticleCounter counter = new ParticleCounter();
        if (sel.isComplete()) {
            final Location min = sel.min();
            final Location max = sel.max();
            if (min == null || max == null) return;
            drawCuboid(player, min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                    max.getBlockX() + 1, max.getBlockY() + 1, max.getBlockZ() + 1,
                    GOLD, CYAN, true, ps, phase, counter);
            drawCornerPillar(player, sel.pos1, POS1, counter);
            drawCornerPillar(player, sel.pos2, POS2, counter);
        } else {
            drawCornerPillar(player, sel.pos1, POS1, counter);
            drawCornerPillar(player, sel.pos2, POS2, counter);
        }
    }

    private void drawPreview(@NotNull final Player player, @NotNull final Arena arena,
                             @NotNull final Settings.ParticleSettings ps, final float phase) {
        final World world = player.getWorld();
        if (!world.getName().equals(arena.worldName())) return;
        final ParticleCounter counter = new ParticleCounter();
        drawCuboid(player,
                arena.originX(), arena.originY(), arena.originZ(),
                arena.originX() + arena.sizeX(), arena.originY() + arena.sizeY(), arena.originZ() + arena.sizeZ(),
                GOLD, ORANGE, true, ps, phase, counter);
        for (final ExclusionZone zone : arena.exclusions()) {
            drawExclusion(player, zone, ps, phase, counter);
        }
    }

    private void drawExclusion(@NotNull final Player player, @NotNull final ExclusionZone zone,
                               @NotNull final Settings.ParticleSettings ps, final float phase,
                               @NotNull final ParticleCounter counter) {
        if (zone.shape() == ExclusionZone.Shape.SPHERE) {
            drawSphere(player, zone.cx(), zone.cy(), zone.cz(), zone.radius(), RED, phase, counter, ps);
            return;
        }
        if (zone.shape() == ExclusionZone.Shape.CYLINDER) {
            drawCylinder(player, zone.cx(), zone.cz(), zone.minY(), zone.maxY() + 1,
                    zone.radius(), RED, phase, counter, ps);
            return;
        }
        drawCuboid(player, zone.minX(), zone.minY(), zone.minZ(),
                zone.maxX() + 1, zone.maxY() + 1, zone.maxZ() + 1,
                RED, RED, true, ps, phase, counter);
    }

    private void drawCuboid(@NotNull final Player player,
                            final int x1, final int y1, final int z1,
                            final int x2, final int y2, final int z2,
                            @NotNull final Particle.DustOptions edge,
                            @NotNull final Particle.DustOptions fill,
                            final boolean faces,
                            @NotNull final Settings.ParticleSettings ps,
                            final float phase,
                            @NotNull final ParticleCounter counter) {
        final double dx = Math.abs(x2 - x1);
        final double dy = Math.abs(y2 - y1);
        final double dz = Math.abs(z2 - z1);
        final double maxDim = Math.max(dx, Math.max(dy, dz));
        final double density = Math.max(6, ps.borderDensity);
        final double edgeStep = Math.max(0.45, Math.min(1.15, maxDim / (density * 10.0)));

        drawEdge(player, edge, x1, y1, z1, x2, y1, z1, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x1, y1, z2, x2, y1, z2, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x1, y2, z1, x2, y2, z1, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x1, y2, z2, x2, y2, z2, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x1, y1, z1, x1, y2, z1, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x2, y1, z1, x2, y2, z1, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x1, y1, z2, x1, y2, z2, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x2, y1, z2, x2, y2, z2, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x1, y1, z1, x1, y1, z2, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x2, y1, z1, x2, y1, z2, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x1, y2, z1, x1, y2, z2, edgeStep, phase, counter, ps);
        drawEdge(player, edge, x2, y2, z1, x2, y2, z2, edgeStep, phase, counter, ps);

        spawnDust(player, edge, x1, y1, z1, 4, 0.04, counter, ps);
        spawnDust(player, edge, x2, y1, z1, 4, 0.04, counter, ps);
        spawnDust(player, edge, x1, y2, z1, 4, 0.04, counter, ps);
        spawnDust(player, edge, x2, y2, z1, 4, 0.04, counter, ps);
        spawnDust(player, edge, x1, y1, z2, 4, 0.04, counter, ps);
        spawnDust(player, edge, x2, y1, z2, 4, 0.04, counter, ps);
        spawnDust(player, edge, x1, y2, z2, 4, 0.04, counter, ps);
        spawnDust(player, edge, x2, y2, z2, 4, 0.04, counter, ps);

        if (faces) {
            final double faceStep = Math.max(0.65, Math.min(1.25, edgeStep * 1.15));
            drawFaceGrid(player, fill, x1, y1, z1, x2, y2, z2, faceStep, counter, ps);
        }
    }

    private void drawFaceGrid(@NotNull final Player player, @NotNull final Particle.DustOptions dust,
                              final int x1, final int y1, final int z1,
                              final int x2, final int y2, final int z2,
                              final double step, @NotNull final ParticleCounter counter,
                              @NotNull final Settings.ParticleSettings ps) {
        final Location eye = player.getEyeLocation();
        final double view = Math.max(24.0, ps.viewDistanceBlocks);
        final double viewSq = view * view;

        drawVerticalFace(player, dust, x1, x2, y1, y2, z1, step, counter, eye, viewSq);
        drawVerticalFace(player, dust, x1, x2, y1, y2, z2, step, counter, eye, viewSq);
        drawSideFace(player, dust, x1, y1, y2, z1, z2, step, counter, eye, viewSq);
        drawSideFace(player, dust, x2, y1, y2, z1, z2, step, counter, eye, viewSq);
        drawHorizontalFace(player, dust, x1, x2, y1, z1, z2, step, counter, eye, viewSq);
        drawHorizontalFace(player, dust, x1, x2, y2, z1, z2, step, counter, eye, viewSq);
    }

    private void drawVerticalFace(@NotNull final Player player, @NotNull final Particle.DustOptions dust,
                                  final int x1, final int x2, final int y1, final int y2, final int z,
                                  final double step, @NotNull final ParticleCounter counter,
                                  @NotNull final Location eye, final double viewSq) {
        if (!faceNear(eye, (x1 + x2) * 0.5, (y1 + y2) * 0.5, z, viewSq * 1.6)) {
            return;
        }
        for (double x = x1; x <= x2 + 0.001; x += step) {
            for (double y = y1; y <= y2 + 0.001; y += step) {
                spawnDustNear(player, dust, x, y, z, counter, eye, viewSq);
            }
        }
    }

    private void drawSideFace(@NotNull final Player player, @NotNull final Particle.DustOptions dust,
                              final int x, final int y1, final int y2, final int z1, final int z2,
                              final double step, @NotNull final ParticleCounter counter,
                              @NotNull final Location eye, final double viewSq) {
        if (!faceNear(eye, x, (y1 + y2) * 0.5, (z1 + z2) * 0.5, viewSq * 1.6)) {
            return;
        }
        for (double z = z1; z <= z2 + 0.001; z += step) {
            for (double y = y1; y <= y2 + 0.001; y += step) {
                spawnDustNear(player, dust, x, y, z, counter, eye, viewSq);
            }
        }
    }

    private void drawHorizontalFace(@NotNull final Player player, @NotNull final Particle.DustOptions dust,
                                    final int x1, final int x2, final int y, final int z1, final int z2,
                                    final double step, @NotNull final ParticleCounter counter,
                                    @NotNull final Location eye, final double viewSq) {
        if (!faceNear(eye, (x1 + x2) * 0.5, y, (z1 + z2) * 0.5, viewSq * 1.6)) {
            return;
        }
        for (double x = x1; x <= x2 + 0.001; x += step) {
            for (double z = z1; z <= z2 + 0.001; z += step) {
                spawnDustNear(player, dust, x, y, z, counter, eye, viewSq);
            }
        }
    }

    private boolean faceNear(@NotNull final Location eye, final double x, final double y, final double z,
                             final double maxSq) {
        final double dx = eye.getX() - x;
        final double dy = eye.getY() - y;
        final double dz = eye.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= maxSq;
    }

    private void drawSphere(@NotNull final Player player, final double cx, final double cy, final double cz,
                            final double radius, @NotNull final Particle.DustOptions dust,
                            final float phase, @NotNull final ParticleCounter counter,
                            @NotNull final Settings.ParticleSettings ps) {
        final int rings = Math.max(8, Math.min(24, (int) (radius * 2.0)));
        final double step = Math.max(0.45, radius / 12.0);
        for (int i = 0; i < rings; i++) {
            final double t = (i + phase) / rings * Math.PI;
            final double ringR = Math.sin(t) * radius;
            final double y = cy + Math.cos(t) * radius;
            final int dots = Math.max(10, (int) (ringR * Math.PI * 2.0 / step));
            for (int d = 0; d < dots; d++) {
                final double a = (d / (double) dots) * Math.PI * 2.0 + phase;
                spawnDust(player, dust, cx + Math.cos(a) * ringR, y, cz + Math.sin(a) * ringR, 1, 0.0, counter, ps);
            }
        }
    }

    private void drawCylinder(@NotNull final Player player, final double cx, final double cz,
                              final int minY, final int maxY, final double radius,
                              @NotNull final Particle.DustOptions dust, final float phase,
                              @NotNull final ParticleCounter counter,
                              @NotNull final Settings.ParticleSettings ps) {
        final double step = Math.max(0.5, radius / 10.0);
        final int dots = Math.max(14, (int) (radius * Math.PI * 2.0 / step));
        for (int y = minY; y <= maxY; y += Math.max(1, (int) Math.ceil(step))) {
            for (int d = 0; d < dots; d++) {
                final double a = (d / (double) dots) * Math.PI * 2.0 + phase;
                spawnDust(player, dust, cx + Math.cos(a) * radius, y, cz + Math.sin(a) * radius, 1, 0.0, counter, ps);
            }
        }
    }

    private void drawEdge(@NotNull final Player player, @NotNull final Particle.DustOptions dust,
                          final double x1, final double y1, final double z1,
                          final double x2, final double y2, final double z2,
                          final double step, final float phase,
                          @NotNull final ParticleCounter counter,
                          @NotNull final Settings.ParticleSettings ps) {
        final double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) return;
        final double ux = dx / len, uy = dy / len, uz = dz / len;
        double t = (phase * step) % step;
        for (; t <= len; t += step) {
            spawnDust(player, dust, x1 + ux * t, y1 + uy * t, z1 + uz * t, 1, 0.0, counter, ps);
        }
    }

    private void drawCornerPillar(@NotNull final Player player, @Nullable final Location loc,
                                  @NotNull final Particle.DustOptions dust,
                                  @NotNull final ParticleCounter counter) {
        if (loc == null || loc.getWorld() == null) return;
        if (player.getWorld() != loc.getWorld()) return;
        final Settings.ParticleSettings ps = configManager.particles();
        for (int i = 0; i <= 4; i++) {
            spawnDust(player, dust, loc.getBlockX() + 0.5, loc.getBlockY() + i, loc.getBlockZ() + 0.5, 3, 0.05, counter, ps);
        }
    }

    private void spawnDustNear(@NotNull final Player player, @NotNull final Particle.DustOptions dust,
                               final double x, final double y, final double z,
                               @NotNull final ParticleCounter counter,
                               @NotNull final Location eye, final double viewSq) {
        final double dx = eye.getX() - x;
        final double dy = eye.getY() - y;
        final double dz = eye.getZ() - z;
        if (dx * dx + dy * dy + dz * dz > viewSq) {
            return;
        }
        spawnDust(player, dust, x, y, z, 1, 0.0, counter, null);
    }

    private void spawnDust(@NotNull final Player player, @NotNull final Particle.DustOptions dust,
                           final double x, final double y, final double z,
                           final int count, final double extra,
                           @NotNull final ParticleCounter counter,
                           @Nullable final Settings.ParticleSettings ps) {
        if (!counter.allow()) return;
        if (ps != null) {
            final Location eye = player.getEyeLocation();
            final double view = Math.max(32.0, ps.viewDistanceBlocks + 8.0);
            final double dx = eye.getX() - x;
            final double dy = eye.getY() - y;
            final double dz = eye.getZ() - z;
            if (dx * dx + dy * dy + dz * dz > view * view) {
                return;
            }
        }
        try {
            player.spawnParticle(Particle.DUST, x, y, z, count, extra, extra, extra, 0.0, dust);
        } catch (final Throwable ignored) {
        }
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        active.clear();
        previews.clear();
    }

    private static final class Preview {
        final Arena arena;
        final long until;

        Preview(final Arena arena, final long until) {
            this.arena = arena;
            this.until = until;
        }
    }

    private static final class ParticleCounter {
        int count;

        boolean allow() {
            return ++count <= MAX_PARTICLES;
        }
    }
}
