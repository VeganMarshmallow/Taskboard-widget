package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.model.Priority

/**
 * JSON text form of a [TaskDraft], so the form's draft survives process death. Decoding never
 * throws: a non-object decodes to null, and a missing or unknown field falls back to its default.
 */
object TaskDraftCodec {
    fun encode(draft: TaskDraft): String = JsonWriter.write(
        mapOf(
            "title" to draft.title,
            "goal" to draft.goal,
            "doneWhen" to draft.doneWhen,
            "repo" to draft.repo,
            "type" to draft.type.value,
            "priority" to draft.priority.name,
            "approveNow" to draft.approveNow,
        ),
    )

    fun decode(text: String?): TaskDraft? {
        val root = StoredJson.objectOrNull(text) ?: return null
        val defaults = TaskDraft.EMPTY
        fun string(key: String, fallback: String) = root[key] as? String ?: fallback
        return TaskDraft(
            title = string("title", defaults.title),
            goal = string("goal", defaults.goal),
            doneWhen = string("doneWhen", defaults.doneWhen),
            repo = string("repo", defaults.repo),
            type = TaskType.entries.firstOrNull { it.value == root["type"] } ?: defaults.type,
            priority = Priority.entries.firstOrNull { it.name == root["priority"] } ?: defaults.priority,
            approveNow = root["approveNow"] as? Boolean ?: defaults.approveNow,
        )
    }
}
