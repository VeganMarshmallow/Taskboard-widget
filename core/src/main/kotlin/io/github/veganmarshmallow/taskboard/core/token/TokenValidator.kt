package io.github.veganmarshmallow.taskboard.core.token

/** Checks a pasted token before it is saved or sent. */
object TokenValidator {
    sealed class Result {
        /** [token] is trimmed. [warning] is set for tokens that may work but are not what the setup asks for. */
        data class Valid(val token: String, val warning: String? = null) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private const val FINE_GRAINED_PREFIX = "github_pat_"
    private const val MAX_LENGTH = 512

    fun validate(raw: String): Result {
        val token = raw.trim()
        return when {
            token.isEmpty() -> Result.Invalid("Paste a token first")
            token.length > MAX_LENGTH -> Result.Invalid("That's too long to be a GitHub token")
            token.any { it.isWhitespace() || it.isISOControl() } ->
                Result.Invalid("Token contains spaces or line breaks — paste just the token")
            token.any { it.code > 0x7E } -> Result.Invalid("Token contains unexpected characters")
            !token.startsWith(FINE_GRAINED_PREFIX) ->
                Result.Valid(token, "Not a fine-grained token (they start with github_pat_); it may still work")
            else -> Result.Valid(token)
        }
    }
}
