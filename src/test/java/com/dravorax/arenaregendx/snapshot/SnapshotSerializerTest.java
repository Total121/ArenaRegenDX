package com.dravorax.arenaregendx.snapshot;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotSerializerTest {

    private static Snapshot sample(final int paletteSize) {
        final int sx = 17, sy = 9, sz = 13;
        final int[] ids = new int[sx * sy * sz];
        final Random random = new Random(42);
        for (int i = 0; i < ids.length; i++) {
            // Long runs plus noise, like a real arena.
            ids[i] = (i / 40) % 3 == 0 ? 0 : random.nextInt(paletteSize);
        }
        final String[] palette = new String[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = "minecraft:block_" + i;
        }
        final Int2ObjectOpenHashMap<byte[]> tiles = new Int2ObjectOpenHashMap<>();
        tiles.put(5, new byte[]{1, 2, 3});
        tiles.put(ids.length - 1, new byte[]{'N', 'B', 'T', 1, 10, 0, 0});
        final Snapshot.EntityPayload[] entities = {
                new Snapshot.EntityPayload("ARMOR_STAND", 1.5, 2.0, 3.25, 90f, -10f, new byte[]{9, 8, 7})
        };
        return new Snapshot(sx, sy, sz, 1_700_000_000_000L, palette, ids, tiles, entities);
    }

    @ParameterizedTest
    @ValueSource(ints = {SnapshotSerializer.COMPRESSION_NONE, SnapshotSerializer.COMPRESSION_GZIP,
            SnapshotSerializer.COMPRESSION_ZLIB})
    void roundTripPreservesEverything(final int compression) throws IOException {
        for (final int paletteSize : new int[]{3, 300, 70_000}) {
            final Snapshot original = sample(paletteSize);
            final Snapshot copy = SnapshotSerializer.deserialize(SnapshotSerializer.serialize(original, compression, 6));
            assertEquals(original.sizeX(), copy.sizeX());
            assertEquals(original.sizeY(), copy.sizeY());
            assertEquals(original.sizeZ(), copy.sizeZ());
            assertEquals(original.createdAt(), copy.createdAt());
            assertArrayEquals(original.uniqueBlockData(), copy.uniqueBlockData());
            for (int i = 0; i < original.blockCount(); i++) {
                assertEquals(original.paletteId(i), copy.paletteId(i), "voxel " + i);
            }
            assertEquals(original.tileEntities().size(), copy.tileEntities().size());
            assertArrayEquals(original.tile(5), copy.tile(5));
            assertEquals(1, copy.entities().length);
            assertEquals("ARMOR_STAND", copy.entities()[0].type());
            assertEquals(3.25, copy.entities()[0].z());
            assertArrayEquals(new byte[]{9, 8, 7}, copy.entities()[0].payload());
        }
    }

    @Test
    void headerIsReadableWithoutBody() throws IOException {
        final byte[] bytes = SnapshotSerializer.serialize(sample(4), SnapshotSerializer.COMPRESSION_ZLIB, 6);
        final SnapshotSerializer.HeaderInfo header = SnapshotSerializer.readHeader(Arrays.copyOf(bytes, 28));
        assertEquals(17, header.sizeX());
        assertEquals(9, header.sizeY());
        assertEquals(13, header.sizeZ());
        assertTrue(header.compressed());
    }

    @Test
    void rejectsBadMagic() {
        assertThrows(IOException.class, () -> SnapshotSerializer.deserialize(new byte[64]));
    }

    @Test
    void rejectsAbsurdDimensionsBeforeAllocating() throws IOException {
        final byte[] bytes = SnapshotSerializer.serialize(sample(4), SnapshotSerializer.COMPRESSION_NONE, 0);
        // sizeX lives at offset 16; claim a 2^30-wide snapshot.
        bytes[16] = 0x40;
        assertThrows(IOException.class, () -> SnapshotSerializer.deserialize(bytes));
    }

    @Test
    void rejectsTruncatedBody() throws IOException {
        final byte[] bytes = SnapshotSerializer.serialize(sample(4), SnapshotSerializer.COMPRESSION_NONE, 0);
        assertThrows(IOException.class, () -> SnapshotSerializer.deserialize(Arrays.copyOf(bytes, bytes.length / 2)));
    }

    @Test
    void rejectsRunPastTheEnd() throws IOException {
        final Snapshot tiny = new Snapshot(2, 1, 1, 0L, new String[]{"minecraft:air"}, new int[]{0, 0},
                new Int2ObjectOpenHashMap<>(), new Snapshot.EntityPayload[0]);
        final byte[] bytes = SnapshotSerializer.serialize(tiny, SnapshotSerializer.COMPRESSION_NONE, 0);
        // Body: palette count(1) len(13) "minecraft:air" runCount(1) id(0) len(2) -> bump len to 3.
        final int lenOffset = 28 + 1 + 1 + 13 + 1 + 1;
        assertEquals(2, bytes[lenOffset]);
        bytes[lenOffset] = 3;
        assertThrows(IOException.class, () -> SnapshotSerializer.deserialize(bytes));
    }
}
