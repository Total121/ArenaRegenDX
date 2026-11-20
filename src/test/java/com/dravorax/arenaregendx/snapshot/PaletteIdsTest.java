package com.dravorax.arenaregendx.snapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaletteIdsTest {

    @Test
    void picksNarrowestStorage() {
        assertEquals(1000, PaletteIds.allocate(1000, 256).memoryBytes());
        assertEquals(2000, PaletteIds.allocate(1000, 257).memoryBytes());
        assertEquals(2000, PaletteIds.allocate(1000, 65_536).memoryBytes());
        assertEquals(4000, PaletteIds.allocate(1000, 65_537).memoryBytes());
    }

    @Test
    void storesUnsignedValuesAtTheEdges() {
        final PaletteIds bytes = PaletteIds.allocate(4, 256);
        bytes.set(0, 255);
        bytes.set(1, 128);
        assertEquals(255, bytes.get(0));
        assertEquals(128, bytes.get(1));

        final PaletteIds shorts = PaletteIds.allocate(4, 65_536);
        shorts.set(0, 65_535);
        shorts.fill(1, 4, 40_000);
        assertEquals(65_535, shorts.get(0));
        assertEquals(40_000, shorts.get(3));
    }

    @Test
    void compactCopiesEveryValue() {
        final int[] raw = new int[1000];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (i * 7) % 300;
        }
        final PaletteIds ids = PaletteIds.compact(raw, 300);
        for (int i = 0; i < raw.length; i++) {
            assertEquals(raw[i], ids.get(i));
        }
    }
}
