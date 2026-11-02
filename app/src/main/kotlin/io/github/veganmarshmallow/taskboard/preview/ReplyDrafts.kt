package io.github.veganmarshmallow.taskboard.preview

import io.github.veganmarshmallow.taskboard.core.reply.ReplyMemory

/**
 * The app process's reply boxes per issue (task #44), shared by every preview sheet so a sheet
 * closed by a stray tap reopens with its draft, and one closed mid-send can't post twice.
 * Main thread only.
 */
val ReplyDrafts = ReplyMemory()
