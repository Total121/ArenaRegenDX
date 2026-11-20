package com.dravorax.arenaregendx.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorUtilTest {

    @Test
    void sanitizeIsLocaleIndependent() {
        final Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // Under tr-TR, "I".toLowerCase() is a dotless i and used to be dropped.
            assertEquals("island", ColorUtil.sanitizeName("ISLAND"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void sanitizeStripsFormattingAndUnsafeCharacters() {
        assertEquals("warzone", ColorUtil.sanitizeName("&6<bold>War Zone!"));
        assertEquals("a-b_c1", ColorUtil.sanitizeName("../A-b_c1/"));
        assertEquals("", ColorUtil.sanitizeName("!!!"));
    }

    @Test
    void validNamesHaveBoundedLength() {
        assertTrue(ColorUtil.isValidName("duel1"));
        assertFalse(ColorUtil.isValidName(""));
        assertFalse(ColorUtil.isValidName("x".repeat(ColorUtil.MAX_NAME_LENGTH + 1)));
    }

    @Test
    void escapeNeutralisesTags() {
        final String hostile = "<click:run_command:/op me>x";
        assertEquals("<click:run_command:/op me>x",
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(ColorUtil.mini(ColorUtil.escape(hostile))));
    }
}
