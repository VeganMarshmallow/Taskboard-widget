package io.github.veganmarshmallow.taskboard.core.reply

/**
 * The reply box per issue as the app process remembers it between preview sheets (task #44).
 *
 * A sheet closed by a stray tap reopens with its draft, its checkbox choice and any label retry
 * still owed. A sheet closed while its reply is sending reopens with Send still disabled (so the
 * reply can't be posted twice) and hears how that send ended. The last sheet's result message is
 * not carried over. Not thread-safe: use it from one thread (the main thread).
 */
class ReplyMemory {
    private val forms = HashMap<Int, ReplyForm>()
    private val listeners = HashMap<Int, (ReplyForm, ReplyResult) -> Unit>()

    /** Whether this process has seen a reply box for [issue] (so it beats older saved state). */
    fun knows(issue: Int): Boolean = issue in forms

    /** The reply box a sheet for [issue] opens with. */
    fun form(issue: Int): ReplyForm = forms[issue]?.copy(failure = null, sent = null) ?: ReplyForm()

    fun remember(issue: Int, form: ReplyForm) {
        forms[issue] = form
    }

    /** A send for [issue] ended: the remembered form moves on, and the sheet now listening hears it. */
    fun finished(issue: Int, result: ReplyResult) {
        val next = (forms[issue] ?: ReplyForm(sending = true)).finished(result)
        forms[issue] = next
        listeners[issue]?.invoke(next, result)
    }

    /** The sheet showing [issue]; a newer sheet replaces an older one. */
    fun listen(issue: Int, listener: (ReplyForm, ReplyResult) -> Unit) {
        listeners[issue] = listener
    }

    /** Stops [listener], unless a newer sheet has replaced it already. */
    fun stopListening(issue: Int, listener: (ReplyForm, ReplyResult) -> Unit) {
        if (listeners[issue] === listener) listeners.remove(issue)
    }
}
