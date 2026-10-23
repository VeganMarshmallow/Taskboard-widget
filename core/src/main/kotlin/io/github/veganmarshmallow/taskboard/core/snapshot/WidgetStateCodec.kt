package io.github.veganmarshmallow.taskboard.core.snapshot

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.SectionContent
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.json.list
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.model.Priority
import java.time.Instant

/**
 * Serializes [WidgetState] to JSON for local storage. Decoding never throws: anything unreadable
 * (old format, corruption) yields [WidgetState.INITIAL], and the next refresh replaces it.
 */
object WidgetStateCodec {
    private const val VERSION = 3L // 2: six tabs, Done lists items. 3: five tabs (no In review)

    fun encode(state: WidgetState): String = JsonWriter.write(
        mapOf(
            "v" to VERSION,
            "board" to state.board?.let(::boardToMap),
            "error" to state.error?.let(::errorToMap),
            "attemptedAt" to state.attemptedAt?.toEpochMilli(),
        ),
    )

    fun decode(text: String?): WidgetState {
        if (text.isNullOrBlank()) return WidgetState.INITIAL
        return try {
            val root = JsonParser.parse(text) as Map<*, *>
            if (root.long("v") != VERSION) return WidgetState.INITIAL
            WidgetState(
                board = (root["board"] as? Map<*, *>)?.let(::boardFromMap),
                error = (root["error"] as? Map<*, *>)?.let(::errorFromMap),
                attemptedAt = root.long("attemptedAt")?.let(Instant::ofEpochMilli),
            )
        } catch (e: Exception) {
            WidgetState.INITIAL
        }
    }

    private fun boardToMap(board: Board) = mapOf(
        "fetchedAt" to board.fetchedAt.toEpochMilli(),
        "sections" to board.sections.map { s ->
            mapOf(
                "section" to s.section.name,
                "total" to s.totalCount.toLong(),
                "items" to s.items.map(::itemToMap),
            )
        },
    )

    private fun itemToMap(item: BoardItem) = mapOf(
        "n" to item.number.toLong(),
        "t" to item.title,
        "p" to item.priority.name,
        "a" to item.canApprove,
        "r" to item.needsYouReason?.name,
        "m" to item.canMerge,
    )

    private fun boardFromMap(map: Map<*, *>): Board {
        val byName = map.list("sections")!!.map { raw ->
            val s = raw as Map<*, *>
            val items = s.list("items")!!.map { itemFromMap(it as Map<*, *>) }
            SectionContent(Section.valueOf(s.string("section")!!), items, s.long("total")!!.toInt())
        }.associateBy { it.section }
        // Always return every section in display order, even if the stored data lacks one.
        val sections = Section.entries.map { byName[it] ?: SectionContent(it, emptyList(), 0) }
        return Board(sections, Instant.ofEpochMilli(map.long("fetchedAt")!!))
    }

    private fun itemFromMap(m: Map<*, *>) = BoardItem(
        number = m.long("n")!!.toInt(),
        title = m.string("t")!!,
        priority = Priority.valueOf(m.string("p")!!),
        canApprove = m["a"] as Boolean,
        needsYouReason = m.string("r")?.let(NeedsYouReason::valueOf),
        canMerge = m["m"] == true, // absent in boards saved before #30: no Merge until the next refresh
    )

    private fun errorToMap(error: BoardError): Map<String, Any?> = when (error) {
        BoardError.NoToken -> mapOf("k" to "NoToken")
        is BoardError.BadToken -> mapOf("k" to "BadToken", "status" to error.status.toLong())
        BoardError.NoAccess -> mapOf("k" to "NoAccess")
        is BoardError.RateLimited -> mapOf("k" to "RateLimited", "retryAt" to error.retryAt?.toEpochMilli())
        is BoardError.IssueGone -> mapOf("k" to "IssueGone", "n" to error.number.toLong())
        BoardError.Network -> mapOf("k" to "Network")
        BoardError.Timeout -> mapOf("k" to "Timeout")
        is BoardError.Server -> mapOf("k" to "Server", "status" to error.status.toLong())
        is BoardError.Unexpected -> mapOf("k" to "Unexpected", "detail" to error.detail)
    }

    private fun errorFromMap(m: Map<*, *>): BoardError = when (m.string("k")) {
        "NoToken" -> BoardError.NoToken
        "BadToken" -> BoardError.BadToken(m.long("status")!!.toInt())
        "NoAccess" -> BoardError.NoAccess
        "RateLimited" -> BoardError.RateLimited(m.long("retryAt")?.let(Instant::ofEpochMilli))
        "IssueGone" -> BoardError.IssueGone(m.long("n")!!.toInt())
        "Network" -> BoardError.Network
        "Timeout" -> BoardError.Timeout
        "Server" -> BoardError.Server(m.long("status")!!.toInt())
        "Unexpected" -> BoardError.Unexpected(m.string("detail") ?: "")
        else -> throw IllegalArgumentException("unknown error kind")
    }
}
