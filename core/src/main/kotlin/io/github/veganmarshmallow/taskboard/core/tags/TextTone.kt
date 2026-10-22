package io.github.veganmarshmallow.taskboard.core.tags

/** Black or white text on a coloured pill, whichever has the higher WCAG contrast. */
enum class TextTone(val rgb: Rgb) {
    DARK(Rgb(0x1B1C1F)),
    LIGHT(Rgb.WHITE);

    companion object {
        /**
         * Picks the tone with the better contrast against [background]. Uses the near-black the app's
         * text already uses; ties go to dark text (GitHub's mid-grey labels read better that way).
         */
        fun on(background: Rgb): TextTone =
            if (background.contrastWith(DARK.rgb) >= background.contrastWith(LIGHT.rgb)) DARK else LIGHT
    }
}
