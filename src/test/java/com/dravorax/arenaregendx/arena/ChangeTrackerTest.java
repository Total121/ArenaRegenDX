package com.dravorax.arenaregendx.arena;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeTrackerTest {

    private Arena arena;
    private ChangeTracker tracker;

    @BeforeEach
    void setUp() {
        // 100 x 50 x 100 = 500k blocks; threshold 0.35 -> cap 175k
        arena = new Arena("warzone", "world", 0, 0, 0, 100, 50, 100,
                0L, 1L, 0L, 1L, ArenaStatus.READY, 0, true, false, 0, 64, 0, 0, 0);
        tracker = new ChangeTracker();
        tracker.bind((world, x, y, z) -> world.equals("world") && arena.contains(x, y, z) ? arena : null);
    }

    @ParameterizedTest
    @CsvSource({"0,0,0", "-1,-64,-1", "29999999,319,-29999999", "-30000000,-2048,30000000", "12345,2047,-54321"})
    void packRoundTrips(final int x, final int y, final int z) {
        final long packed = ChangeTracker.pack(x, y, z);
        assertEquals(x & 0x3FFFFFF, ChangeTracker.unpackX(packed) & 0x3FFFFFF);
        assertEquals(x, ChangeTracker.unpackX(packed));
        assertEquals(y, ChangeTracker.unpackY(packed));
        assertEquals(z, ChangeTracker.unpackZ(packed));
    }

    @Test
    void untrustedArenaRecordsNothingAndForcesFullRestore() {
        tracker.mark("world", 5, 5, 5);
        assertEquals(0, tracker.dirtyCount("warzone"));
        assertFalse(tracker.trusted("warzone"));
        assertNull(tracker.beginRestore(arena, true), "untrusted arenas must do a full restore");
    }

    @Test
    void fullRestoreStartsTrustAndDirtyRestoreHandsOverTheSet() {
        assertNull(tracker.beginRestore(arena, true)); // full restore -> trusted from now on
        assertTrue(tracker.trusted("warzone"));
        tracker.mark("world", 1, 2, 3);
        tracker.mark("world", 1, 2, 3); // duplicate
        tracker.mark("world", 4, 5, 6);
        tracker.mark("world", 500, 5, 6); // outside the arena
        assertEquals(2, tracker.dirtyCount("warzone"));

        final long[] dirty = tracker.beginRestore(arena, true);
        assertNotNull(dirty);
        assertEquals(2, dirty.length);
        assertEquals(0, tracker.dirtyCount("warzone"), "set is handed over, not copied");
        tracker.endRestore("warzone", true);
        assertTrue(tracker.trusted("warzone"));
    }

    @Test
    void manualRestoresIgnoreDirtySet() {
        tracker.reset(arena);
        tracker.mark("world", 1, 2, 3);
        assertNull(tracker.beginRestore(arena, false));
        assertEquals(0, tracker.dirtyCount("warzone"), "a full restore starts a fresh record");
    }

    @Test
    void failedRestoreLosesTrust() {
        tracker.reset(arena);
        tracker.mark("world", 1, 2, 3);
        tracker.beginRestore(arena, true);
        tracker.endRestore("warzone", false);
        assertFalse(tracker.trusted("warzone"));
        assertNull(tracker.beginRestore(arena, true));
    }

    @Test
    void overflowDropsTheSetAndForcesFullRestore() {
        final Arena small = new Arena("pit", "world", 0, 0, 0, 40, 40, 40,
                0L, 1L, 0L, 1L, ArenaStatus.READY, 0, true, false, 0, 64, 0, 0, 0);
        tracker.bind((world, x, y, z) -> small.contains(x, y, z) ? small : null);
        tracker.setFullThreshold(0.05); // 64000 * 0.05 = 3200 voxels
        tracker.reset(small);
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 40; y++) {
                for (int z = 0; z < 3; z++) {
                    tracker.mark("world", x, y, z);
                }
            }
        }
        assertFalse(tracker.trusted("pit"));
        assertEquals(0, tracker.dirtyCount("pit"), "overflowed set must release its memory");
        assertNull(tracker.beginRestore(small, true));
    }

    @Test
    void excludedVoxelsAreNotTracked() {
        arena.addExclusion(ExclusionZone.cuboid("spawn",
                new org.bukkit.Location(null, 10, 0, 10), new org.bukkit.Location(null, 20, 49, 20)));
        tracker.reset(arena);
        tracker.mark("world", 15, 5, 15);
        tracker.mark("world", 30, 5, 30);
        assertEquals(1, tracker.dirtyCount("warzone"));
    }

    @Test
    void disablingTrackingForgetsEverything() {
        tracker.reset(arena);
        tracker.mark("world", 1, 1, 1);
        tracker.setEnabled(false);
        tracker.setEnabled(true);
        assertFalse(tracker.trusted("warzone"));
    }

    @Test
    void dirtyVoxelsRoundTripThroughPacking() {
        tracker.reset(arena);
        tracker.mark("world", 7, 8, 9);
        final long[] dirty = tracker.beginRestore(arena, true);
        assertArrayEquals(new long[]{ChangeTracker.pack(7, 8, 9)}, dirty);
    }
}
