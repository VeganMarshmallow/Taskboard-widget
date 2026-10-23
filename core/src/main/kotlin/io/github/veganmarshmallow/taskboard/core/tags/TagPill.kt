package io.github.veganmarshmallow.taskboard.core.tags

/** One coloured tag in the preview. [kind] orders the pills and lets tests and views tell them apart. */
data class TagPill(val text: String, val background: Rgb, val kind: Kind) {
    val textTone: TextTone get() = TextTone.on(background)

    enum class Kind { PROJECT, TYPE, PRIORITY, APPROVED, NEEDS_YOU, BY_CLAUDE }
}
