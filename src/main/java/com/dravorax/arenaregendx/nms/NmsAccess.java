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
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reflective Paper/NMS bridge. Resolved once, then used as near-direct calls.
 *
 * <p>Writes use {@code Level.setBlock} with client+shape flags (no neighbour
 * physics, no drops). Tile entities round-trip through snapshot NBT when the
 * CraftBukkit methods exist. Everything falls back to Bukkit if a lookup fails
 * so the plugin still runs on Spigot-like forks.</p>
 *
 * @author DravoraX
 */
public final class NmsAccess {

    /** UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SKIP_ON_PLACE (1.21). */
    private static final int FLAGS_NO_PHYSICS = 2 | 16 | 1024;

    private static final boolean AVAILABLE;
    private static final String STATUS;

    private static final MethodHandle GET_HANDLE;
    private static final MethodHandle GET_NMS_STATE;
    private static final Constructor<?> BLOCK_POS_CTOR;
    private static final MethodHandle SET_BLOCK;
    private static final Method GET_SNAPSHOT_NBT;
    private static final Method LOAD_NBT;
    private static final Method NBT_IO_WRITE;
    private static final Method NBT_IO_READ;
    private static final Class<?> COMPOUND_TAG;

    static {
        MethodHandle getHandle = null;
        MethodHandle getNmsState = null;
        Constructor<?> blockPosCtor = null;
        MethodHandle setBlock = null;
        Method getSnapshotNbt = null;
        Method loadNbt = null;
        Method nbtWrite = null;
        Method nbtRead = null;
        Class<?> compoundTag = null;
        String status = "bukkit-fallback";
        boolean ok = false;
        try {
            final Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            final Class<?> craftData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
            getHandle = MethodHandles.lookup().unreflect(craftWorld.getMethod("getHandle"));
            getNmsState = MethodHandles.lookup().unreflect(craftData.getMethod("getState"));

            Class<?> blockPos = load("net.minecraft.core.BlockPos");
            blockPosCtor = blockPos.getConstructor(int.class, int.class, int.class);

            final Class<?> nmsState = load("net.minecraft.world.level.block.state.BlockState");
            final Class<?> handleProbeType = craftWorld.getMethod("getHandle").getReturnType();
            Method set = findSetBlock(handleProbeType, blockPos, nmsState);
            if (set == null) {
                // ServerLevel extends Level — walk supers.
                Class<?> c = handleProbeType;
                while (c != null && set == null) {
                    set = findSetBlock(c, blockPos, nmsState);
                    c = c.getSuperclass();
                }
            }
            if (set == null) {
                throw new IllegalStateException("Level.setBlock not found");
            }
            setBlock = MethodHandles.lookup().unreflect(set);

            try {
                final Class<?> craftBes = Class.forName("org.bukkit.craftbukkit.block.CraftBlockEntityState");
                getSnapshotNbt = findNoArg(craftBes, "getSnapshotNBT", "getSnapshotNbt");
                loadNbt = findLoad(craftBes);
            } catch (final ClassNotFoundException ignored) {
            }

            compoundTag = load("net.minecraft.nbt.CompoundTag");
            final Class<?> nbtIo = load("net.minecraft.nbt.NbtIo");
            nbtWrite = findNbtWrite(nbtIo, compoundTag);
            nbtRead = findNbtRead(nbtIo, compoundTag);

            ok = true;
            status = getSnapshotNbt != null ? "nms+nbt" : "nms-blocks";
        } catch (final Throwable t) {
            status = "bukkit-fallback (" + t.getClass().getSimpleName() + ")";
        }
        GET_HANDLE = getHandle;
        GET_NMS_STATE = getNmsState;
        BLOCK_POS_CTOR = blockPosCtor;
        SET_BLOCK = setBlock;
        GET_SNAPSHOT_NBT = getSnapshotNbt;
        LOAD_NBT = loadNbt;
        NBT_IO_WRITE = nbtWrite;
        NBT_IO_READ = nbtRead;
        COMPOUND_TAG = compoundTag;
        AVAILABLE = ok;
        STATUS = status;
    }

    private NmsAccess() {
    }

    public static boolean available() {
        return AVAILABLE;
    }

    public static boolean nbtAvailable() {
        return GET_SNAPSHOT_NBT != null && LOAD_NBT != null && NBT_IO_WRITE != null && NBT_IO_READ != null;
    }

