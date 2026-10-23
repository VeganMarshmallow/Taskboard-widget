package io.github.veganmarshmallow.taskboard.core.update

/**
 * Who an APK is: its package name, versionCode and signing certificates, each certificate as the
 * lower-case hex SHA-256 of its encoded bytes. [signers] are the current signers; [signerHistory]
 * also holds certificates the package proved it rotated away from (Android 9+ key rotation), and
 * always contains [signers].
 */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val signerHistory: Set<String> = signers,
)
