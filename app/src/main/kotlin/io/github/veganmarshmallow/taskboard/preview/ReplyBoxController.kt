package io.github.veganmarshmallow.taskboard.preview

import io.github.veganmarshmallow.taskboard.core.reply.ReplyAction
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.core.reply.ReplyMemory
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult

/**
 * One reply box on screen (task #86): the task sheet's, a Questions card's, or the ask box. Owns the
 * box's [form] for [key] in [memory] and keeps the rules every box shares:
 * - every change is remembered, so a reopened screen picks up the draft or a send still running;
 * - one listener per key: this box hears how a send ended until a newer screen takes over, and
 *   [resume] takes it back (re-reading the form that send left behind);
 * - [ReplyForm.start] refuses while a send is in flight, so a double tap sends once;
 * - [onPosted] runs after a send that posted, so the screen can refresh.
 *
 * [start] says which checkboxes the task offers when Send is tapped; [send] does the write;
 * [show] puts the form on screen (the owner may fold it into its own screen state first).
 * Main thread only.
 */
class ReplyBoxController(
    private val memory: ReplyMemory,
    key: Int,
    views: ReplyViews,
    initial: ReplyForm = memory.form(key),
    private val start: (ReplyForm) -> ReplyForm.Start? = { it.start(offerUnblock = false) },
    private val send: (ReplyAction) -> Unit,
    private val show: (ReplyForm) -> Unit,
    private val onPosted: () -> Unit,
) {
    var key: Int = key
        private set
    var form: ReplyForm = initial
        private set

    private val listener: (ReplyForm, ReplyResult) -> Unit = { next, result ->
        set(next)
        if (result.posted) onPosted()
    }

    init {
        views.listen(
            onEdit = { text -> if (text != form.draft) set(form.edit(text)) },
            onUnblock = { checked -> if (checked != form.unblock) set(form.unblockChecked(checked)) },
            onRequestChanges = { checked -> if (checked != form.requestChanges) set(form.requestChangesChecked(checked)) },
            onSend = ::sendReply,
        )
        memory.remember(key, initial)
        memory.listen(key, listener)
    }

    /** Back on screen after another one (a task sheet) took this key's listener: take it back. */
    fun resume() {
        memory.listen(key, listener)
        if (memory.knows(key)) set(memory.form(key))
    }

    /** Moves the box to [next] (the ask box going from a task to a general question). */
    fun switchTo(next: Int) {
        memory.stopListening(key, listener)
        key = next
        set(memory.form(next))
        memory.listen(next, listener)
    }

    fun close() = memory.stopListening(key, listener)

    private fun sendReply() {
        val begun = start(form) ?: return
        set(begun.form)
        send(begun.action)
    }

    private fun set(next: ReplyForm) {
        form = next
        memory.remember(key, next)
        show(next)
    }
}
