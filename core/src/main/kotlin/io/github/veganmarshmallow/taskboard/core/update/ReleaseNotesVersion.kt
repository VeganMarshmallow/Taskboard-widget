package io.github.veganmarshmallow.taskboard.core.update

/**
 * Fallback for releases published before `version.json` existed: their notes read
 * "Built from 776dc5f on main (build 9, versionCode 9)." No checksum is available then.
 */
object ReleaseNotesVersion {
    private val CODE = Regex("""\bversionCode (\d{1,9})\b""")

    fun parse(notes: String?): VersionInfo? {
        val code = notes?.let { CODE.find(it) }?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return VersionInfo(code, "0.1.$code")
    }
}
