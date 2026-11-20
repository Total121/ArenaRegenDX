/*
 * ArenaRegenDX - Premium Arena Regeneration Plugin
 * Copyright (C) 2024 DravoraX
 */

package com.dravorax.arenaregendx.snapshot;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Binary (de)serialiser for {@link Snapshot}.
 *
 * <h2>On-disk format (version 1)</h2>
 * <pre>
 *   Header (16 bytes):
 *     magic       : 4 bytes  "ARDX" (legacy "SARP" accepted on read)
 *     version     : 1 byte   = 1
 *     flags       : 1 byte   bit0: compressed
 *     compression : 1 byte   0=none, 1=gzip, 2=zlib
 *     reserved    : 1 byte   = 0
 *     createdAt   : 8 bytes  epoch millis
 *
 *   Dimensions (12 bytes):
 *     sizeX, sizeY, sizeZ : 3 × int32
 *
 *   Compressed payload (the rest):
 *     uniqueBlockDataCount : varint
 *     for each unique:
 *       stringLen : varint
 *       string    : UTF-8 bytes
 *
 *     blockRunCount : varint         (RLE-encoded block array)
 *     for each run:
 *       dataId  : varint
 *       runLen  : varint
 *
 *     tileEntityCount : varint
 *     for each:
 *       flatIndex : varint
 *       payloadLen: varint
 *       payload   : bytes
 *
 *     entityCount : varint
 *     for each:
 *       typeLen   : varint
 *       type      : UTF-8
 *       x,y,z     : 3 × double
 *       yaw,pitch : 2 × float
 *       payloadLen: varint
 *       payload   : bytes
 * </pre>
 *
 * <p>All multi-byte integers are big-endian. Varints use the standard LEB128
 * encoding (same scheme as Protocol Buffer varints, for compact storage of small
 * values while still supporting {@code int32} range).</p>
 *
 * <p>Block runs use RLE because most arenas are dominated by long runs of air,
 * stone, dirt, etc. — RLE typically reduces a 1M-block arena to a few thousand
 * runs, which then GZIP/ZLIB-compress to a few hundred KB.</p>
 *
 * @author DravoraX
 * @since 1.0.0
 */
public final class SnapshotSerializer {

    /** Magic bytes ("ARDX"). */
    public static final int MAGIC = 0x41524458; // 'A','R','D','X'
    /** Legacy SwordArenaRegen+ magic, still accepted on read. */
    public static final int LEGACY_MAGIC = 0x53415250; // 'S','A','R','P'

    /** Current format version. Bump on incompatible changes. */
    public static final int VERSION = 1;

    /** Flag bit: payload is compressed. */
    public static final int FLAG_COMPRESSED = 0x01;

    public static final int COMPRESSION_NONE = 0;
    public static final int COMPRESSION_GZIP = 1;
    public static final int COMPRESSION_ZLIB = 2;

    /** Largest volume a snapshot may describe (matches the capture limit). */
    public static final long MAX_VOLUME = 100_000_000L;
    private static final int MAX_PALETTE = 1 << 20;
    private static final int MAX_STRING = 1 << 16;
    private static final int MAX_PAYLOAD = 16 << 20;
    private static final int MAX_ENTITIES = 1 << 20;

    private SnapshotSerializer() {
        // static utility
    }

    // -----------------------------------------------------------------------
    // Serialization
    // -----------------------------------------------------------------------

