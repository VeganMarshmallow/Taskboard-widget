package io.github.veganmarshmallow.taskboard.core.tags

import kotlin.math.abs

/**
 * A stable colour for a project name: the same name (ignoring case) always gets the same colour,
 * on every device and version, because it comes from FNV-1a over the UTF-8 bytes, not from
 * [String.hashCode]. Hues are spread around the wheel at a fixed saturation and lightness so every
 * project pill is clearly coloured and readable.
 */
object ProjectColor {
    private const val SATURATION = 0.55
    private const val LIGHTNESS = 0.42

    fun of(name: String): Rgb = hsl(hue(name), SATURATION, LIGHTNESS)

    /** 0 until 360. */
    fun hue(name: String): Double {
        var hash = 0x811C9DC5.toInt()
        for (b in name.trim().lowercase().toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (b.toInt() and 0xFF)) * 0x01000193
        }
        return (hash.toLong() and 0xFFFFFFFFL) % 360L * 1.0
    }

    internal fun hsl(hue: Double, saturation: Double, lightness: Double): Rgb {
        val c = (1 - abs(2 * lightness - 1)) * saturation
        val h = (hue % 360 + 360) % 360 / 60
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = lightness - c / 2
        fun channel(v: Double) = Math.round((v + m) * 255).toInt().coerceIn(0, 255)
        return Rgb((channel(r) shl 16) or (channel(g) shl 8) or channel(b))
    }
}
