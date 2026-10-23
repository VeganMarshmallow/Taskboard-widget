package io.github.veganmarshmallow.taskboard.core.update

/**
 * The web page of the `latest` release, which the Update button opens in the browser. The app
 * never downloads or installs APKs itself; the owner downloads the APK there while signed in.
 */
object ReleasePage {
    private const val REPO_PAGE = "https://github.com/VeganMarshmallow/Taskboard-widget/"

    /** Used when the release JSON has no usable `html_url` (or a cached check predates it). */
    const val FALLBACK = "${REPO_PAGE}releases/tag/${UpdateRequests.LATEST_TAG}"

    /** Only https pages of the app's own repository on github.com are opened. */
    fun isSafe(url: String?): Boolean =
        url != null && url.startsWith(REPO_PAGE) && url.length <= MAX_LENGTH && url.none { it.isWhitespace() || it.isISOControl() }

    /** The page the Update button opens: the offered update's, or the `latest` page if none is offered. */
    fun toOpen(status: UpdateStatus, installedVersionCode: Long): String =
        status.offer(installedVersionCode)?.pageUrl ?: FALLBACK

    /** [url] when it is safe to open, otherwise [FALLBACK]. */
    fun orFallback(url: String?): String = if (isSafe(url)) url!! else FALLBACK

    private const val MAX_LENGTH = 2048
}
