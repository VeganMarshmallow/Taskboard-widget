package io.github.veganmarshmallow.taskboard.core.tags

import kotlin.math.pow

/** An opaque sRGB colour, 0xRRGGBB. */
data class Rgb(val value: Int) {
    init {
        require(value in 0..0xFFFFFF) { "not a 24-bit colour" }
    }

    val red: Int get() = value shr 16 and 0xFF
    val green: Int get() = value shr 8 and 0xFF
    val blue: Int get() = value and 0xFF

    /** 0xAARRGGBB with full alpha, as Android's Color ints are. */
    val argb: Int get() = (0xFF shl 24) or value

    /** WCAG 2 relative luminance, 0 (black) to 1 (white). */
    val luminance: Double
        get() = 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)

    /** WCAG 2 contrast ratio with [other], 1 to 21. */
    fun contrastWith(other: Rgb): Double {
        val a = luminance
        val b = other.luminance
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    fun hex(): String = "%06x".format(value)

    companion object {
        val BLACK = Rgb(0x000000)
        val WHITE = Rgb(0xFFFFFF)

        private val HEX = Regex("""^#?([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})$""")

        /** GitHub label colours: "b60205" (also "#b60205" and short "f00"). Null when unreadable. */
        fun parse(raw: String?): Rgb? {
            val digits = raw?.trim()?.let { HEX.find(it)?.groupValues?.get(1) } ?: return null
            val six = if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
            return Rgb(six.toInt(16))
        }

        private fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
    }
}
