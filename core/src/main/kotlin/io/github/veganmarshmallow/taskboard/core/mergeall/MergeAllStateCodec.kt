package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.json.list
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.model.Repo
import java.time.Instant

/**
 * [MergeAllState] to and from JSON, for the app's store (#57). [decode] returns null for anything it
 * can't read back in full (a corrupt file, another app version's format): no run is better than a
 * guessed one, which could merge the wrong PR.
 */
object MergeAllStateCodec {
    private const val VERSION = 1L

    fun encode(state: MergeAllState): String = JsonWriter.write(
        mapOf(
            "v" to VERSION,
            "run" to state.runId,
            "plan" to state.plan.map { mapOf("issue" to it.issue.toLong(), "title" to it.title) + ref(it.pr) },
            "index" to state.index.toLong(),
            "phase" to state.phase.name,
            "merged" to state.merged.map { ref(it.ref) + ("head" to it.headRef) },
            "oldHead" to state.oldHead,
            "checkedHead" to state.checkedHead,
            "waitSince" to state.waitSince?.toString(),
            "end" to end(state.end),
            "updatedAt" to state.updatedAt.toString(),
        ),
    )

    fun decode(text: String?): MergeAllState? = try {
        text?.let { JsonParser.parse(it) as? Map<*, *> }?.takeIf { it.long("v") == VERSION }?.let(::state)
    } catch (e: Exception) {
        null
    }

    private fun ref(ref: PullRef) = mapOf("owner" to ref.repo.owner, "repo" to ref.repo.name, "pr" to ref.number.toLong())

    private fun end(end: MergeAllEnd?): Map<String, Any?>? = when (end) {
        null -> null
        MergeAllEnd.Done -> mapOf("kind" to "done")
        MergeAllEnd.Cancelled -> mapOf("kind" to "cancelled")
        is MergeAllEnd.Stopped -> mapOf("kind" to "stopped", "at" to end.at.toLong(), "stop" to end.stop.kind.name, "detail" to end.stop.detail)
    }

    private fun state(m: Map<*, *>): MergeAllState = MergeAllState(
        runId = m.string("run")!!,
        plan = m.list("plan")!!.map { it as Map<*, *>; PlannedMerge(it.long("issue")!!.toInt(), it.string("title")!!, refOf(it)) },
        index = m.long("index")!!.toInt(),
        phase = MergeAllPhase.valueOf(m.string("phase")!!),
        merged = m.list("merged")!!.map { it as Map<*, *>; MergedPr(refOf(it), it.string("head")!!) },
        oldHead = m.string("oldHead"),
        checkedHead = m.string("checkedHead"),
        waitSince = m.string("waitSince")?.let(Instant::parse),
        end = (m["end"] as? Map<*, *>)?.let(::endOf),
        updatedAt = Instant.parse(m.string("updatedAt")!!),
    )

    private fun refOf(m: Map<*, *>) = PullRef(Repo(m.string("owner")!!, m.string("repo")!!), m.long("pr")!!.toInt())

    private fun endOf(m: Map<*, *>): MergeAllEnd = when (m.string("kind")) {
        "done" -> MergeAllEnd.Done
        "cancelled" -> MergeAllEnd.Cancelled
        "stopped" -> MergeAllEnd.Stopped(m.long("at")!!.toInt(), MergeAllStop(MergeAllStop.Kind.valueOf(m.string("stop")!!), m.string("detail")))
        else -> throw IllegalArgumentException("unknown end")
    }
}
