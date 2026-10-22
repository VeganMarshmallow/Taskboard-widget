package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * Parses the form's Project repo field. Accepts `owner/name`, a bare `name` (owner defaults to
 * [defaultOwner]), or a pasted `https://github.com/owner/name` link (an optional `.git` suffix
 * or a link's trailing slash is dropped). Names follow GitHub's rules: owners are 1-39 letters, digits or
 * single hyphens, not starting or ending with one; repo names are 1-100 of letters, digits, `.`,
 * `-` and `_`, and not `.` or `..`.
 */
object RepoInput {
    const val DEFAULT_OWNER = "VeganMarshmallow"

    sealed class Result {
        data object Empty : Result()
        data class Valid(val repo: Repo) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private val OWNER = Regex("^[A-Za-z0-9](?:-?[A-Za-z0-9])*$")
    private val NAME = Regex("^[A-Za-z0-9._-]+$")
    private val URL_PREFIX = Regex("^(?:https?://)?(?:www\\.)?github\\.com/", RegexOption.IGNORE_CASE)
    private const val MAX_OWNER = 39
    private const val MAX_NAME = 100
    const val FORMAT_HINT = "Use owner/name, e.g. VeganMarshmallow/my-app"

    fun parse(input: String, defaultOwner: String = DEFAULT_OWNER): Result {
        var text = input.trim()
        if (text.isEmpty()) return Result.Empty
        // A trailing slash is only dropped from pasted links: in "owner/" it means the name is missing.
        URL_PREFIX.find(text)?.let { text = text.substring(it.range.last + 1).trimEnd('/') }
        if (text.endsWith(".git", ignoreCase = true) && text.length > 4) text = text.dropLast(4)
        val parts = text.split('/')
        return when (parts.size) {
            1 -> build(defaultOwner, parts[0])
            2 -> build(parts[0], parts[1])
            else -> Result.Invalid("Too many slashes. $FORMAT_HINT")
        }
    }

    /** Like [parse] but requires an explicit owner; returns null for anything else. Used on text found in issue bodies. */
    fun parseStrict(input: String): Repo? {
        val text = input.trim()
        if (text.count { it == '/' } != 1) return null
        return (parse(text) as? Result.Valid)?.repo
    }

    private fun build(owner: String, name: String): Result = when {
        owner.isEmpty() -> Result.Invalid("Owner is missing. $FORMAT_HINT")
        name.isEmpty() -> Result.Invalid("Repo name is missing. $FORMAT_HINT")
        owner.length > MAX_OWNER || !OWNER.matches(owner) ->
            Result.Invalid("“$owner” isn't a valid GitHub owner. $FORMAT_HINT")
        name.length > MAX_NAME || !NAME.matches(name) || name == "." || name == ".." ->
            Result.Invalid("“$name” isn't a valid repo name. Use letters, digits, '.', '-' or '_'")
        else -> Result.Valid(Repo(owner, name))
    }
}
