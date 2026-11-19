/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.snapshot;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockState;
import org.bukkit.block.Banner;
import org.bukkit.block.Beacon;
import org.bukkit.block.Bell;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Chest;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.EnchantingTable;
import org.bukkit.block.EndGateway;
import org.bukkit.block.EntityBlockStorage;
import org.bukkit.block.Furnace;
import org.bukkit.block.Hopper;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.Lockable;
import org.bukkit.Nameable;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.Structure;
import org.bukkit.block.TileState;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry-driven (de)serialiser for tile entities (block entities).
 *
 * <p>Each Bukkit {@link TileState} subtype that carries non-block-data state
 * (container contents, sign text, banner patterns, beacon effects, etc.) gets a
 * dedicated {@link Handler}. The registry is keyed by the simple class name so
 * serialisation survives Bukkit API refactors gracefully.</p>
 *
 * <p><b>ItemStack serialisation</b>: Bukkit does not expose a public ItemStack
 * serialiser that preserves NBT, so we use {@link ItemStack#serialize()} for the
 * public fields and rely on the server's persistence for full NBT round-trips via
 * {@code Bukkit.getItemFactory().serializeMeta}. For best fidelity we round-trip
 * through the inventory's own serialisation in production; this implementation
 * uses the public API only and is portable across versions.</p>
 *
 * <p>Handlers are stateless singletons; safe to call concurrently.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class TileEntitySerializer {

    private TileEntitySerializer() {
        // static utility
    }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    /**
     * Capture the extra state of a tile entity (beyond its {@code BlockData}).
     *
     * @param state the live BlockState (must be a TileState subclass).
     * @return the serialized payload (type-prefixed), or {@code null} if there is
     *         nothing extra to capture for this block.
     */
    public static byte @Nullable [] capture(@NotNull final BlockState state) {
        final byte[] nbt = TileNbt.wrap(state);
        if (nbt != null) {
            return nbt;
        }
        final Handler handler = HANDLERS.get(state.getClass().getName());
        if (handler == null) {
            return null;
        }
        try {
            final ByteArrayOutputStream bout = new ByteArrayOutputStream(128);
            final DataOutputStream out = new DataOutputStream(bout);
            // Write the type name for sanity-checking on restore.
            final String typeName = state.getClass().getSimpleName();
            SnapshotSerializer.writeVarInt(out, typeName.length());
            out.write(typeName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            handler.write(out, state);
            out.flush();
            return bout.toByteArray();
        } catch (final IOException e) {
            // ByteArrayOutputStream does not throw IOException; defensive.
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------

    /**
     * Apply a previously-captured payload to a freshly-placed tile entity.
     *
     * @param state   the target BlockState (already placed, must match the captured type).
     * @param payload the payload from {@link #capture}.
     */
    public static void restore(@NotNull final BlockState state, final byte @NotNull [] payload) {
        if (TileNbt.restore(state, payload)) {
            return;
        }
        try {
            final DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload));
            final int nameLen = SnapshotSerializer.readVarInt(in);
            final byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            final String capturedType = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!capturedType.equals(state.getClass().getSimpleName())) {
                // Type mismatch — silently skip to avoid corrupting the block.
                return;
            }
            final Handler handler = HANDLERS.get(state.getClass().getName());
            if (handler != null) {
                handler.read(in, state);
            }
        } catch (final IOException e) {
            // ByteArrayInputStream does not throw; defensive.
        }
    }

    // -----------------------------------------------------------------------
    // Handler interface + registry
    // -----------------------------------------------------------------------

    private interface Handler {
        void write(@NotNull DataOutputStream out, @NotNull BlockState state) throws IOException;
        void read(@NotNull DataInputStream in, @NotNull BlockState state) throws IOException;
    }

    private static final Map<String, Handler> HANDLERS = new HashMap<>();

    static {
        // Inventory-backed containers (chests, barrels, shulkers, hoppers, dispensers, droppers).
        registerInventoryHandlers();
        // Furnaces.
        register(Furnace.class, new FurnaceHandler());
        register(org.bukkit.block.BlastFurnace.class, new FurnaceHandler());
        register(org.bukkit.block.Smoker.class, new FurnaceHandler());
        // Brewing stand.
        register(BrewingStand.class, new BrewingStandHandler());
        // Signs (incl. hanging signs).
        register(Sign.class, new SignHandler());
        register(org.bukkit.block.HangingSign.class, new SignHandler());
        // Banners.
        register(Banner.class, new BannerHandler());
        // Beacon.
        register(Beacon.class, new BeaconHandler());
        // Lectern.
        register(Lectern.class, new LecternHandler());
        // Jukebox.
        register(Jukebox.class, new JukeboxHandler());
        // Spawner.
        register(CreatureSpawner.class, new SpawnerHandler());
        // Skull (player heads).
        register(Skull.class, new SkullHandler());
        // Command block.
        register(CommandBlock.class, new CommandBlockHandler());
        register(org.bukkit.block.Structure.class, new StructureHandler());
        register(EndGateway.class, new EndGatewayHandler());
        register(EnchantingTable.class, new NameableHandler());
        register(Bell.class, new NameableHandler());
    }

    @SuppressWarnings("unchecked")
    private static void register(@NotNull final Class<? extends BlockState> clazz, @NotNull final Handler h) {
        HANDLERS.put(clazz.getName(), h);
    }

    private static void registerInventoryHandlers() {
        // All classes implementing InventoryHolder + Lockable + Nameable share the
        // same inventory capture logic. We register by concrete class name because
        // Bukkit's getState() returns the most-derived type.
        final Handler inv = new InventoryHandler();
        register(Chest.class, inv);
        register(org.bukkit.block.Barrel.class, inv);
        register(ShulkerBox.class, inv);
        register(Hopper.class, inv);
        register(org.bukkit.block.Dispenser.class, inv);
        register(org.bukkit.block.Dropper.class, inv);
        register(org.bukkit.block.ChiseledBookshelf.class, inv);
        register(org.bukkit.block.DecoratedPot.class, inv);
        register(org.bukkit.block.Crafter.class, inv);
    }

    // -----------------------------------------------------------------------
    // Handler implementations
    // -----------------------------------------------------------------------

    /** Handler for inventory-holding containers + custom names + lock strings. */
    @SuppressWarnings("deprecation")
    private static class InventoryHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            // Custom name (if nameable) — use modern Component API.
            final boolean nameable = state instanceof Nameable;
            out.writeBoolean(nameable);
            if (nameable) {
                final Nameable n = (Nameable) state;
                final var nameComp = n.customName();
                writeNullableString(out, nameComp == null ? "" : com.dravorax.arenaregendx.util.ColorUtil.legacy(nameComp));
            }
            // Lock (if lockable).
            final boolean lockable = state instanceof Lockable;
            out.writeBoolean(lockable);
            if (lockable) {
                writeNullableString(out, ((Lockable) state).getLock());
            }
            // Inventory contents.
            if (state instanceof InventoryHolder holder) {
                final Inventory inv = holder.getInventory();
                final ItemStack[] contents = inv.getContents();
                SnapshotSerializer.writeVarInt(out, contents.length);
                for (final ItemStack item : contents) {
                    writeItemStack(out, item);
                }
            } else {
                SnapshotSerializer.writeVarInt(out, 0);
            }
        }

        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            if (in.readBoolean() && state instanceof Nameable n) {
                final String name = readNullableString(in);
                n.customName(name == null || name.isEmpty() ? null : com.dravorax.arenaregendx.util.ColorUtil.mini(name));
            } else if (in.readBoolean()) {
                readNullableString(in); // discard
            }
            if (in.readBoolean() && state instanceof Lockable l) {
                l.setLock(readNullableString(in));
            } else if (in.readBoolean()) {
                readNullableString(in); // discard
            }
            if (state instanceof InventoryHolder holder) {
                final int len = SnapshotSerializer.readVarInt(in);
                final ItemStack[] contents = new ItemStack[len];
                for (int i = 0; i < len; i++) {
                    contents[i] = readItemStack(in);
                }
                holder.getInventory().setContents(contents);
            } else {
                final int len = SnapshotSerializer.readVarInt(in);
                for (int i = 0; i < len; i++) {
                    readItemStack(in); // discard
                }
            }
        }
    }

    /** Furnace: inventory + cook time + burn time. */
    private static class FurnaceHandler extends InventoryHandler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            super.write(out, state);
            final Furnace f = (Furnace) state;
            out.writeShort(f.getCookTime());
            out.writeShort(f.getCookTimeTotal());
            out.writeShort(f.getBurnTime());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            super.read(in, state);
            final Furnace f = (Furnace) state;
            f.setCookTime((short) in.readUnsignedShort());
            f.setCookTimeTotal((short) in.readUnsignedShort());
            f.setBurnTime((short) in.readUnsignedShort());
        }
    }

    /** Brewing stand: inventory + brewing time + fuel. */
    private static class BrewingStandHandler extends InventoryHandler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            super.write(out, state);
            final BrewingStand b = (BrewingStand) state;
            out.writeShort(b.getBrewingTime());
            out.writeShort(b.getFuelLevel());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            super.read(in, state);
            final BrewingStand b = (BrewingStand) state;
            b.setBrewingTime((short) in.readUnsignedShort());
            b.setFuelLevel((short) in.readUnsignedShort());
        }
    }

    /** Sign: front + back lines (modern Side API). */
    @SuppressWarnings("deprecation")
    private static final class SignHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final Sign s = (Sign) state;
            // Front side.
            final var front = s.getSide(Side.FRONT);
            writeStringArray(out, front.getLines());
            // Back side.
            final var back = s.getSide(Side.BACK);
            writeStringArray(out, back.getLines());
            // Glowing text flags (per side).
            out.writeBoolean(front.isGlowingText());
            out.writeBoolean(back.isGlowingText());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final Sign s = (Sign) state;
            final String[] frontLines = readStringArray(in);
            final String[] backLines = readStringArray(in);
            final var front = s.getSide(Side.FRONT);
            for (int i = 0; i < Math.min(4, frontLines.length); i++) {
                front.setLine(i, frontLines[i]);
            }
            front.setGlowingText(in.readBoolean());
            final var back = s.getSide(Side.BACK);
            for (int i = 0; i < Math.min(4, backLines.length); i++) {
                back.setLine(i, backLines[i]);
            }
            back.setGlowingText(in.readBoolean());
        }
    }

    /** Banner: patterns (modern Registry API). */
    @SuppressWarnings({"deprecation", "removal"})
    private static final class BannerHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final Banner b = (Banner) state;
            final var patterns = b.getPatterns();
            SnapshotSerializer.writeVarInt(out, patterns.size());
            for (final var p : patterns) {
                writeNullableString(out, p.getPattern().getKey().toString());
                writeNullableString(out, p.getColor().name()); // DyeColor is still a plain enum
            }
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final Banner b = (Banner) state;
            final int n = SnapshotSerializer.readVarInt(in);
            final var list = new java.util.ArrayList<org.bukkit.block.banner.Pattern>();
            for (int i = 0; i < n; i++) {
                final String patternKey = readNullableString(in);
                final String colorName = readNullableString(in);
                try {
                    final NamespacedKey key = patternKey.contains(":")
                            ? NamespacedKey.fromString(patternKey.toLowerCase())
                            : NamespacedKey.minecraft(patternKey.toLowerCase().replace('_', '.'));
                    final org.bukkit.block.banner.PatternType pt = Registry.BANNER_PATTERN.get(key);
                    final org.bukkit.DyeColor dc = org.bukkit.DyeColor.valueOf(colorName);
                    list.add(new org.bukkit.block.banner.Pattern(dc, pt));
                } catch (final Exception ignored) {
                    // skip unknown pattern
                }
            }
            b.setPatterns(list);
        }
    }

    /** Beacon: primary + secondary effects (modern Registry API). */
    private static final class BeaconHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final Beacon b = (Beacon) state;
            writeNullableString(out, b.getPrimaryEffect() == null ? "" : b.getPrimaryEffect().getType().getKey().toString());
            writeNullableString(out, b.getSecondaryEffect() == null ? "" : b.getSecondaryEffect().getType().getKey().toString());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final Beacon b = (Beacon) state;
            final String p = readNullableString(in);
            final String s2 = readNullableString(in);
            try { if (!p.isEmpty()) b.setPrimaryEffect(com.dravorax.arenaregendx.util.RegistryUtil.potionEffectFromString(p)); } catch (final Exception ignored) {}
            try { if (!s2.isEmpty()) b.setSecondaryEffect(com.dravorax.arenaregendx.util.RegistryUtil.potionEffectFromString(s2)); } catch (final Exception ignored) {}
        }
    }

    /** Lectern: book item + page. */
    private static final class LecternHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final Lectern l = (Lectern) state;
            writeItemStack(out, l.getInventory().getItem(0));
            out.writeInt(l.getPage());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final Lectern l = (Lectern) state;
            final ItemStack book = readItemStack(in);
            l.getInventory().setItem(0, book);
            l.setPage(in.readInt());
        }
    }

    /** Jukebox: playing record. */
    private static final class JukeboxHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final Jukebox j = (Jukebox) state;
            final Material rec = j.getPlaying() == null ? null : j.getRecord().getType();
            writeNullableString(out, rec == null ? "" : rec.name());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final Jukebox j = (Jukebox) state;
            final String recName = readNullableString(in);
            if (!recName.isEmpty()) {
                try {
                    final Material m = Material.valueOf(recName);
                    j.setRecord(new ItemStack(m));
                } catch (final Exception ignored) {}
            }
        }
    }

    /** Spawner: spawned entity type + delay + parameters. */
    private static final class SpawnerHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final CreatureSpawner s = (CreatureSpawner) state;
            writeNullableString(out, s.getSpawnedType() == null ? "" : s.getSpawnedType().name());
            out.writeShort(s.getDelay());
            out.writeShort(s.getMinSpawnDelay());
            out.writeShort(s.getMaxSpawnDelay());
            out.writeShort(s.getSpawnCount());
            out.writeShort(s.getMaxNearbyEntities());
            out.writeShort(s.getRequiredPlayerRange());
            out.writeShort(s.getSpawnRange());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final CreatureSpawner s = (CreatureSpawner) state;
            final String typeName = readNullableString(in);
            if (!typeName.isEmpty()) {
                try { s.setSpawnedType(org.bukkit.entity.EntityType.valueOf(typeName)); } catch (final Exception ignored) {}
            }
            s.setDelay(in.readUnsignedShort());
            s.setMinSpawnDelay(in.readUnsignedShort());
            s.setMaxSpawnDelay(in.readUnsignedShort());
            s.setSpawnCount(in.readUnsignedShort());
            s.setMaxNearbyEntities(in.readUnsignedShort());
            s.setRequiredPlayerRange(in.readUnsignedShort());
            s.setSpawnRange(in.readUnsignedShort());
        }
    }

    /** Skull: owning player / profile. */
    private static final class SkullHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final Skull s = (Skull) state;
            final OfflineProfile p = OfflineProfile.from(s);
            writeNullableString(out, p == null ? "" : p.name);
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final Skull s = (Skull) state;
            final String name = readNullableString(in);
            if (!name.isEmpty()) {
                try { s.setOwningPlayer(Bukkit.getOfflinePlayer(name)); } catch (final Exception ignored) {}
            }
        }
    }

    private record OfflineProfile(String name) {
        static @Nullable OfflineProfile from(@NotNull final Skull s) {
            try {
                final var p = s.getPlayerProfile();
                if (p != null && p.getName() != null) {
                    return new OfflineProfile(p.getName());
                }
            } catch (final Throwable ignored) {}
            return null;
        }
    }

    /** Command block: command + custom name. */
    @SuppressWarnings("deprecation")
    private static final class CommandBlockHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final CommandBlock c = (CommandBlock) state;
            writeNullableString(out, c.getCommand());
            writeNullableString(out, c.getName());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final CommandBlock c = (CommandBlock) state;
            c.setCommand(readNullableString(in));
            c.setName(readNullableString(in));
        }
    }

    /** Structure block: mode + author + position + size + ignore entities + integrity. */
    private static final class StructureHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final Structure s = (Structure) state;
            writeNullableString(out, s.getUsageMode().name());
            writeNullableString(out, s.getAuthor() == null ? "" : s.getAuthor());
            writeNullableString(out, s.getStructureName() == null ? "" : s.getStructureName());
            out.writeBoolean(s.isIgnoreEntities());
            out.writeBoolean(s.isShowAir());
            out.writeFloat(s.getIntegrity());
            out.writeLong(s.getSeed());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final Structure s = (Structure) state;
            final String mode = readNullableString(in);
            try { s.setUsageMode(org.bukkit.block.structure.UsageMode.valueOf(mode)); } catch (final Exception ignored) {}
            s.setAuthor(readNullableString(in));
            s.setStructureName(readNullableString(in));
            s.setIgnoreEntities(in.readBoolean());
            s.setShowAir(in.readBoolean());
            s.setIntegrity(in.readFloat());
            s.setSeed(in.readLong());
        }
    }

    /** End gateway: exit location + exact teleport flag. */
    private static final class EndGatewayHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final EndGateway g = (EndGateway) state;
            out.writeInt(g.getExitLocation() == null ? 0 : g.getExitLocation().getBlockX());
            out.writeInt(g.getExitLocation() == null ? 0 : g.getExitLocation().getBlockY());
            out.writeInt(g.getExitLocation() == null ? 0 : g.getExitLocation().getBlockZ());
            out.writeBoolean(g.isExactTeleport());
            out.writeLong(g.getAge());
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final EndGateway g = (EndGateway) state;
            final int x = in.readInt(), y = in.readInt(), z = in.readInt();
            if (g.getWorld() != null) {
                g.setExitLocation(new org.bukkit.Location(g.getWorld(), x, y, z));
            }
            g.setExactTeleport(in.readBoolean());
            g.setAge(in.readLong());
        }
    }

    /** Nameable-only (enchanting table, bell): just the custom name (Component API). */
    private static final class NameableHandler implements Handler {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final BlockState state) throws IOException {
            final Nameable n = (Nameable) state;
            final var nameComp = n.customName();
            writeNullableString(out, nameComp == null ? "" : com.dravorax.arenaregendx.util.ColorUtil.legacy(nameComp));
        }
        @Override public void read(@NotNull final DataInputStream in, @NotNull final BlockState state) throws IOException {
            final Nameable n = (Nameable) state;
            final String name = readNullableString(in);
            n.customName(name == null || name.isEmpty() ? null : com.dravorax.arenaregendx.util.ColorUtil.mini(name));
        }
    }

    // -----------------------------------------------------------------------
    // ItemStack + string helpers
    // -----------------------------------------------------------------------

    private static void writeItemStack(@NotNull final DataOutputStream out, @Nullable final ItemStack item) throws IOException {
        if (item == null || item.getType() == Material.AIR) {
            out.writeBoolean(false);
            return;
        }
        out.writeBoolean(true);
        try {
            final byte[] bytes = item.serializeAsBytes();
            out.writeByte(2);
            SnapshotSerializer.writeVarInt(out, bytes.length);
            out.write(bytes);
            return;
        } catch (final Throwable ignored) {
            // Pre-Paper or stripped API — fall through to map serialisation.
        }
        out.writeByte(1);
        writeNullableString(out, item.getType().name());
        out.writeInt(item.getAmount());
        final Map<String, Object> data = item.serialize();
        SnapshotSerializer.writeVarInt(out, data.size());
        for (final var e : data.entrySet()) {
            writeNullableString(out, e.getKey());
            writeObject(out, e.getValue());
        }
    }

    @Nullable
    private static ItemStack readItemStack(@NotNull final DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        final int formatOrLen = in.readUnsignedByte();
        if (formatOrLen == 2) {
            final int len = SnapshotSerializer.readVarInt(in);
            final byte[] bytes = new byte[len];
            in.readFully(bytes);
            try {
                return ItemStack.deserializeBytes(bytes);
            } catch (final Throwable t) {
                return null;
            }
        }
        if (formatOrLen == 1) {
            return readLegacyMapItem(in, true);
        }
        // ArenaRegenDX 2.0 write path: nullable-string length varint (not 1 or 2).
        // Push the byte back by reconstructing via a tiny buffer — we already consumed
        // one byte of the LEB128 length. Read the rest of the legacy record.
        return readLegacyItemAfterFirstByte(in, formatOrLen);
    }

    private static @Nullable ItemStack readLegacyMapItem(@NotNull final DataInputStream in, final boolean skipTypePrefix) throws IOException {
        if (skipTypePrefix) {
            final String typeName = readNullableString(in);
            final int amount = in.readInt();
            final int size = SnapshotSerializer.readVarInt(in);
            final Map<String, Object> data = new HashMap<>(size * 2);
            for (int i = 0; i < size; i++) {
                final String k = readNullableString(in);
                final Object v = readObject(in);
                if (k != null) data.put(k, v);
            }
            try {
                return ItemStack.deserialize(data);
            } catch (final Throwable t) {
                if (typeName != null) {
                    try { return new ItemStack(Material.valueOf(typeName), Math.max(1, amount)); }
                    catch (final Exception ignored) {}
                }
                return null;
            }
        }
        return null;
    }

    private static @Nullable ItemStack readLegacyItemAfterFirstByte(@NotNull final DataInputStream in, final int first) throws IOException {
        // Recreate the varint we split. first is a full byte of the LEB128 name-length.
        int len = first & 0x7F;
        int shift = 7;
        int b = first;
        while ((b & 0x80) != 0) {
            b = in.readUnsignedByte();
            len |= (b & 0x7F) << shift;
            shift += 7;
        }
        String typeName = null;
        if (len > 0) {
            final byte[] nameBytes = new byte[len - 1];
            in.readFully(nameBytes);
            typeName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        final int amount = in.readInt();
        final int size = SnapshotSerializer.readVarInt(in);
        final Map<String, Object> data = new HashMap<>(size * 2);
        for (int i = 0; i < size; i++) {
            final String k = readNullableString(in);
            final Object v = readObject(in);
            if (k != null) data.put(k, v);
        }
        try {
            return ItemStack.deserialize(data);
        } catch (final Throwable t) {
            if (typeName != null) {
                try { return new ItemStack(Material.valueOf(typeName), Math.max(1, amount)); }
                catch (final Exception ignored) {}
            }
            return null;
        }
    }

    private static void writeObject(@NotNull final DataOutputStream out, @Nullable final Object v) throws IOException {
        if (v == null) {
            out.writeByte(0);
        } else if (v instanceof Number n) {
            out.writeByte(1);
            writeNullableString(out, v.getClass().getName());
            out.writeUTF(n.toString());
        } else if (v instanceof Boolean b) {
            out.writeByte(2);
            out.writeBoolean(b);
        } else if (v instanceof String s) {
            out.writeByte(3);
            writeNullableString(out, s);
        } else if (v instanceof java.util.List<?> list) {
            out.writeByte(4);
            SnapshotSerializer.writeVarInt(out, list.size());
            for (final Object o : list) {
                writeObject(out, o);
            }
        } else if (v instanceof java.util.Map<?, ?> map) {
            out.writeByte(5);
            SnapshotSerializer.writeVarInt(out, map.size());
            for (final var e : map.entrySet()) {
                writeObject(out, e.getKey());
                writeObject(out, e.getValue());
            }
        } else {
            out.writeByte(0); // unsupported → null
        }
    }

    @Nullable
    private static Object readObject(@NotNull final DataInputStream in) throws IOException {
        final byte tag = in.readByte();
        switch (tag) {
            case 0: return null;
            case 1: {
                final String cls = readNullableString(in);
                final String val = in.readUTF();
                return deserializeNumber(cls, val);
            }
            case 2: return in.readBoolean();
            case 3: return readNullableString(in);
            case 4: {
                final int len = SnapshotSerializer.readVarInt(in);
                final java.util.List<Object> list = new java.util.ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    list.add(readObject(in));
                }
                return list;
            }
            case 5: {
                final int len = SnapshotSerializer.readVarInt(in);
                final Map<String, Object> map = new HashMap<>(len * 2);
                for (int i = 0; i < len; i++) {
                    final Object k = readObject(in);
                    final Object v = readObject(in);
                    if (k != null) {
                        map.put(k.toString(), v);
                    }
                }
                return map;
            }
            default: return null;
        }
    }

    @Nullable
    private static Number deserializeNumber(@NotNull final String cls, @NotNull final String val) {
        try {
            if (cls.equals(Integer.class.getName())) return Integer.valueOf(val);
            if (cls.equals(Double.class.getName())) return Double.valueOf(val);
            if (cls.equals(Long.class.getName())) return Long.valueOf(val);
            if (cls.equals(Float.class.getName())) return Float.valueOf(val);
            if (cls.equals(Short.class.getName())) return Short.valueOf(val);
            if (cls.equals(Byte.class.getName())) return Byte.valueOf(val);
        } catch (final Exception ignored) {}
        return null;
    }

    private static void writeNullableString(@NotNull final DataOutputStream out, @Nullable final String s) throws IOException {
        if (s == null) {
            SnapshotSerializer.writeVarInt(out, 0);
            return;
        }
        final byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SnapshotSerializer.writeVarInt(out, b.length + 1); // +1 so we can distinguish "" from null
        out.write(b);
    }

    private static @Nullable String readNullableString(@NotNull final DataInputStream in) throws IOException {
        final int len = SnapshotSerializer.readVarInt(in);
        if (len == 0) {
            return null;
        }
        final byte[] b = new byte[len - 1];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeStringArray(@NotNull final DataOutputStream out, String @NotNull [] arr) throws IOException {
        SnapshotSerializer.writeVarInt(out, arr.length);
        for (final String s : arr) {
            writeNullableString(out, s == null ? "" : s);
        }
    }

    private static String @NotNull [] readStringArray(@NotNull final DataInputStream in) throws IOException {
        final int len = SnapshotSerializer.readVarInt(in);
        final String[] arr = new String[len];
        for (int i = 0; i < len; i++) {
            arr[i] = readNullableString(in);
            if (arr[i] == null) {
                arr[i] = "";
            }
        }
        return arr;
    }

    // Suppress unused-import warnings for types we use reflectively in some branches.
    @SuppressWarnings("unused")
    private static final Class<?> GUARD1 = EntityBlockStorage.class;
    @SuppressWarnings("unused")
    private static final Class<?> GUARD2 = TileState.class;
}
