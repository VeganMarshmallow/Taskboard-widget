package io.github.veganmarshmallow.taskboard.core.tags

/**
 * White or black text on a coloured pill. White whenever it meets WCAG AA (4.5:1), since it reads
 * better on saturated label colours (GitHub does the same, e.g. white on `1d76db`); otherwise black,
 * which then always has more than 4.5:1 (the two tones cross over at about 4.58:1).
 */
enum class TextTone(val rgb: Rgb) {
    /** Pure black: the app's near-black #1B1C1F fell below 4:1 on some light-mid colours. */
    DARK(Rgb.BLACK),
    LIGHT(Rgb.WHITE);

    companion object {
        /** WCAG AA for normal-size text; the chosen tone always reaches it. */
        const val MIN_CONTRAST = 4.5

        fun on(background: Rgb): TextTone =
            if (background.contrastWith(LIGHT.rgb) >= MIN_CONTRAST) LIGHT else DARK
    }
}
