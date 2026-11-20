package com.dravorax.arenaregendx.regen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegenSpeedTest {

    @Test
    void parsesPresetsAndClampsPercents() {
        assertEquals(RegenSpeed.SLOW, RegenSpeed.parsePresetOrPercent("slow"));
        assertEquals(RegenSpeed.MAX_PRESET, RegenSpeed.parsePresetOrPercent(" MAX "));
        assertEquals(RegenSpeed.INHERIT, RegenSpeed.parsePresetOrPercent("inherit"));
        assertEquals(RegenSpeed.MIN, RegenSpeed.parsePresetOrPercent("1"));
        assertEquals(RegenSpeed.MAX, RegenSpeed.parsePresetOrPercent("9000"));
        assertThrows(IllegalArgumentException.class, () -> RegenSpeed.parsePresetOrPercent("warp"));
    }

    @Test
    void resolvesInheritToGlobal() {
        assertEquals(150, RegenSpeed.resolve(RegenSpeed.INHERIT, 150));
        assertEquals(40, RegenSpeed.resolve(40, 150));
    }
}
