package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.theme.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeChoiceTest {
    @Test fun calmIsTheDefault() {
        assertEquals(ThemeChoice.CALM, ThemeChoice.DEFAULT)
    }

    @Test fun everyChoiceSurvivesARoundTrip() {
        ThemeChoice.entries.forEach { assertEquals(it, ThemeChoice.decode(ThemeChoice.encode(it))) }
    }

    @Test fun keysAreStableAndDistinct() {
        assertEquals("calm", ThemeChoice.encode(ThemeChoice.CALM))
        assertEquals("ink", ThemeChoice.encode(ThemeChoice.INK))
        assertEquals(ThemeChoice.entries.size, ThemeChoice.entries.map { it.key }.toSet().size)
    }

    @Test fun nothingStoredIsTheDefault() {
        assertEquals(ThemeChoice.DEFAULT, ThemeChoice.decode(null))
        assertEquals(ThemeChoice.DEFAULT, ThemeChoice.decode(""))
        assertEquals(ThemeChoice.DEFAULT, ThemeChoice.decode("   "))
    }

    @Test fun unknownOrCorruptValuesFallBackToTheDefault() {
        listOf("dense", "C", "inky", "calm ink", "{\"theme\":\"ink\"}", "\u0000", "x".repeat(10_000)).forEach {
            assertEquals(it.take(20), ThemeChoice.DEFAULT, ThemeChoice.decode(it))
        }
    }

    @Test fun decodingIgnoresCaseAndSurroundingSpace() {
        assertEquals(ThemeChoice.INK, ThemeChoice.decode("INK"))
        assertEquals(ThemeChoice.INK, ThemeChoice.decode("  Ink\n"))
        assertEquals(ThemeChoice.CALM, ThemeChoice.decode("Calm"))
    }

    @Test fun enumNamesAreNotKeys() {
        // Only keys are stored; an enum name that differs from its key must not decode by accident.
        assertNotEquals(ThemeChoice.INK, ThemeChoice.decode("I N K"))
    }
}
