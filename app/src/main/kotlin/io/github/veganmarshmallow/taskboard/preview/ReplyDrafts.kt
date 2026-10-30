package io.github.veganmarshmallow.taskboard.preview

/**
 * Unsent reply text per issue (task #44), kept for the app process so a sheet closed by a stray
 * tap outside it reopens with the text still there. Main thread only.
 */
object ReplyDrafts {
    private val drafts = HashMap<Int, String>()

    fun get(issue: Int): String = drafts[issue].orEmpty()

    fun put(issue: Int, text: String) {
        if (text.isEmpty()) drafts.remove(issue) else drafts[issue] = text
    }
}
