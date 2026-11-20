package com.dravorax.arenaregendx.commands;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArdxCommandTest {

    @ParameterizedTest
    @CsvSource({
            "300, 300", "5m, 300", "2h, 7200", "45s, 45", "off, 0", "OFF, 0", "0, 0",
            "-5, -1", "abc, -1", "9999999, -1"
    })
    void parsesDurations(final String raw, final int expected) {
        assertEquals(expected, ArdxCommand.parseSeconds(raw));
    }

    @ParameterizedTest
    @CsvSource({"512, 512B", "2048, 2.0KB", "3145728, 3.0MB"})
    void formatsSizes(final long bytes, final String expected) {
        assertEquals(expected, ArdxCommand.humanSize(bytes));
    }
}
