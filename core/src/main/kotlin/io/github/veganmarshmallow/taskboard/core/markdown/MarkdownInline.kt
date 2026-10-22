package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Strips inline Markdown from one block's text, keeping what a reader would see: link and image
 * text without URLs, code spans without backticks, no emphasis markers, no HTML tags, decoded
 * entities and backslash escapes. Code span contents are kept verbatim (an `*` in code stays).
 * Underscores inside words (snake_case) are not emphasis. Never throws.
 */
object MarkdownInline {
    private val CODE_SPAN = Regex("""(`+)(.+?)\1""")
    private val IMAGE = Regex("""!\[([^\]]*)]\((?:[^()]|\([^()]*\))*\)""")
    private val LINK = Regex("""\[([^\]]*)]\((?:[^()]|\([^()]*\))*\)""")
    private val REFERENCE_LINK = Regex("""\[([^\]]+)]\[[^\]]*]""")
    private val AUTOLINK = Regex("""<((?:https?|mailto):[^<>\s]+)>""", RegexOption.IGNORE_CASE)
    private val BREAK_TAG = Regex("""</?(?:br|p|div|li|tr|td|th|ul|ol|hr)\b[^<>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""</?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?/?>""")
    private val EMPHASIS = listOf(
        Regex("""\*\*(?!\s)(.+?)(?<!\s)\*\*"""),
        Regex("""(?<![\p{L}\p{N}_])__(?!\s)(.+?)(?<!\s)__(?![\p{L}\p{N}_])"""),
        Regex("""~~(?!\s)(.+?)(?<!\s)~~"""),
        Regex("""(?<![\p{L}\p{N}*\\])\*(?![\s*])(.+?)(?<![\s*\\])\*(?![\p{L}\p{N}*])"""),
        Regex("""(?<![\p{L}\p{N}_\\])_(?![\s_])(.+?)(?<![\s_\\])_(?![\p{L}\p{N}_])"""),
    )
    private val ESCAPE = Regex("""\\([!-/:-@\[-`{-~])""")
    private val NUMERIC_ENTITY = Regex("""&#(?:[xX]([0-9A-Fa-f]{1,6})|([0-9]{1,7}));""")
    private val NAMED_ENTITIES = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ",
    )
    private val WHITESPACE = Regex("""\s+""")

    /** Private-use characters stand in for escaped punctuation while emphasis is matched. */
    private const val ESCAPE_BASE = 0xE000
    private val ESCAPE_RANGE = ESCAPE_BASE..(ESCAPE_BASE + 0x7F)

    fun strip(text: String): String {
        val clean = text.filterNot { it.code in ESCAPE_RANGE }
        val out = StringBuilder()
        var last = 0
        for (m in CODE_SPAN.findAll(clean)) {
            out.append(stripOutsideCode(clean.substring(last, m.range.first)))
            out.append(m.groupValues[2].trim())
            last = m.range.last + 1
        }
        out.append(stripOutsideCode(clean.substring(last)))
        return WHITESPACE.replace(out, " ").trim()
    }

    private fun stripOutsideCode(segment: String): String {
        var s = ESCAPE.replace(segment) { (ESCAPE_BASE + it.groupValues[1][0].code).toChar().toString() }
        s = IMAGE.replace(s, "$1")
        s = LINK.replace(s, "$1")
        s = REFERENCE_LINK.replace(s, "$1")
        s = AUTOLINK.replace(s, "$1")
        s = BREAK_TAG.replace(s, " ")
        s = HTML_TAG.replace(s, "")
        // Nested emphasis (***both***, **_mixed_**) needs a few passes; stop once nothing changes.
        for (pass in 1..MAX_EMPHASIS_PASSES) {
            val before = s
            EMPHASIS.forEach { re -> s = re.replace(s, "$1") }
            if (s == before) break
        }
        s = decodeEntities(s)
        return s.map { if (it.code in ESCAPE_RANGE) (it.code - ESCAPE_BASE).toChar() else it }.joinToString("")
    }

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        var out = NUMERIC_ENTITY.replace(s) { m ->
            val code = m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16) ?: m.groupValues[2].toIntOrNull()
            if (code == null || code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) "\uFFFD"
            else String(Character.toChars(code))
        }
        NAMED_ENTITIES.forEach { (entity, value) -> out = out.replace(entity, value, ignoreCase = true) }
        return out
    }

    private const val MAX_EMPHASIS_PASSES = 4
}
