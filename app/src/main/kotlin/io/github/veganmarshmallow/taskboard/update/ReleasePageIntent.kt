package io.github.veganmarshmallow.taskboard.update

import android.content.Intent
import android.net.Uri

/** Opens a release page in the browser, where the owner downloads the APK. The app installs nothing itself. */
object ReleasePageIntent {
    fun of(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
}