    /**
     * Serialize a snapshot to a byte array with the given compression.
     *
     * @param snapshot       the snapshot.
     * @param compression    one of {@link #COMPRESSION_NONE}, {@link #COMPRESSION_GZIP}, {@link #COMPRESSION_ZLIB}.
     * @param compressionLevel Deflater level (0-9, or -1 for default). Ignored for NONE.
     * @return the bytes.
     * @throws IOException if serialization fails.
     */
    public static byte @NotNull [] serialize(@NotNull final Snapshot snapshot,
                                             final int compression,
                                             final int compressionLevel) throws IOException {
        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream(8192);
        // Write header directly (uncompressed).
        final DataOutputStream header = new DataOutputStream(byteOut);
        header.writeInt(MAGIC);
        header.writeByte(VERSION);
        header.writeByte(compression == COMPRESSION_NONE ? 0 : FLAG_COMPRESSED);
        header.writeByte(compression);
        header.writeByte(0); // reserved
        header.writeLong(snapshot.createdAt());
        header.writeInt(snapshot.sizeX());
        header.writeInt(snapshot.sizeY());
        header.writeInt(snapshot.sizeZ());
        header.flush();

        // Body: write to a compressed stream wrapping the same byteOut.
        final java.io.OutputStream body;
        final Deflater deflater;
        if (compression == COMPRESSION_GZIP) {
            final int level = compressionLevel < 0 ? Deflater.DEFAULT_COMPRESSION : compressionLevel;
            body = new GZIPOutputStream(byteOut, 8192) {
                {
                    def.setLevel(level);
                }
            };
            deflater = null;
        } else if (compression == COMPRESSION_ZLIB) {
            deflater = new Deflater(compressionLevel < 0 ? Deflater.DEFAULT_COMPRESSION : compressionLevel);
            body = new DeflaterOutputStream(byteOut, deflater, 8192);
        } else {
            deflater = null;
            body = byteOut;
        }
        final DataOutputStream out = new DataOutputStream(body);
        writeBody(out, snapshot);
        out.flush();
        body.close();
        if (deflater != null) {
            deflater.end();
        }
        return byteOut.toByteArray();
    }

    private static void writeBody(@NotNull final DataOutputStream out,
                                  @NotNull final Snapshot s) throws IOException {
        // 1. Unique block data strings.
        final String[] uniq = s.uniqueBlockData();
        writeVarInt(out, uniq.length);
        for (final String str : uniq) {
            final byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeVarInt(out, bytes.length);
            out.write(bytes);
        }

        // 2. RLE-encoded block array.
        final PaletteIds ids = s.ids();
        final int n = ids.length();
        int i = 0;
        // First pass counts runs so the count can prefix them (format compatibility).
        int runCount = 0;
        while (i < n) {
            final int cur = ids.get(i);
            int len = 1;
            while (i + len < n && ids.get(i + len) == cur && len < 0x3FFFFFFF) {
                len++;
            }
            runCount++;
            i += len;
        }
        writeVarInt(out, runCount);
        i = 0;
        while (i < n) {
            final int cur = ids.get(i);
            int len = 1;
            while (i + len < n && ids.get(i + len) == cur && len < 0x3FFFFFFF) {
                len++;
            }
            writeVarInt(out, cur);
            writeVarInt(out, len);
            i += len;
        }

        // 3. Tile entities.
        final Int2ObjectMap<byte[]> tiles = s.tileEntities();
        writeVarInt(out, tiles.size());
        for (final Int2ObjectMap.Entry<byte[]> e : tiles.int2ObjectEntrySet()) {
            writeVarInt(out, e.getIntKey());
            final byte[] v = e.getValue();
            writeVarInt(out, v.length);
            out.write(v);
        }

        // 4. Entities.
        final Snapshot.EntityPayload[] entities = s.entities();
        writeVarInt(out, entities.length);
        for (final Snapshot.EntityPayload ent : entities) {
            final byte[] tb = ent.type().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeVarInt(out, tb.length);
            out.write(tb);
            out.writeDouble(ent.x());
            out.writeDouble(ent.y());
            out.writeDouble(ent.z());
            out.writeFloat(ent.yaw());
            out.writeFloat(ent.pitch());
            writeVarInt(out, ent.payload().length);
            out.write(ent.payload());
        }
    }

    // -----------------------------------------------------------------------
    // Deserialization
    // -----------------------------------------------------------------------

