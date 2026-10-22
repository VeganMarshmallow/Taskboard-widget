package io.github.veganmarshmallow.taskboard.core.model

/** A GitHub repository coordinate. */
data class Repo(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"

    fun issueUrl(number: Int): String = "https://github.com/$fullName/issues/$number"
    fun issuesUrl(): String = "https://github.com/$fullName/issues"

    companion object {
        /** The owner's task board repository. */
        val BOARD = Repo("VeganMarshmallow", "claude-workspace")
    }
}
