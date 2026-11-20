/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.arena;

import com.dravorax.arenaregendx.regen.RegenSpeed;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Named cuboid region bound to a world, with optional spawn and exclusion zones.
 *
 * @author DravoraX
 */
public final class Arena {

    private final String name;
    private final String worldName;
    private final int originX, originY, originZ;
    private final int sizeX, sizeY, sizeZ;
    private final long createdAt;

    private volatile long latestSnapshotId;
    private volatile long lastRegeneratedAt;
    private volatile long snapshotCount;
    private volatile ArenaStatus status;

    private volatile int autoResetInterval;
    private volatile boolean asyncRegen;
    /** -1 = inherit global performance.yml regen-speed-percent. */
    private volatile int regenSpeedPercent = RegenSpeed.INHERIT;
    private volatile boolean hasSpawn;
    private volatile double spawnX, spawnY, spawnZ;
    private volatile float spawnYaw, spawnPitch;

    /** Protected volumes that regeneration must never rewrite. */
    private final CopyOnWriteArrayList<ExclusionZone> exclusions = new CopyOnWriteArrayList<>();
    private volatile ExclusionIndex exclusionIndex;

    public Arena(@NotNull final String name, @NotNull final String worldName,
                 final int originX, final int originY, final int originZ,
                 final int sizeX, final int sizeY, final int sizeZ,
                 final long createdAt, final long latestSnapshotId,
                 final long lastRegeneratedAt, final long snapshotCount,
                 @NotNull final ArenaStatus status,
                 final int autoResetInterval, final boolean asyncRegen,
                 final boolean hasSpawn,
                 final double spawnX, final double spawnY, final double spawnZ,
                 final float spawnYaw, final float spawnPitch) {
        this.name = name;
        this.worldName = worldName;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.createdAt = createdAt;
        this.latestSnapshotId = latestSnapshotId;
        this.lastRegeneratedAt = lastRegeneratedAt;
        this.snapshotCount = snapshotCount;
        this.status = status;
        this.autoResetInterval = autoResetInterval;
        this.asyncRegen = asyncRegen;
        this.hasSpawn = hasSpawn;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;
    }

    public @NotNull String name() { return name; }
    public @NotNull String worldName() { return worldName; }
    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public long createdAt() { return createdAt; }
    public long latestSnapshotId() { return latestSnapshotId; }
    public long lastRegeneratedAt() { return lastRegeneratedAt; }
    public long snapshotCount() { return snapshotCount; }
    public @NotNull ArenaStatus status() { return status; }

    public int autoResetInterval() { return autoResetInterval; }
    public boolean asyncRegen() { return asyncRegen; }

    /** -1 inherits global speed from performance.yml. */
    public int regenSpeedPercent() { return regenSpeedPercent; }

    public boolean hasSpawn() { return hasSpawn; }
    public double spawnX() { return spawnX; }
    public double spawnY() { return spawnY; }
    public double spawnZ() { return spawnZ; }
    public float spawnYaw() { return spawnYaw; }
    public float spawnPitch() { return spawnPitch; }

    public int blockCount() {
        return sizeX * sizeY * sizeZ;
    }

    /** @return true if the (fractional) point lies inside the arena cuboid. */
    public boolean containsPoint(final double x, final double y, final double z) {
        return x >= originX && x < originX + sizeX
                && y >= originY && y < originY + sizeY
                && z >= originZ && z < originZ + sizeZ;
    }

    /** @return a read-only live view of the exclusion zones. */
    public @NotNull List<ExclusionZone> exclusions() {
        return java.util.Collections.unmodifiableList(exclusions);
    }

    /** Cached immutable index; rebuilt lazily after exclusions change. */
    public @NotNull ExclusionIndex exclusionIndex() {
        ExclusionIndex index = exclusionIndex;
        if (index == null) {
            index = ExclusionIndex.of(exclusions);
            exclusionIndex = index;
        }
        return index;
    }

    public void setExclusions(@NotNull final List<ExclusionZone> zones) {
        exclusions.clear();
        exclusions.addAll(zones);
        exclusionIndex = null;
    }

    public synchronized boolean addExclusion(@NotNull final ExclusionZone zone) {
        exclusions.removeIf(z -> z.name().equalsIgnoreCase(zone.name()));
        exclusions.add(zone);
        exclusionIndex = null;
        return true;
    }

    public synchronized boolean removeExclusion(@NotNull final String zoneName) {
        final boolean removed = exclusions.removeIf(z -> z.name().equalsIgnoreCase(zoneName));
        exclusionIndex = null;
        return removed;
    }

    public @Nullable ExclusionZone exclusion(@NotNull final String zoneName) {
        for (final ExclusionZone z : exclusions) {
            if (z.name().equalsIgnoreCase(zoneName)) {
                return z;
            }
        }
        return null;
    }

    public boolean isExcluded(final int x, final int y, final int z) {
        return exclusionIndex().contains(x, y, z);
    }

    public synchronized void onSnapshotSaved(final long snapshotId) {
        this.latestSnapshotId = snapshotId;
        this.snapshotCount++;
    }

    /** Make an existing snapshot the restore point (see {@code /ardx load}). */
    public synchronized void selectSnapshot(final long snapshotId) {
        this.latestSnapshotId = snapshotId;
        if (status == ArenaStatus.EMPTY || status == ArenaStatus.ERROR) {
            status = ArenaStatus.READY;
        }
    }

    /**
     * Atomically move into {@link ArenaStatus#SAVING}.
     *
     * @return false if the arena is already saving or regenerating.
     */
    public synchronized boolean beginSaving() {
        if (status == ArenaStatus.SAVING || status == ArenaStatus.REGENERATING) {
            return false;
        }
        status = ArenaStatus.SAVING;
        return true;
    }

    /** Leave {@link ArenaStatus#SAVING}: READY on success, otherwise back to READY/EMPTY. */
    public synchronized void endSaving(final boolean success) {
        if (status != ArenaStatus.SAVING) {
            return;
        }
        status = success || latestSnapshotId > 0 ? ArenaStatus.READY : ArenaStatus.EMPTY;
    }

    public synchronized void markRegenerated() {
        this.lastRegeneratedAt = System.currentTimeMillis();
        this.status = ArenaStatus.READY;
    }

    public synchronized void setStatus(@NotNull final ArenaStatus status) {
        this.status = status;
    }

    /** Set {@code next} only if the current status is {@code expected}. */
    public synchronized boolean compareAndSetStatus(@NotNull final ArenaStatus expected,
                                                    @NotNull final ArenaStatus next) {
        if (status != expected) {
            return false;
        }
        status = next;
        return true;
    }

    public void setAutoResetInterval(final int seconds) {
        this.autoResetInterval = Math.max(0, seconds);
    }

    public void setAsyncRegen(final boolean async) {
        this.asyncRegen = async;
    }

    public void setRegenSpeedPercent(final int percent) {
        this.regenSpeedPercent = RegenSpeed.clampStored(percent);
    }

    public void setSpawn(final double x, final double y, final double z,
                         final float yaw, final float pitch) {
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
        this.hasSpawn = true;
    }

    public void clearSpawn() {
        this.hasSpawn = false;
    }

    public boolean contains(final int x, final int y, final int z) {
        return x >= originX && x < originX + sizeX
                && y >= originY && y < originY + sizeY
                && z >= originZ && z < originZ + sizeZ;
    }
}
