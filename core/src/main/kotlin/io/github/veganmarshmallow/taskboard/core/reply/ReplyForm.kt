package io.github.veganmarshmallow.taskboard.core.reply

/**
 * The preview's reply box (task #44) as an immutable value with pure transitions; it lives on the
 * main thread inside the preview's screen state.
 *
 * - [draft] is kept while typing and after a failed send; a successful send clears it.
 * - [unblock] is the "This answers it: unblock the task" checkbox, on by default. It only applies
 *   when the task waits on the owner ([start]'s `offerUnblock`).
 * - [sending] is true from [start] to [finished]; [start] refuses a second send meanwhile, so a
 *   double tap posts once.
 * - [pendingUnblock]: the reply was posted but the label change failed. Send then retries only the
 *   labels (unless a new reply is typed, which posts it and unblocks).
 * - [failure] is the last failure, [sent] the last success, each shown until the next send.
 */
data class ReplyForm(
    val draft: String = "",
    val unblock: Boolean = true,
    val sending: Boolean = false,
    val pendingUnblock: Boolean = false,
    val failure: ReplyResult.Failed? = null,
    val sent: ReplyResult.Sent? = null,
) {
    /** Whether Send is enabled. */
    val canSend: Boolean get() = !sending && (ReplyText.isSendable(draft) || (pendingUnblock && draft.isBlank()))

    /** The text is too long to post (empty is not a problem worth showing). */
    val tooLong: Boolean get() = ReplyText.clean(draft).length > ReplyText.MAX_LENGTH

    /** Send retries the label change alone. */
    val retriesUnblock: Boolean get() = pendingUnblock && draft.isBlank()

    fun edit(text: String) = copy(draft = text)

    fun unblockChecked(checked: Boolean) = copy(unblock = checked)

    class Start(val form: ReplyForm, val action: ReplyAction)

    /** Marks a send as started and says what to send, or null when Send is disabled or a send is in flight. */
    fun start(offerUnblock: Boolean): Start? {
        if (!canSend) return null
        val action = if (retriesUnblock) ReplyAction.Unblock
        else ReplyAction.Post(ReplyText.clean(draft), unblock = pendingUnblock || (offerUnblock && unblock))
        return Start(copy(sending = true, failure = null, sent = null), action)
    }

    fun finished(result: ReplyResult): ReplyForm = when (result) {
        is ReplyResult.Sent -> ReplyForm(unblock = unblock, sent = result)
        is ReplyResult.Failed -> when (result.step) {
            ReplyStep.COMMENT -> copy(sending = false, failure = result)
            // The reply is on GitHub now: clear it so it can't be posted again, and offer the label retry.
            ReplyStep.UNBLOCK -> copy(sending = false, failure = result, draft = "", pendingUnblock = true)
        }
    }
}
