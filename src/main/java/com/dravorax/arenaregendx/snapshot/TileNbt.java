/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.snapshot;

import com.dravorax.arenaregendx.nms.NmsAccess;
import org.bukkit.block.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Prefixes tile payloads with {@code NBT\1} when CraftBukkit snapshot NBT is
 * available, so chests, signs, spawners and the rest round-trip with full
 * component data. Legacy handler payloads remain readable.
 *
 * @author DravoraX
 */
public final class TileNbt {

    public static final byte[] MAGIC = new byte[]{'N', 'B', 'T', 1};

    private TileNbt() {
    }

    public static boolean isNbt(final byte @NotNull [] payload) {
        return payload.length > MAGIC.length
                && payload[0] == MAGIC[0]
                && payload[1] == MAGIC[1]
                && payload[2] == MAGIC[2]
                && payload[3] == MAGIC[3];
    }

    public static byte @Nullable [] wrap(@NotNull final BlockState state) {
        final byte[] nbt = NmsAccess.captureTileNbt(state);
        if (nbt == null || nbt.length == 0) return null;
        final byte[] out = new byte[MAGIC.length + nbt.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(nbt, 0, out, MAGIC.length, nbt.length);
        return out;
    }

    public static boolean restore(@NotNull final BlockState state, final byte @NotNull [] payload) {
        if (!isNbt(payload)) return false;
        final byte[] nbt = new byte[payload.length - MAGIC.length];
        System.arraycopy(payload, MAGIC.length, nbt, 0, nbt.length);
        // The caller applies state.update(...) exactly once.
        return NmsAccess.restoreTileNbt(state, nbt);
    }
}
