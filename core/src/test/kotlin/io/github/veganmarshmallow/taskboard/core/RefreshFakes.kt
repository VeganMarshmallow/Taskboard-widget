package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateStore
import io.github.veganmarshmallow.taskboard.core.work.RefreshMark
import io.github.veganmarshmallow.taskboard.core.work.RefreshMarkCodec
import io.github.veganmarshmallow.taskboard.core.work.RefreshMarkStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests move by hand. */
class MutableClock(@Volatile var now: Instant = NOW) : Clock() {
    fun advance(d: Duration) { now = now.plus(d) }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

/**
 * Stores backed by text, like SharedPreferences: a new tracker or runner built on the same
 * "disk" behaves like the app after its process was killed and restarted.
 */
class TextMarkStore : RefreshMarkStore {
    @Volatile var text: String? = null
    override fun load(): RefreshMark? = RefreshMarkCodec.decode(text)
    override fun save(mark: RefreshMark?) { text = mark?.let(RefreshMarkCodec::encode) }
}

class MemoryStateStore(@Volatile var state: WidgetState = WidgetState.INITIAL) : WidgetStateStore {
    var saves = 0
    var failSaves = false
    override fun load(): WidgetState = state
    override fun save(state: WidgetState) {
        if (failSaves) throw IllegalStateException("disk full")
        saves++
        this.state = state
    }
}
