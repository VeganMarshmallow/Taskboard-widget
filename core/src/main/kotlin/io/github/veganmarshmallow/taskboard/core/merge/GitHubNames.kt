package io.github.veganmarshmallow.taskboard.core.merge

/**
 * GitHub's owner, repo-name and commit-SHA shapes, shared by the parsers and the request builders
 * of task #30 so the text that ends up in a URL path is validated by one definition.
 */
internal object GitHubNames {
    const val OWNER = """[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"""
    const val NAME = """[A-Za-z0-9._-]{1,100}"""
    val SHA = Regex("^[0-9a-fA-F]{7,64}$")

    private val OWNER_ONLY = Regex("^$OWNER$")
    private val NAME_ONLY = Regex("^$NAME$")

    fun isOwner(s: String) = OWNER_ONLY.matches(s)

    /** A repo name; `.` and `..` match the pattern but would change the URL path. */
    fun isName(s: String) = NAME_ONLY.matches(s) && s != "." && s != ".."
}
