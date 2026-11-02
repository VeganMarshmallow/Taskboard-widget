package io.github.veganmarshmallow.taskboard.core.model

/**
 * GitHub's owner, repo-name, branch and commit-SHA shapes: the one definition of what may end up in
 * an API URL path. Used by the Repo field parsers (`tags/RepoField`, `task/RepoInput`), the PR link
 * parser and the merge request builders. Each rule is tested in `GitHubNamesTest`.
 */
internal object GitHubNames {
    /** An owner as GitHub may return it: a letter or digit, then up to 38 letters, digits or `-`. */
    const val OWNER = """[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"""
    const val NAME = """[A-Za-z0-9._-]{1,100}"""
    val SHA = Regex("^[0-9a-fA-F]{7,64}$")

    private val OWNER_ONLY = Regex("^$OWNER$")
    private val NAME_ONLY = Regex("^$NAME$")
    private val NEW_OWNER = Regex("^[A-Za-z0-9](?:-?[A-Za-z0-9])*$")
    private val BRANCH = Regex("^[A-Za-z0-9._/-]{1,200}$")

    fun isOwner(s: String) = OWNER_ONLY.matches(s)

    /**
     * An owner typed by the user: [isOwner], and GitHub's sign-up rule on top (no leading, trailing
     * or doubled `-`), so the form flags a typo instead of creating an issue for a repo that can't exist.
     */
    fun isTypedOwner(s: String) = isOwner(s) && NEW_OWNER.matches(s)

    /** A repo name; `.` and `..` match the pattern but would change the URL path. */
    fun isName(s: String) = NAME_ONLY.matches(s) && s != "." && s != ".."

    /** A branch name safe in a URL path (#45 compares against the default branch): no `..`, no leading `/`. */
    fun isBranch(s: String) = BRANCH.matches(s) && ".." !in s && !s.startsWith("/") && !s.endsWith("/")
}
