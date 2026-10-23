package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.tags.ProjectColor
import io.github.veganmarshmallow.taskboard.core.tags.Rgb
import io.github.veganmarshmallow.taskboard.core.tags.TextTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TagColorTest {
    @Test fun parsesGitHubHexColours() {
        assertEquals(Rgb(0xB60205), Rgb.parse("b60205"))
        assertEquals(Rgb(0xB60205), Rgb.parse("#B60205"))
        assertEquals(Rgb(0xFF0000), Rgb.parse("f00"))
        assertEquals(Rgb(0xFFFFFF), Rgb.parse(" ffffff "))
    }

    @Test fun rejectsUnreadableColours() {
        listOf(null, "", "#", "12345", "1234567", "gggggg", "red", "#ff00", "0xffffff", "-12345", "ff ff ff", "ｆｆｆｆｆｆ").forEach {
            assertNull("$it", Rgb.parse(it))
        }
    }

    @Test fun rgbRangeAndParts() {
        assertThrows(IllegalArgumentException::class.java) { Rgb(-1) }
        assertThrows(IllegalArgumentException::class.java) { Rgb(0x1000000) }
        val c = Rgb(0x123456)
        assertEquals(0x12, c.red); assertEquals(0x34, c.green); assertEquals(0x56, c.blue)
        assertEquals(0xFF123456.toInt(), c.argb)
        assertEquals("123456", c.hex())
        assertEquals("000000", Rgb.BLACK.hex())
    }

    @Test fun luminanceAndContrastMatchWcag() {
        assertEquals(0.0, Rgb.BLACK.luminance, 1e-9)
        assertEquals(1.0, Rgb.WHITE.luminance, 1e-9)
        assertEquals(21.0, Rgb.BLACK.contrastWith(Rgb.WHITE), 1e-9)
        assertEquals(1.0, Rgb(0x777777).contrastWith(Rgb(0x777777)), 1e-9)
        assertEquals(0.2159, Rgb(0x808080).luminance, 1e-3) // WCAG reference value for #808080
        assertEquals(Rgb.WHITE.contrastWith(Rgb(0x0000FF)), Rgb(0x0000FF).contrastWith(Rgb.WHITE), 1e-12)
    }

    @Test fun textToneForTheBoardsRealLabelColours() {
        // name -> colour from VeganMarshmallow/claude-workspace; expected tone by eye and by WCAG
        val dark = listOf("c5def5", "c2e0c6", "a2eeef", "fbca04", "fef2c0", "bfd4f2", "ededed", "ffffff", "d4d4d4")
        val light = listOf("b60205", "1d76db", "ee0701", "5319e7", "0e8a16", "000000", "d93f0b")
        dark.forEach { assertEquals(it, TextTone.DARK, TextTone.on(Rgb.parse(it)!!)) }
        light.forEach { assertEquals(it, TextTone.LIGHT, TextTone.on(Rgb.parse(it)!!)) }
    }

    @Test fun chosenToneAlwaysMeetsWcagAa() {
        var v = 0
        while (v <= 0xFFFFFF) {
            val bg = Rgb(v)
            val tone = TextTone.on(bg)
            val other = if (tone == TextTone.DARK) TextTone.LIGHT else TextTone.DARK
            // Always WCAG AA, and never the worse tone unless that one also passes AA (white preferred).
            assertTrue(bg.hex(), bg.contrastWith(tone.rgb) >= TextTone.MIN_CONTRAST)
            assertTrue(bg.hex(), bg.contrastWith(tone.rgb) >= bg.contrastWith(other.rgb) || tone == TextTone.LIGHT)
            v += 0x010307
        }
    }

    @Test fun projectColourIsDeterministicAndCaseInsensitive() {
        assertEquals(ProjectColor.of("Taskboard-widget"), ProjectColor.of("Taskboard-widget"))
        assertEquals(ProjectColor.of("Taskboard-widget"), ProjectColor.of("taskboard-WIDGET"))
        assertEquals(ProjectColor.of("site"), ProjectColor.of("  site "))
        // Pinned so a refactor can't silently recolour every project (FNV-1a, not String.hashCode).
        assertEquals("a63079", ProjectColor.of("Taskboard-widget").hex())
        assertEquals("3090a6", ProjectColor.of("claude-workspace").hex())
    }

    @Test fun differentProjectsUsuallyGetDifferentColours() {
        val names = listOf("Taskboard-widget", "claude-workspace", "portfolio-site", "nature-vs-life", "dotfiles", "api", "web", "app")
        assertTrue(names.map { ProjectColor.of(it) }.toSet().size >= names.size - 1)
        assertNotEquals(ProjectColor.of("a"), ProjectColor.of("b"))
    }

    @Test fun projectColoursAreAlwaysSaturatedAndReadable() {
        (0 until 2_000).map { "project-$it" }.plus(listOf("", "פרויקט", "😀", "x".repeat(10_000))).forEach { name ->
            val c = ProjectColor.of(name)
            val hue = ProjectColor.hue(name)
            assertTrue(hue >= 0 && hue < 360)
            val spread = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
            assertTrue("$name ${c.hex()}", spread > 80) // never grey
            assertTrue("$name ${c.hex()}", c.contrastWith(TextTone.on(c).rgb) >= TextTone.MIN_CONTRAST)
        }
    }

    @Test fun hslCorners() {
        assertEquals(Rgb(0xFF0000), ProjectColor.hsl(0.0, 1.0, 0.5))
        assertEquals(Rgb(0x00FF00), ProjectColor.hsl(120.0, 1.0, 0.5))
        assertEquals(Rgb(0x0000FF), ProjectColor.hsl(240.0, 1.0, 0.5))
        assertEquals(Rgb(0xFF0000), ProjectColor.hsl(360.0, 1.0, 0.5))
        assertEquals(Rgb(0x808080), ProjectColor.hsl(42.0, 0.0, 0.5))
        assertEquals(Rgb.WHITE, ProjectColor.hsl(42.0, 1.0, 1.0))
    }

    // --- review of #13: boundaries and odd colour strings ---

    @Test fun toneAtTheLuminanceBoundary() {
        // Find the exact grey where white drops below 4.5:1; one step lighter must switch to black.
        val greys = (0..255).map { Rgb(it * 0x010101) }
        val lastWhite = greys.last { TextTone.on(it) == TextTone.LIGHT }
        val firstBlack = greys.first { TextTone.on(it) == TextTone.DARK }
        assertEquals(lastWhite.red + 1, firstBlack.red)
        assertTrue(lastWhite.contrastWith(Rgb.WHITE) >= 4.5)
        assertTrue(firstBlack.contrastWith(Rgb.WHITE) < 4.5)
        assertTrue(firstBlack.contrastWith(Rgb.BLACK) >= 4.5)
        assertEquals(TextTone.LIGHT, TextTone.on(Rgb(0x1D76DB))) // GitHub's blue: white, as on github.com
        assertEquals(TextTone.LIGHT, TextTone.on(Rgb.BLACK))
        assertEquals(TextTone.DARK, TextTone.on(Rgb.WHITE))
    }

    @Test fun shortUppercaseAndPaddedColours() {
        assertEquals(Rgb(0xAABBCC), Rgb.parse("ABC"))
        assertEquals(Rgb(0xAABBCC), Rgb.parse("#abc"))
        assertEquals(Rgb(0x000000), Rgb.parse("000"))
        assertEquals(Rgb(0xB60205), Rgb.parse("\tB60205\n"))
        assertEquals(Rgb(0xAABBCC), Rgb.parse("abc ")) // surrounding whitespace is trimmed
        listOf("##abc", "#abcd", "ab", "abcabc1", "#ggg", "١٢٣٤٥٦").forEach { assertNull(it, Rgb.parse(it)) }
    }
}
