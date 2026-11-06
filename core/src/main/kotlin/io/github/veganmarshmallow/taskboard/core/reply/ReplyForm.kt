package io.github.veganmarshmallow.taskboard.core.reply

/**
 * The preview's reply box (tasks #44, #65) as an immutable value with pure transitions; it lives
 * on the main thread inside the preview's screen state.
 *
 * - [draft] is kept while typing and after a failed send; a successful send clears it.
 * - [unblock] is the "This answers it: unblock the task" checkbox, on by default. It only applies
 *   when the task waits on the owner ([start]'s `offerUnblock`).
 * - [requestChanges] is the "Request changes" checkbox (task #65), off by default and after each
 *   successful send. It only applies when the task's PR is in review ([start]'s `offerRequestChanges`).
 * - [sending] is true from [start] to [finished]; [start] refuses a second send meanwhile, so a
 *   double tap posts once.
 * - [pendingMove]: the reply was posted but this label change failed. Send then retries only the
 *   labels (unless a new reply is typed, which posts it and makes the same move).
 * - [failure] is the last failure, [sent] the last success, each shown until the next send.
 */
data class ReplyForm(
    val draft: String = "",
    val unblock: Boolean = true,
    val requestChanges: Boolean = false,
    val sending: Boolean = false,
    val pendingMove: StatusMove? = null,
    val failure: ReplyResult.Failed? = null,
    val sent: ReplyResult.Sent? = null,
) {
    /** Whether Send is enabled. */
    val canSend: Boolean get() = !sending && (ReplyText.isSendable(draft) || retriesLabels)

    /** The text is too long to post (empty is not a problem worth showing). */
    val tooLong: Boolean get() = ReplyText.clean(draft).length > ReplyText.MAX_LENGTH

    /** Send retries the owed label change alone. */
    val retriesLabels: Boolean get() = pendingMove != null && draft.isBlank()

    fun edit(text: String) = copy(draft = text)

    fun unblockChecked(checked: Boolean) = copy(unblock = checked)

    fun requestChangesChecked(checked: Boolean) = copy(requestChanges = checked)

    class Start(val form: ReplyForm, val action: ReplyAction)

    /** Marks a send as started and says what to send, or null when Send is disabled or a send is in flight. */
    fun start(offerUnblock: Boolean, offerRequestChanges: Boolean = false): Start? {
        if (!canSend) return null
        val pending = pendingMove
        val action = if (pending != null && draft.isBlank()) ReplyAction.Move(pending)
        else ReplyAction.Post(ReplyText.clean(draft), pending ?: chosenMove(offerUnblock, offerRequestChanges))
        return Start(copy(sending = true, failure = null, sent = null), action)
    }

    /** The checked move the task offers; unblocking wins if both were ever offered at once. */
    private fun chosenMove(offerUnblock: Boolean, offerRequestChanges: Boolean): StatusMove? = when {
        offerUnblock && unblock -> StatusMove.UNBLOCK
        offerRequestChanges && requestChanges -> StatusMove.REQUEST_CHANGES
        else -> null
    }

    fun finished(result: ReplyResult): ReplyForm = when (result) {
        is ReplyResult.Sent -> ReplyForm(unblock = unblock, sent = result)
        is ReplyResult.Failed -> when (val move = result.step.move) {
            null -> copy(sending = false, failure = result)
            // The reply is on GitHub now: clear it so it can't be posted again, and offer the label retry.
            else -> copy(sending = false, failure = result, draft = "", pendingMove = move)
        }
    }
}
