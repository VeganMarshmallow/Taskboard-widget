package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm

/** The task an "Ask Claude" question is about; [title] is null when the screen was not told it. */
data class AskTopic(val number: Int, val title: String?)

/**
 * Everything the Questions screen renders apart from each card's reply box (task #82), as an
 * immutable value with pure transitions.
 *
 * - [questions] stays visible while a reload runs or after one fails ([error]).
 * - [topic] is the task the ask box asks about, or null for a general question.
 * - [ask] is the ask box: a [ReplyForm] without checkboxes, remembered per [askKey].
 */
data class QuestionsScreen(
    val loading: Boolean = false,
    val error: BoardError? = null,
    val questions: Questions? = null,
    val topic: AskTopic? = null,
    val ask: ReplyForm = ReplyForm(),
) {
    /** Where the ask box's draft is remembered: the task's number, or [GENERAL] for a general question. */
    val askKey: Int get() = topic?.number ?: GENERAL

    fun loadStarted() = copy(loading = true, error = null)

    fun loaded(found: Questions) = copy(loading = false, error = null, questions = found)

    fun failed(failure: BoardError) = copy(loading = false, error = failure)

    fun withAsk(form: ReplyForm) = copy(ask = form)

    /** The ask box switches to a general question (with that question's own draft). */
    fun askGeneral(form: ReplyForm) = copy(topic = null, ask = form)

    companion object {
        /** Not an issue number (those start at 1), so a general draft never mixes with a task's. */
        const val GENERAL = 0
    }
}
