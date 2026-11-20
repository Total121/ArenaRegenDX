/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.snapshot;

import org.bukkit.Art;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Serializes the small set of entities typically used to decorate arenas:
 * armor stands, item frames and paintings.
 *
 * <p>Each entity type has a dedicated {@link EntityHandler}. The serialized
 * payload is a type-prefixed byte array that {@link RegionScanner} stores in
 * {@link Snapshot.EntityPayload}.</p>
 *
 * <p>Restoration creates a fresh entity at the captured location with the
 * captured state — this is safer than teleporting existing entities (which may
 * have been killed or moved by players between snapshot and restore).</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class EntitySerializer {

    /** Trailer marker for the facing of hanging entities (item frames, paintings). */
    private static final int FACING_MARKER = 0x46;

    private EntitySerializer() {
        // static utility
    }

    /**
     * Serialize an entity to a {@link Snapshot.EntityPayload}.
     *
     * @param entity the entity (must be armor stand / item frame / painting).
     * @return the payload, or {@code null} if the type is unsupported.
     */
    public static @Nullable Snapshot.EntityPayload capture(@NotNull final Entity entity) {
        final EntityHandler handler = handlerFor(entity);
        if (handler == null) {
            return null;
        }
        try {
            final ByteArrayOutputStream bout = new ByteArrayOutputStream(128);
            final DataOutputStream out = new DataOutputStream(bout);
            handler.write(out, entity);
            if (entity instanceof org.bukkit.entity.Hanging hanging) {
                // Appended trailer (older readers stop before it): which wall the frame/painting hangs on.
                out.writeByte(FACING_MARKER);
                writeNullableString(out, hanging.getFacing().name());
            }
            out.flush();
            final Location loc = entity.getLocation();
            return new Snapshot.EntityPayload(
                    entity.getType().name(),
                    loc.getX(), loc.getY(), loc.getZ(),
                    loc.getYaw(), loc.getPitch(),
                    bout.toByteArray()
            );
        } catch (final IOException e) {
            return null;
        }
    }

    /**
     * Restore an entity at its captured location in the given world.
     *
     * @param payload the captured payload.
     * @param world   the world to restore into.
     * @return the restored entity, or {@code null} on failure.
     */
    public static @Nullable Entity restore(@NotNull final Snapshot.EntityPayload payload,
                                           @NotNull final org.bukkit.World world) {
        final org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(payload.type());
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final Location loc = new Location(world, payload.x(), payload.y(), payload.z(),
                payload.yaw(), payload.pitch());
        final Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null) {
            return null;
        }
        try {
            // Apply the payload before the entity joins the world (no flicker, correct wall for frames).
            return world.spawn(loc, entityClass, entity -> apply(entity, payload.payload()));
        } catch (final Throwable t) {
            return null;
        }
    }

    private static void apply(@NotNull final Entity entity, final byte @NotNull [] data) {
        final EntityHandler handler = handlerFor(entity);
        if (handler == null) {
            return;
        }
        try {
            final DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
            handler.read(in, entity);
            if (entity instanceof org.bukkit.entity.Hanging hanging
                    && in.available() > 0 && in.readUnsignedByte() == FACING_MARKER) {
                final String face = readNullableString(in);
                if (face != null && !face.isEmpty()) {
                    hanging.setFacingDirection(org.bukkit.block.BlockFace.valueOf(face), true);
                }
            }
        } catch (final IOException | IllegalArgumentException ignored) {
            // Truncated / unknown values: keep the defaults chosen by the server.
        }
    }

    // -----------------------------------------------------------------------
    // Handler dispatch
    // -----------------------------------------------------------------------

    private interface EntityHandler {
        void write(@NotNull DataOutputStream out, @NotNull Entity entity) throws IOException;
        void read(@NotNull DataInputStream in, @NotNull Entity entity) throws IOException;
    }

    private static @Nullable EntityHandler handlerFor(@NotNull final Entity e) {
        if (e instanceof ArmorStand) return ARMOR_STAND;
        if (e instanceof ItemFrame) return ITEM_FRAME;
        if (e instanceof Painting) return PAINTING;
        if (e instanceof ItemDisplay) return ITEM_DISPLAY;
        if (e instanceof BlockDisplay) return BLOCK_DISPLAY;
        if (e instanceof TextDisplay) return TEXT_DISPLAY;
        return null;
    }

    // -----------------------------------------------------------------------
    // Armor stand: equipment, pose, properties
    // -----------------------------------------------------------------------

    private static final EntityHandler ARMOR_STAND = new EntityHandler() {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final Entity e) throws IOException {
            final ArmorStand a = (ArmorStand) e;
            // Properties.
            out.writeBoolean(a.isVisible());
            out.writeBoolean(a.hasBasePlate());
            out.writeBoolean(a.isSmall());
            out.writeBoolean(a.hasArms());
            out.writeBoolean(a.isMarker());
            out.writeBoolean(a.hasGravity());
            out.writeBoolean(a.isCustomNameVisible());
            out.writeBoolean(a.isGlowing());
            // Use modern Component-based custom name API.
            final var nameComp = a.customName();
            writeNullableString(out, nameComp == null ? "" : com.dravorax.arenaregendx.util.ColorUtil.legacy(nameComp));
            // Equipment.
            writeItem(out, a.getItem(EquipmentSlot.HEAD));
            writeItem(out, a.getItem(EquipmentSlot.CHEST));
            writeItem(out, a.getItem(EquipmentSlot.LEGS));
            writeItem(out, a.getItem(EquipmentSlot.FEET));
            writeItem(out, a.getItem(EquipmentSlot.HAND));
            writeItem(out, a.getItem(EquipmentSlot.OFF_HAND));
            // Pose (Euler angles per body part).
            writeAngle(out, a.getHeadPose());
            writeAngle(out, a.getBodyPose());
            writeAngle(out, a.getLeftArmPose());
            writeAngle(out, a.getRightArmPose());
            writeAngle(out, a.getLeftLegPose());
            writeAngle(out, a.getRightLegPose());
            out.writeByte(3);
            writeItemBytes(out, a.getItem(EquipmentSlot.HEAD));
            writeItemBytes(out, a.getItem(EquipmentSlot.CHEST));
            writeItemBytes(out, a.getItem(EquipmentSlot.LEGS));
            writeItemBytes(out, a.getItem(EquipmentSlot.FEET));
            writeItemBytes(out, a.getItem(EquipmentSlot.HAND));
            writeItemBytes(out, a.getItem(EquipmentSlot.OFF_HAND));
        }

        @Override public void read(@NotNull final DataInputStream in, @NotNull final Entity e) throws IOException {
            final ArmorStand a = (ArmorStand) e;
            a.setVisible(in.readBoolean());
            a.setBasePlate(in.readBoolean());
            a.setSmall(in.readBoolean());
            a.setArms(in.readBoolean());
            a.setMarker(in.readBoolean());
            a.setGravity(in.readBoolean());
            a.setCustomNameVisible(in.readBoolean());
            a.setGlowing(in.readBoolean());
            final String name = readNullableString(in);
            if (name != null && !name.isEmpty()) {
                a.customName(com.dravorax.arenaregendx.util.ColorUtil.mini(name));
            } else {
                a.customName(null);
            }
            a.getEquipment().setItem(EquipmentSlot.HEAD, readItem(in));
            a.getEquipment().setItem(EquipmentSlot.CHEST, readItem(in));
            a.getEquipment().setItem(EquipmentSlot.LEGS, readItem(in));
            a.getEquipment().setItem(EquipmentSlot.FEET, readItem(in));
            a.getEquipment().setItem(EquipmentSlot.HAND, readItem(in));
            a.getEquipment().setItem(EquipmentSlot.OFF_HAND, readItem(in));
            a.setHeadPose(readAngle(in));
            a.setBodyPose(readAngle(in));
            a.setLeftArmPose(readAngle(in));
            a.setRightArmPose(readAngle(in));
            a.setLeftLegPose(readAngle(in));
            a.setRightLegPose(readAngle(in));
            if (in.available() > 0 && in.readUnsignedByte() == 3) {
                a.getEquipment().setItem(EquipmentSlot.HEAD, readItemBytes(in));
                a.getEquipment().setItem(EquipmentSlot.CHEST, readItemBytes(in));
                a.getEquipment().setItem(EquipmentSlot.LEGS, readItemBytes(in));
                a.getEquipment().setItem(EquipmentSlot.FEET, readItemBytes(in));
                a.getEquipment().setItem(EquipmentSlot.HAND, readItemBytes(in));
                a.getEquipment().setItem(EquipmentSlot.OFF_HAND, readItemBytes(in));
            }
        }
    };

    // -----------------------------------------------------------------------
    // Item frame: item + rotation + item rotation
    // -----------------------------------------------------------------------

    private static final EntityHandler ITEM_FRAME = new EntityHandler() {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final Entity e) throws IOException {
            final ItemFrame f = (ItemFrame) e;
            writeItem(out, f.getItem());
            final String rot = f.getRotation() == null ? "" : f.getRotation().name();
            writeNullableString(out, rot);
            out.writeBoolean(f.isVisible());
            out.writeBoolean(f.isGlowing());
            out.writeBoolean(f.isFixed());
            out.writeByte(3);
            writeItemBytes(out, f.getItem());
        }

        @Override public void read(@NotNull final DataInputStream in, @NotNull final Entity e) throws IOException {
            final ItemFrame f = (ItemFrame) e;
            f.setItem(readItem(in));
            final String rotName = readNullableString(in);
            if (rotName != null && !rotName.isEmpty()) {
                try { f.setRotation(org.bukkit.Rotation.valueOf(rotName)); } catch (final Exception ignored) {}
            }
            f.setVisible(in.readBoolean());
            if (in.available() >= 2) {
                f.setGlowing(in.readBoolean());
                f.setFixed(in.readBoolean());
            }
            if (in.available() > 0 && in.readUnsignedByte() == 3) {
                final ItemStack item = readItemBytes(in);
                if (item != null) f.setItem(item);
            }
        }
    };

    // -----------------------------------------------------------------------
    // Painting: art
    // -----------------------------------------------------------------------

    private static final EntityHandler PAINTING = new EntityHandler() {
        @SuppressWarnings("deprecation")
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final Entity e) throws IOException {
            final Painting p = (Painting) e;
            final Art art = p.getArt();
            writeNullableString(out, art == null ? "" : Registry.ART.getKey(art).toString());
        }

        @SuppressWarnings("deprecation")
        @Override public void read(@NotNull final DataInputStream in, @NotNull final Entity e) throws IOException {
            final Painting p = (Painting) e;
            final String artName = readNullableString(in);
            if (artName != null && !artName.isEmpty()) {
                try {
                    final String lower = artName.toLowerCase(java.util.Locale.ROOT);
                    final NamespacedKey key = lower.contains(":")
                            ? NamespacedKey.fromString(lower)
                            : NamespacedKey.minecraft(lower);
                    final Art art = key == null ? null : Registry.ART.get(key);
                    if (art != null) p.setArt(art, true);
                } catch (final Exception ignored) {}
            }
        }
    };

    // -----------------------------------------------------------------------
    // Display entities (1.19.4+): item / block / text
    // -----------------------------------------------------------------------

    private static void writeDisplay(@NotNull final DataOutputStream out, @NotNull final Display d) throws IOException {
        writeNullableString(out, d.getBillboard() == null ? "" : d.getBillboard().name());
        out.writeBoolean(d.isGlowing());
        out.writeFloat(d.getViewRange());
        out.writeFloat(d.getShadowRadius());
        out.writeFloat(d.getShadowStrength());
        out.writeFloat(d.getDisplayWidth());
        out.writeFloat(d.getDisplayHeight());
        final Display.Brightness b = d.getBrightness();
        out.writeBoolean(b != null);
        if (b != null) {
            out.writeInt(b.getBlockLight());
            out.writeInt(b.getSkyLight());
        }
        final var t = d.getTransformation();
        writeVec3(out, t.getTranslation());
        writeVec3(out, t.getScale());
        writeQuat(out, t.getLeftRotation());
        writeQuat(out, t.getRightRotation());
    }

    private static void readDisplay(@NotNull final DataInputStream in, @NotNull final Display d) throws IOException {
        final String billboard = readNullableString(in);
        if (billboard != null && !billboard.isEmpty()) {
            try { d.setBillboard(Display.Billboard.valueOf(billboard)); } catch (final Exception ignored) {}
        }
        d.setGlowing(in.readBoolean());
        d.setViewRange(in.readFloat());
        d.setShadowRadius(in.readFloat());
        d.setShadowStrength(in.readFloat());
        d.setDisplayWidth(in.readFloat());
        d.setDisplayHeight(in.readFloat());
        if (in.readBoolean()) {
            d.setBrightness(new Display.Brightness(in.readInt(), in.readInt()));
        }
        final var translation = readVec3(in);
        final var scale = readVec3(in);
        final var left = readQuat(in);
        final var right = readQuat(in);
        d.setTransformation(new org.bukkit.util.Transformation(translation, left, scale, right));
    }

    private static final EntityHandler ITEM_DISPLAY = new EntityHandler() {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final Entity e) throws IOException {
            final ItemDisplay d = (ItemDisplay) e;
            writeDisplay(out, d);
            writeNullableString(out, d.getItemDisplayTransform() == null ? "" : d.getItemDisplayTransform().name());
            writeItemBytes(out, d.getItemStack());
        }

        @Override public void read(@NotNull final DataInputStream in, @NotNull final Entity e) throws IOException {
            final ItemDisplay d = (ItemDisplay) e;
            readDisplay(in, d);
            final String transform = readNullableString(in);
            if (transform != null && !transform.isEmpty()) {
                try { d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.valueOf(transform)); }
                catch (final Exception ignored) {}
            }
            d.setItemStack(readItemBytes(in));
        }
    };

    private static final EntityHandler BLOCK_DISPLAY = new EntityHandler() {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final Entity e) throws IOException {
            final BlockDisplay d = (BlockDisplay) e;
            writeDisplay(out, d);
            writeNullableString(out, d.getBlock() == null ? "" : d.getBlock().getAsString(false));
        }

        @Override public void read(@NotNull final DataInputStream in, @NotNull final Entity e) throws IOException {
            final BlockDisplay d = (BlockDisplay) e;
            readDisplay(in, d);
            final String data = readNullableString(in);
            if (data != null && !data.isEmpty()) {
                try { d.setBlock(Bukkit.createBlockData(data)); } catch (final Exception ignored) {}
            }
        }
    };

    private static final EntityHandler TEXT_DISPLAY = new EntityHandler() {
        @Override public void write(@NotNull final DataOutputStream out, @NotNull final Entity e) throws IOException {
            final TextDisplay d = (TextDisplay) e;
            writeDisplay(out, d);
            final var text = d.text();
            writeNullableString(out, text == null ? "" : com.dravorax.arenaregendx.util.ColorUtil.legacy(text));
            writeNullableString(out, d.getAlignment() == null ? "" : d.getAlignment().name());
            out.writeInt(d.getLineWidth());
            out.writeBoolean(d.isSeeThrough());
            out.writeBoolean(d.isShadowed());
            final org.bukkit.Color bg = d.getBackgroundColor();
            out.writeBoolean(bg != null);
            if (bg != null) out.writeInt(bg.asARGB());
        }

        @Override public void read(@NotNull final DataInputStream in, @NotNull final Entity e) throws IOException {
            final TextDisplay d = (TextDisplay) e;
            readDisplay(in, d);
            final String text = readNullableString(in);
            if (text != null && !text.isEmpty()) {
                d.text(com.dravorax.arenaregendx.util.ColorUtil.mini(text));
            }
            final String align = readNullableString(in);
            if (align != null && !align.isEmpty()) {
                try { d.setAlignment(TextDisplay.TextAlignment.valueOf(align)); } catch (final Exception ignored) {}
            }
            d.setLineWidth(in.readInt());
            d.setSeeThrough(in.readBoolean());
            d.setShadowed(in.readBoolean());
            if (in.readBoolean()) {
                d.setBackgroundColor(org.bukkit.Color.fromARGB(in.readInt()));
            }
        }
    };

    private static void writeVec3(@NotNull final DataOutputStream out, @NotNull final org.joml.Vector3f v) throws IOException {
        out.writeFloat(v.x());
        out.writeFloat(v.y());
        out.writeFloat(v.z());
    }

    private static org.joml.Vector3f readVec3(@NotNull final DataInputStream in) throws IOException {
        return new org.joml.Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
    }

    private static void writeQuat(@NotNull final DataOutputStream out, @NotNull final org.joml.Quaternionf q) throws IOException {
        out.writeFloat(q.x());
        out.writeFloat(q.y());
        out.writeFloat(q.z());
        out.writeFloat(q.w());
    }

    private static org.joml.Quaternionf readQuat(@NotNull final DataInputStream in) throws IOException {
        return new org.joml.Quaternionf(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
    }

    // -----------------------------------------------------------------------
    // ItemStack + Euler-angle helpers (compact variants)
    // -----------------------------------------------------------------------

    private static void writeItem(@NotNull final DataOutputStream out, @Nullable final ItemStack item) throws IOException {
        if (item == null || item.getType() == Material.AIR) {
            out.writeBoolean(false);
            return;
        }
        out.writeBoolean(true);
        writeNullableString(out, item.getType().name());
        out.writeInt(item.getAmount());
    }

    @Nullable
    private static ItemStack readItem(@NotNull final DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        final String typeName = readNullableString(in);
        final int amount = in.readInt();
        if (typeName == null) return null;
        try {
            final Material m = Material.valueOf(typeName);
            return new ItemStack(m, Math.max(1, amount));
        } catch (final Exception e) {
            return null;
        }
    }

    private static void writeItemBytes(@NotNull final DataOutputStream out, @Nullable final ItemStack item) throws IOException {
        if (item == null || item.getType() == Material.AIR) {
            out.writeBoolean(false);
            return;
        }
        try {
            final byte[] bytes = item.serializeAsBytes();
            out.writeBoolean(true);
            SnapshotSerializer.writeVarInt(out, bytes.length);
            out.write(bytes);
        } catch (final Throwable t) {
            out.writeBoolean(false);
        }
    }

    @Nullable
    private static ItemStack readItemBytes(@NotNull final DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        final int len = SnapshotSerializer.readVarInt(in);
        final byte[] bytes = new byte[Math.max(0, len)];
        in.readFully(bytes);
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static void writeAngle(@NotNull final DataOutputStream out, @NotNull final org.bukkit.util.EulerAngle a) throws IOException {
        out.writeDouble(a.getX());
        out.writeDouble(a.getY());
        out.writeDouble(a.getZ());
    }

    @NotNull
    private static org.bukkit.util.EulerAngle readAngle(@NotNull final DataInputStream in) throws IOException {
        return new org.bukkit.util.EulerAngle(in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static void writeNullableString(@NotNull final DataOutputStream out, @Nullable final String s) throws IOException {
        if (s == null) {
            SnapshotSerializer.writeVarInt(out, 0);
            return;
        }
        final byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SnapshotSerializer.writeVarInt(out, b.length + 1);
        out.write(b);
    }

    private static @Nullable String readNullableString(@NotNull final DataInputStream in) throws IOException {
        final int len = SnapshotSerializer.readVarInt(in);
        if (len == 0) return null;
        final byte[] b = new byte[len - 1];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    // Suppress unused-import warnings for types referenced only via reflection guards.
    @SuppressWarnings("unused")
    private static final Class<?> GUARD1 = Bukkit.class;
    @SuppressWarnings("unused")
    private static final Class<?> GUARD2 = Map.class;
}
