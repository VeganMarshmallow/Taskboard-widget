package io.github.veganmarshmallow.taskboard.core.theme

/**
 * The look the app and widget use (#46), picked in settings. [CALM] ("A · calm tonal": warm neutrals,
 * clay accent, rounded) is the default; [INK] ("B · ink and paper": near-monochrome, serif titles,
 * a vermilion signal colour, square-ish shapes). Only the [key] is stored, so renaming an entry
 * never loses the choice.
 */
enum class ThemeChoice(val key: String) {
    CALM("calm"),
    INK("ink"),
    ;

    companion object {
        val DEFAULT = CALM

        /** The stored [key] back to a choice; nothing stored, or anything unknown, is [DEFAULT]. */
        fun decode(raw: String?): ThemeChoice {
            val key = raw?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.key == key } ?: DEFAULT
        }

        fun encode(choice: ThemeChoice): String = choice.key
    }
}
