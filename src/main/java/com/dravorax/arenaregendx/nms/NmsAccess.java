/*
 * ArenaRegenDX - High-performance arena regeneration
 * Copyright (C) 2026 DravoraX. All rights reserved.
 * Licensed exclusively to DravoraX.
 */

package com.dravorax.arenaregendx.nms;

import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Reflective Paper (Mojang-mapped) bridge, resolved once into {@link MethodHandle}s.
 *
 * <p>Block writes use {@code Level.setBlock(pos, state, 2 | 16 | 1024)}, the same
 * flags CraftBukkit uses for {@code setBlockData(data, false)}: notify clients,
 * no neighbour shape updates, no {@code onPlace}. Before replacing a block that
 * owns a block entity with a different block, the block entity is removed first
 * (as CraftBukkit does) so containers never spill their items on restore.</p>
 *
 * <p>If any lookup fails the whole fast path is disabled and callers fall back
 * to the Bukkit API, so the plugin keeps working on unexpected forks.</p>
 *
 * @author DravoraX
 */
public final class NmsAccess {

    /** UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | CraftBukkit "no onPlace" (1024). */
    private static final int FLAGS_NO_PHYSICS = 2 | 16 | 1024;

    private static final boolean AVAILABLE;
    private static final String STATUS;

    private static final MethodHandle GET_HANDLE;        // CraftWorld -> ServerLevel
    private static final MethodHandle GET_NMS_STATE;     // CraftBlockData -> BlockState
    private static final MethodHandle NEW_BLOCK_POS;     // (int,int,int) -> BlockPos
    private static final MethodHandle SET_BLOCK;         // (Level, BlockPos, BlockState, int) -> boolean
    private static final MethodHandle GET_BLOCK_STATE;   // (Level, BlockPos) -> BlockState
    private static final MethodHandle REMOVE_BLOCK_ENTITY; // (Level, BlockPos) -> void
    private static final MethodHandle STATE_IS_AIR;      // BlockState -> boolean
    private static final MethodHandle STATE_HAS_BE;      // BlockState -> boolean
    private static final MethodHandle STATE_GET_BLOCK;   // BlockState -> Block

    private static final Method GET_SNAPSHOT_NBT;
    private static final Method LOAD_NBT;
    private static final Method NBT_IO_WRITE;
    private static final Method NBT_IO_READ;

    private static volatile boolean nbtEnabled = true;

