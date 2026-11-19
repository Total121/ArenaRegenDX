/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.snapshot;

import org.jetbrains.annotations.NotNull;

/**
 * Identity metadata for a snapshot — lives outside the {@link Snapshot} payload
 * so it can be read without decompressing the snapshot body (used for listings,
 * arena-management GUI, etc.).
 *
 * @param arenaName   the owning arena name.
 * @param snapshotId  the snapshot id (auto-incremented or timestamp-based).
 * @param worldName   the world name at capture time.
 * @param originX     region min corner X.
 * @param originY     region min corner Y.
 * @param originZ     region min corner Z.
 * @param sizeX       region size X.
 * @param sizeY       region size Y.
 * @param sizeZ       region size Z.
 * @param createdAt   epoch millis at capture.
 * @param blockCount  total blocks captured.
 * @param fileSize    on-disk file size in bytes.
 * @param compressed  whether the on-disk file is compressed.
 *
 * @author DravoraX
 * @since 1.0.0
 */
public record SnapshotMetadata(
        @NotNull String arenaName,
        long snapshotId,
        @NotNull String worldName,
        int originX, int originY, int originZ,
        int sizeX, int sizeY, int sizeZ,
        long createdAt,
        int blockCount,
        long fileSize,
        boolean compressed
) {
}
