package io.github.veganmarshmallow.taskboard.core.update

/** One published build, as described by the release's `version.json`. */
data class VersionInfo(
    val versionCode: Long,
    val versionName: String,
    val commitSha: String? = null,
) {
    init {
        require(versionCode > 0) { "versionCode must be positive" }
    }

    /** "v0.1.42": what the app shows next to "Update available". */
    val label: String get() = "v$versionName"
}