    static {
        MethodHandle getHandle = null, getNmsState = null, newBlockPos = null, setBlock = null;
        MethodHandle getBlockState = null, removeBe = null, isAir = null, hasBe = null, getBlock = null;
        Method getSnapshotNbt = null, loadNbt = null, nbtWrite = null, nbtRead = null;
        String status = "bukkit-fallback";
        boolean ok = false;
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            final Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            final Class<?> craftData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
            final Method handleMethod = craftWorld.getMethod("getHandle");
            final Class<?> levelType = handleMethod.getReturnType();
            final Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
            final Class<?> nmsState = Class.forName("net.minecraft.world.level.block.state.BlockState");

            getHandle = lookup.unreflect(handleMethod).asType(
                    java.lang.invoke.MethodType.methodType(Object.class, World.class));
            getNmsState = lookup.unreflect(craftData.getMethod("getState")).asType(
                    java.lang.invoke.MethodType.methodType(Object.class, BlockData.class));
            newBlockPos = lookup.unreflectConstructor(blockPos.getConstructor(int.class, int.class, int.class))
                    .asType(java.lang.invoke.MethodType.methodType(Object.class, int.class, int.class, int.class));

            final Method set = findSetBlock(levelType, blockPos, nmsState);
            if (set == null) {
                throw new IllegalStateException("Level.setBlock not found");
            }
            setBlock = lookup.unreflect(set).asType(java.lang.invoke.MethodType.methodType(
                    boolean.class, Object.class, Object.class, Object.class, int.class));
            getBlockState = lookup.unreflect(levelType.getMethod("getBlockState", blockPos)).asType(
                    java.lang.invoke.MethodType.methodType(Object.class, Object.class, Object.class));
            removeBe = lookup.unreflect(levelType.getMethod("removeBlockEntity", blockPos)).asType(
                    java.lang.invoke.MethodType.methodType(void.class, Object.class, Object.class));
            isAir = lookup.unreflect(nmsState.getMethod("isAir")).asType(
                    java.lang.invoke.MethodType.methodType(boolean.class, Object.class));
            hasBe = lookup.unreflect(nmsState.getMethod("hasBlockEntity")).asType(
                    java.lang.invoke.MethodType.methodType(boolean.class, Object.class));
            getBlock = lookup.unreflect(nmsState.getMethod("getBlock")).asType(
                    java.lang.invoke.MethodType.methodType(Object.class, Object.class));

            try {
                final Class<?> craftBes = Class.forName("org.bukkit.craftbukkit.block.CraftBlockEntityState");
                getSnapshotNbt = findNoArg(craftBes, "getSnapshotNBT", "getSnapshotNbt");
                loadNbt = findLoad(craftBes);
                final Class<?> compoundTag = Class.forName("net.minecraft.nbt.CompoundTag");
                final Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
                nbtWrite = findNbtWrite(nbtIo, compoundTag);
                nbtRead = findNbtRead(nbtIo, compoundTag);
            } catch (final ClassNotFoundException ignored) {
                // tile NBT optional
            }
            ok = true;
            status = getSnapshotNbt != null && loadNbt != null && nbtWrite != null && nbtRead != null
                    ? "nms+nbt" : "nms-blocks";
        } catch (final Throwable t) {
            status = "bukkit-fallback (" + t.getClass().getSimpleName() + ")";
        }
        GET_HANDLE = getHandle;
        GET_NMS_STATE = getNmsState;
        NEW_BLOCK_POS = newBlockPos;
        SET_BLOCK = setBlock;
        GET_BLOCK_STATE = getBlockState;
        REMOVE_BLOCK_ENTITY = removeBe;
        STATE_IS_AIR = isAir;
        STATE_HAS_BE = hasBe;
        STATE_GET_BLOCK = getBlock;
        GET_SNAPSHOT_NBT = getSnapshotNbt;
        LOAD_NBT = loadNbt;
        NBT_IO_WRITE = nbtWrite;
        NBT_IO_READ = nbtRead;
        AVAILABLE = ok;
        STATUS = status;
    }

    private NmsAccess() {
    }

    public static boolean available() {
        return AVAILABLE;
    }

    /** Toggle tile NBT capture/restore at runtime ({@code performance.yml → nbt-tiles}). */
    public static void setNbtEnabled(final boolean enabled) {
        nbtEnabled = enabled;
    }

    public static boolean nbtAvailable() {
        return nbtEnabled && GET_SNAPSHOT_NBT != null && LOAD_NBT != null && NBT_IO_WRITE != null && NBT_IO_READ != null;
    }

    public static @NotNull String status() {
        return STATUS;
    }

    // -----------------------------------------------------------------------
    // Block access (call only on the thread that owns the chunk)
    // -----------------------------------------------------------------------

    /** @return the NMS level for {@code world}, or null if unavailable. */
    public static @Nullable Object level(@NotNull final World world) {
        if (!AVAILABLE) return null;
        try {
            return GET_HANDLE.invokeExact(world);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** @return the canonical NMS block state for Bukkit {@code data}, or null. */
    public static @Nullable Object nmsState(@NotNull final BlockData data) {
        if (GET_NMS_STATE == null) return null;
        try {
            return GET_NMS_STATE.invokeExact(data);
        } catch (final Throwable t) {
            return null;
        }
    }

    public static @NotNull Object blockPos(final int x, final int y, final int z) throws Throwable {
        return NEW_BLOCK_POS.invokeExact(x, y, z);
    }

    /** @return the current NMS block state at {@code pos}. */
    public static @NotNull Object stateAt(@NotNull final Object level, @NotNull final Object pos) throws Throwable {
        return GET_BLOCK_STATE.invokeExact(level, pos);
    }

    public static boolean isAir(@NotNull final Object nmsState) throws Throwable {
        return (boolean) STATE_IS_AIR.invokeExact(nmsState);
    }

    /**
     * Write {@code target} at {@code pos} without physics. {@code current} is the
     * state already there (from {@link #stateAt}); used to clear a block entity
     * that would otherwise drop its contents.
     */
    public static boolean write(@NotNull final Object level, @NotNull final Object pos,
                                @NotNull final Object current, @NotNull final Object target) throws Throwable {
        if ((boolean) STATE_HAS_BE.invokeExact(current)
                && STATE_GET_BLOCK.invokeExact(current) != STATE_GET_BLOCK.invokeExact(target)) {
            REMOVE_BLOCK_ENTITY.invokeExact(level, pos);
        }
        return (boolean) SET_BLOCK.invokeExact(level, pos, target, FLAGS_NO_PHYSICS);
    }

    // -----------------------------------------------------------------------
    // Tile entity NBT
    // -----------------------------------------------------------------------

    public static byte @Nullable [] captureTileNbt(@NotNull final BlockState state) {
        if (!nbtAvailable()) return null;
        try {
            final Object tag = GET_SNAPSHOT_NBT.invoke(state);
            if (tag == null) return null;
            final ByteArrayOutputStream bout = new ByteArrayOutputStream(256);
            final DataOutputStream out = new DataOutputStream(bout);
            if (NBT_IO_WRITE.getParameterTypes()[0].isInstance(tag)) {
                NBT_IO_WRITE.invoke(null, tag, out);
            } else {
                NBT_IO_WRITE.invoke(null, out, tag);
            }
            out.flush();
            return bout.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    public static boolean restoreTileNbt(@NotNull final BlockState state, final byte @NotNull [] nbt) {
        if (!nbtAvailable()) return false;
        try {
            java.io.InputStream raw = new ByteArrayInputStream(nbt);
            if (nbt.length > 2 && (nbt[0] & 0xFF) == 0x1F && (nbt[1] & 0xFF) == 0x8B) {
                // Older builds could pick NbtIo.writeCompressed (gzip) for capture.
                raw = new java.util.zip.GZIPInputStream(raw);
            }
            final Object tag = NBT_IO_READ.invoke(null, new DataInputStream(raw));
            if (tag == null) return false;
            LOAD_NBT.invoke(state, tag);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Lookup helpers
    // -----------------------------------------------------------------------

    private static @Nullable Method findSetBlock(@NotNull final Class<?> levelType,
                                                 @NotNull final Class<?> blockPos,
                                                 @NotNull final Class<?> nmsState) {
        try {
            final Method m = levelType.getMethod("setBlock", blockPos, nmsState, int.class);
            if (m.getReturnType() == boolean.class) {
                return m;
            }
        } catch (final NoSuchMethodException ignored) {
            // walk declared methods below
        }
        for (Class<?> c = levelType; c != null; c = c.getSuperclass()) {
            for (final Method m : c.getDeclaredMethods()) {
                final Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("setBlock") && p.length == 3 && p[0].isAssignableFrom(blockPos)
                        && p[1].isAssignableFrom(nmsState) && p[2] == int.class && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static @Nullable Method findNoArg(@NotNull final Class<?> owner, @NotNull final String... names) {
        for (final String name : names) {
            try {
                final Method m = owner.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (final NoSuchMethodException ignored) {
                // try next
            }
        }
        return null;
    }

    private static @Nullable Method findLoad(@NotNull final Class<?> owner) {
        for (final String name : new String[]{"load", "loadData", "setFromNbt", "applySnapshotNBT"}) {
            for (final Method m : owner.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static @Nullable Method findNbtWrite(@NotNull final Class<?> nbtIo, @NotNull final Class<?> compound) {
        for (final Method m : nbtIo.getMethods()) {
            if (!m.getName().equals("write")) continue;
            final Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && (p[0] == compound || p[1] == compound)
                    && (java.io.DataOutput.class.isAssignableFrom(p[0]) || java.io.DataOutput.class.isAssignableFrom(p[1])
                    || p[0] == java.io.DataOutput.class || p[1] == java.io.DataOutput.class)) {
                return m;
            }
        }
        return null;
    }

    private static @Nullable Method findNbtRead(@NotNull final Class<?> nbtIo, @NotNull final Class<?> compound) {
        try {
            final Method m = nbtIo.getMethod("read", java.io.DataInput.class);
            if (m.getReturnType() == compound) {
                return m;
            }
        } catch (final NoSuchMethodException ignored) {
            // scan below
        }
        for (final Method m : nbtIo.getMethods()) {
            if (m.getName().equals("read") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == java.io.DataInput.class && m.getReturnType() == compound) {
                return m;
            }
        }
        return null;
    }
}
