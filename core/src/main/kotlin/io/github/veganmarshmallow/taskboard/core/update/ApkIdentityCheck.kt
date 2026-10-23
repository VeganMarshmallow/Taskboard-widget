package io.github.veganmarshmallow.taskboard.core.update

/**
 * Decides whether a downloaded APK is the published update of the installed app: same package,
 * the versionCode that version.json promised, and signed by the installed app's key. The SHA-256
 * from version.json proves the file is what the release holds; this proves it comes from the key
 * that signed the installed build.
 */
object ApkIdentityCheck {
    /** Null when [archive] may be installed over [installed]; otherwise why not. */
    fun check(installed: ApkIdentity?, archive: ApkIdentity?, expectedVersionCode: Long): UpdateError? {
        if (installed == null || installed.signers.isEmpty()) return UpdateError.Untrusted("can't read this app's signature")
        if (archive == null) return UpdateError.Corrupt("Android can't read the downloaded APK")
        if (archive.packageName != installed.packageName) return UpdateError.Untrusted("it is a different app")
        if (archive.versionCode != expectedVersionCode) return UpdateError.Corrupt("its versionCode doesn't match version.json")
        if (archive.signers.isEmpty()) return UpdateError.Untrusted("it is not signed")
        if (!sameSigner(installed, archive)) return UpdateError.Untrusted("it is signed with a different key")
        return null
    }

    /**
     * Android's own rule for updates, simplified: with several signers the sets must be equal; with
     * one, the installed signer must be the new APK's signer or one it rotated from.
     */
    private fun sameSigner(installed: ApkIdentity, archive: ApkIdentity): Boolean =
        if (installed.signers.size > 1 || archive.signers.size > 1) {
            installed.signers == archive.signers
        } else {
            installed.signers.single() in (archive.signerHistory + archive.signers)
        }
}
