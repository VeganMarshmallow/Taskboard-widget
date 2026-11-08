package io.github.veganmarshmallow.taskboard.preview

import io.github.veganmarshmallow.taskboard.core.reply.ReplyMemory

/**
 * The app process's reply boxes per issue (task #44), shared by every preview sheet so a sheet
 * closed by a stray tap reopens with its draft, and one closed mid-send can't post twice.
 * Main thread only.
 */
val ReplyDrafts = ReplyMemory()

/**
 * The Questions screen's "Ask Claude" box (task #82), per task number, or
 * [io.github.veganmarshmallow.taskboard.core.questions.QuestionsScreen.GENERAL] for a general
 * question; kept apart from [ReplyDrafts] so a question never mixes with a reply on the same task.
 */
val AskDrafts = ReplyMemory()