    /**
     * Deserialize a snapshot from bytes (must match the format written by {@link #serialize}).
     *
     * @param bytes the bytes.
     * @return the snapshot.
     * @throws IOException if the format is invalid.
     */
    public static @NotNull Snapshot deserialize(final byte @NotNull [] bytes) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        final int magic = in.readInt();
        if (magic != MAGIC && magic != LEGACY_MAGIC) {
            throw new IOException("Invalid snapshot file: bad magic 0x" + Integer.toHexString(magic));
        }
        final int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("Unsupported snapshot version: " + version);
        }
        final int flags = in.readUnsignedByte();
        final int compression = in.readUnsignedByte();
        //noinspection ResultOfMethodCallIgnored
        in.readUnsignedByte(); // reserved
        final long createdAt = in.readLong();
        final int sizeX = in.readInt();
        final int sizeY = in.readInt();
        final int sizeZ = in.readInt();
        validateDimensions(sizeX, sizeY, sizeZ);

        // Wrap the remaining stream with the appropriate decompression.
        final java.io.InputStream body;
        final Inflater inflater;
        if ((flags & FLAG_COMPRESSED) == 0) {
            inflater = null;
            body = in;
        } else if (compression == COMPRESSION_GZIP) {
            inflater = null;
            body = new GZIPInputStream(in);
        } else if (compression == COMPRESSION_ZLIB) {
            inflater = new Inflater();
            body = new InflaterInputStream(in, inflater, 8192);
        } else {
            throw new IOException("Unknown compression type: " + compression);
        }
        final DataInputStream bodyIn = new DataInputStream(body);
        try {
            return readBody(bodyIn, sizeX, sizeY, sizeZ, createdAt);
        } finally {
            try { bodyIn.close(); } catch (final IOException ignored) {}
            if (inflater != null) {
                inflater.end();
            }
        }
    }

    private static @NotNull Snapshot readBody(@NotNull final DataInputStream in,
                                              final int sizeX, final int sizeY, final int sizeZ,
                                              final long createdAt) throws IOException {
        // 1. Unique block data strings.
        final int uniqCount = readVarInt(in);
        if (uniqCount <= 0 || uniqCount > MAX_PALETTE) {
            throw new IOException("Corrupt snapshot: palette size " + uniqCount);
        }
        final String[] uniq = new String[uniqCount];
        for (int i = 0; i < uniqCount; i++) {
            final int len = readVarInt(in);
            if (len < 0 || len > MAX_STRING) {
                throw new IOException("Corrupt snapshot: palette entry length " + len);
            }
            final byte[] b = new byte[len];
            in.readFully(b);
            uniq[i] = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }

        // 2. RLE-decoded block array.
        final int totalBlocks = sizeX * sizeY * sizeZ;
        final PaletteIds ids = PaletteIds.allocate(totalBlocks, uniqCount);
        final int runCount = readVarInt(in);
        int pos = 0;
        for (int r = 0; r < runCount; r++) {
            final int dataId = readVarInt(in);
            final int len = readVarInt(in);
            if (dataId < 0 || dataId >= uniqCount || len <= 0 || len > totalBlocks - pos) {
                throw new IOException("Corrupt snapshot: bad block run #" + r
                        + " (id=" + dataId + ", len=" + len + ", pos=" + pos + ")");
            }
            ids.fill(pos, pos + len, dataId);
            pos += len;
        }
        if (pos != totalBlocks) {
            throw new IOException("Block run length mismatch: expected " + totalBlocks + ", got " + pos);
        }

        // 3. Tile entities.
        final int tileCount = readVarInt(in);
        if (tileCount < 0 || tileCount > totalBlocks) {
            throw new IOException("Corrupt snapshot: tile count " + tileCount);
        }
        final Int2ObjectOpenHashMap<byte[]> tiles = new Int2ObjectOpenHashMap<>(tileCount);
        for (int i = 0; i < tileCount; i++) {
            final int flatIndex = readVarInt(in);
            final int payloadLen = readVarInt(in);
            if (flatIndex < 0 || flatIndex >= totalBlocks || payloadLen < 0 || payloadLen > MAX_PAYLOAD) {
                throw new IOException("Corrupt snapshot: tile entry " + flatIndex + " / " + payloadLen);
            }
            final byte[] payload = new byte[payloadLen];
            in.readFully(payload);
            tiles.put(flatIndex, payload);
        }

        // 4. Entities.
        final int entityCount = readVarInt(in);
        if (entityCount < 0 || entityCount > MAX_ENTITIES) {
            throw new IOException("Corrupt snapshot: entity count " + entityCount);
        }
        final Snapshot.EntityPayload[] entities = new Snapshot.EntityPayload[entityCount];
        for (int i = 0; i < entityCount; i++) {
            final int typeLen = readVarInt(in);
            if (typeLen < 0 || typeLen > MAX_STRING) {
                throw new IOException("Corrupt snapshot: entity type length " + typeLen);
            }
            final byte[] tb = new byte[typeLen];
            in.readFully(tb);
            final String type = new String(tb, java.nio.charset.StandardCharsets.UTF_8);
            final double x = in.readDouble();
            final double y = in.readDouble();
            final double z = in.readDouble();
            final float yaw = in.readFloat();
            final float pitch = in.readFloat();
            final int payloadLen = readVarInt(in);
            if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) {
                throw new IOException("Corrupt snapshot: entity payload length " + payloadLen);
            }
            final byte[] payload = new byte[payloadLen];
            in.readFully(payload);
            entities[i] = new Snapshot.EntityPayload(type, x, y, z, yaw, pitch, payload);
        }

        return new Snapshot(sizeX, sizeY, sizeZ, createdAt, uniq, ids, tiles, entities);
    }

    // -----------------------------------------------------------------------
    // Varint helpers (LEB128, same as Protocol Buffers).
    // -----------------------------------------------------------------------

    public static void writeVarInt(@NotNull final DataOutputStream out, int v) throws IOException {
        // Note: this is the zig-zag-agnostic LEB128 for unsigned int32. Negative
        // values are encoded as 5 bytes (we never store negatives).
        while (true) {
            if ((v & ~0x7F) == 0) {
                out.writeByte(v);
                return;
            }
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    public static int readVarInt(@NotNull final DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0 && shift < 35);
        return result;
    }

    /** Read only the header from a byte array (without decompressing the body). */
    public static @NotNull HeaderInfo readHeader(final byte @NotNull [] bytes) throws IOException {
        return readHeader(new ByteArrayInputStream(bytes));
    }

    /** Read only the 28-byte header from a file, without loading the body. */
    public static @NotNull HeaderInfo readHeader(@NotNull final java.nio.file.Path file) throws IOException {
        try (java.io.InputStream raw = java.nio.file.Files.newInputStream(file)) {
            return readHeader(new java.io.BufferedInputStream(raw, 64));
        }
    }

    private static @NotNull HeaderInfo readHeader(@NotNull final java.io.InputStream stream) throws IOException {
        final DataInputStream in = new DataInputStream(stream);
        final int magic = in.readInt();
        if (magic != MAGIC && magic != LEGACY_MAGIC) {
            throw new IOException("Invalid snapshot file: bad magic");
        }
        final int version = in.readUnsignedByte();
        final int flags = in.readUnsignedByte();
        final int compression = in.readUnsignedByte();
        in.readUnsignedByte(); // reserved
        final long createdAt = in.readLong();
        final int sizeX = in.readInt();
        final int sizeY = in.readInt();
        final int sizeZ = in.readInt();
        return new HeaderInfo(version, flags, compression, createdAt, sizeX, sizeY, sizeZ);
    }

    /** Reject absurd dimensions before allocating anything (corrupt / hostile files). */
    private static void validateDimensions(final int sizeX, final int sizeY, final int sizeZ) throws IOException {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                || (long) sizeX * sizeY * sizeZ > MAX_VOLUME) {
            throw new IOException("Corrupt snapshot: dimensions " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
    }

    /** Parsed header fields. */
    public record HeaderInfo(int version, int flags, int compression, long createdAt,
                             int sizeX, int sizeY, int sizeZ) {
        public boolean compressed() {
            return (flags & FLAG_COMPRESSED) != 0;
        }
    }
}