    public static @NotNull String status() {
        return STATUS;
    }

    public static @Nullable Object nmsState(@NotNull final BlockData data) {
        if (GET_NMS_STATE == null) return null;
        try {
            return GET_NMS_STATE.invoke(data);
        } catch (final Throwable t) {
            return null;
        }
    }

    public static boolean setBlock(@NotNull final World world, final int x, final int y, final int z,
                                   @NotNull final Object nmsState) {
        if (!AVAILABLE) return false;
        try {
            final Object level = GET_HANDLE.invoke(world);
            final Object pos = BLOCK_POS_CTOR.newInstance(x, y, z);
            SET_BLOCK.invoke(level, pos, nmsState, FLAGS_NO_PHYSICS);
            return true;
        } catch (final Throwable t) {
            try {
                final Object level = GET_HANDLE.invoke(world);
                final Object pos = BLOCK_POS_CTOR.newInstance(x, y, z);
                SET_BLOCK.invoke(level, pos, nmsState, 2 | 16);
                return true;
            } catch (final Throwable t2) {
                return false;
            }
        }
    }

    public static byte @Nullable [] captureTileNbt(@NotNull final BlockState state) {
        if (!nbtAvailable()) return null;
        try {
            final Object tag = GET_SNAPSHOT_NBT.invoke(state);
            if (tag == null) return null;
            return writeTag(tag);
        } catch (final Throwable t) {
            return null;
        }
    }

    public static boolean restoreTileNbt(@NotNull final BlockState state, final byte @NotNull [] nbt) {
        if (!nbtAvailable()) return false;
        try {
            final Object tag = readTag(nbt);
            if (tag == null) return false;
            LOAD_NBT.invoke(state, tag);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    private static byte @Nullable [] writeTag(@NotNull final Object tag) {
        try {
            final ByteArrayOutputStream bout = new ByteArrayOutputStream(256);
            final DataOutputStream out = new DataOutputStream(bout);
            if (NBT_IO_WRITE.getParameterCount() == 2) {
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

    private static @Nullable Object readTag(final byte @NotNull [] bytes) {
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            return NBT_IO_READ.invoke(null, in);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static @NotNull Class<?> load(@NotNull final String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static @Nullable Method findSetBlock(@NotNull final Class<?> owner,
                                                 @NotNull final Class<?> blockPos,
                                                 @NotNull final Class<?> nmsState) {
        for (final Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals("setBlock") && !m.getName().equals("setBlockState")) continue;
            final Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0].isAssignableFrom(blockPos) && p[1].isAssignableFrom(nmsState)
                    && (p[2] == int.class || p[2] == Integer.TYPE)) {
                m.setAccessible(true);
                return m;
            }
        }
        try {
            final Method m = owner.getMethod("setBlock", blockPos, nmsState, int.class);
            m.setAccessible(true);
            return m;
        } catch (final NoSuchMethodException e) {
            return null;
        }
    }

    private static @Nullable Method findNoArg(@NotNull final Class<?> owner, @NotNull final String... names) {
        for (final String name : names) {
            try {
                final Method m = owner.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (final NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static @Nullable Method findLoad(@NotNull final Class<?> owner) {
        for (final String name : new String[]{"load", "loadData", "setFromNbt", "applySnapshotNBT"}) {
            for (final Method m : owner.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static @Nullable Method findNbtWrite(@NotNull final Class<?> nbtIo, @NotNull final Class<?> compound) {
        for (final Method m : nbtIo.getMethods()) {
            if (!m.getName().equals("write") && !m.getName().equals("writeCompressed")) continue;
            final Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && (p[0] == compound || p[1] == compound)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static @Nullable Method findNbtRead(@NotNull final Class<?> nbtIo, @NotNull final Class<?> compound) {
        for (final Method m : nbtIo.getMethods()) {
            if (!m.getName().equals("read") && !m.getName().equals("readCompressed")) continue;
            if (m.getParameterCount() == 1 && m.getReturnType() == compound) {
                m.setAccessible(true);
                return m;
            }
        }
        try {
            return nbtIo.getMethod("read", java.io.DataInput.class);
        } catch (final NoSuchMethodException e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static final MethodType MT = MethodType.methodType(Object.class);
}
