package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.model.GitHubNames
import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * Parses the form's Project repo field. Accepts `owner/name` (spaces around the slash are fine), a
 * bare `name` (owner defaults to [defaultOwner]), or a pasted GitHub link: `https://github.com/owner/name`,
 * `github.com/owner/name`, or `git@github.com:owner/name.git`. A link names the repo in its first
 * two path segments; anything after it (`/issues/3`, `/tree/main`, `?tab=…`, `#readme`) is ignored.
 * An optional `.git` suffix is dropped. Invisible bidi marks ([InvisibleText]) are removed first.
 * Names follow GitHub's rules: owners are 1-39 letters, digits or single hyphens, not starting or
 * ending with one; repo names are 1-100 of letters, digits, `.`, `-` and `_`, and not `.` or `..`.
 */
object RepoInput {
    const val DEFAULT_OWNER = "VeganMarshmallow"

    sealed class Result {
        data object Empty : Result()
        data class Valid(val repo: Repo) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private val LINK_PREFIX = Regex("^(?:(?:https?://)?(?:www\\.)?github\\.com/|git@github\\.com:)", RegexOption.IGNORE_CASE)
    const val FORMAT_HINT = "Use owner/name, e.g. VeganMarshmallow/my-app"

    fun parse(input: String, defaultOwner: String = DEFAULT_OWNER): Result {
        val text = clean(input)
        if (text.isEmpty()) return Result.Empty
        LINK_PREFIX.find(text)?.let { return parseLink(text.substring(it.range.last + 1)) }
        val parts = dropGitSuffix(text).split('/').map(String::trim)
        return when (parts.size) {
            1 -> build(defaultOwner, parts[0])
            2 -> build(parts[0], parts[1])
            else -> Result.Invalid("Too many slashes. $FORMAT_HINT")
        }
    }

    /** Like [parse] but requires an explicit owner (or a link); returns null for anything else. Used on text found in issue bodies. */
    fun parseStrict(input: String): Repo? {
        val text = clean(input)
        if (!LINK_PREFIX.containsMatchIn(text) && text.count { it == '/' } != 1) return null
        return (parse(text) as? Result.Valid)?.repo
    }

    private fun clean(input: String): String = InvisibleText.strip(input).trim()

    private fun parseLink(path: String): Result {
        val segments = path.substringBefore('?').substringBefore('#').split('/').map(String::trim).filter(String::isNotEmpty)
        return when (segments.size) {
            0 -> Result.Invalid("Owner is missing. $FORMAT_HINT")
            1 -> Result.Invalid("Repo name is missing. $FORMAT_HINT")
            else -> build(segments[0], dropGitSuffix(segments[1]))
        }
    }

    private fun dropGitSuffix(text: String): String =
        if (text.endsWith(".git", ignoreCase = true) && text.length > 4) text.dropLast(4) else text

    private fun build(owner: String, name: String): Result = when {
        owner.isEmpty() -> Result.Invalid("Owner is missing. $FORMAT_HINT")
        name.isEmpty() -> Result.Invalid("Repo name is missing. $FORMAT_HINT")
        !GitHubNames.isTypedOwner(owner) ->
            Result.Invalid("“$owner” isn't a valid GitHub owner. $FORMAT_HINT")
        !GitHubNames.isName(name) ->
            Result.Invalid("“$name” isn't a valid repo name. Use letters, digits, '.', '-' or '_'")
        else -> Result.Valid(Repo(owner, name))
    }
}
