package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateStore
import io.github.veganmarshmallow.taskboard.core.work.RefreshMark
import io.github.veganmarshmallow.taskboard.core.work.RefreshMarkCodec
import io.github.veganmarshmallow.taskboard.core.work.RefreshMarkStore
import io.github.veganmarshmallow.taskboard.core.work.Uptime
import io.github.veganmarshmallow.taskboard.core.work.UptimeInstant
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

/** The device's monotonic clock, moved by hand. [reboot] starts a new boot from (near) zero. */
class FakeUptime(@Volatile var boot: Int = 7, @Volatile var elapsedMs: Long = 3_600_000) : Uptime {
    fun advance(d: Duration) { elapsedMs += d.toMillis() }
    fun reboot(upFor: Duration = Duration.ofSeconds(30)) { boot++; elapsedMs = upFor.toMillis() }
    override fun now() = UptimeInstant(boot, elapsedMs)
}

/** A [MutableClock] and a [FakeUptime] that move together, like a phone whose wall clock is not touched. */
class FakeTime(val clock: MutableClock = MutableClock(), val uptime: FakeUptime = FakeUptime()) {
    fun advance(d: Duration) { clock.advance(d); uptime.advance(d) }
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
    var failLoads = false
    override fun load(): WidgetState {
        if (failLoads) throw IllegalStateException("unreadable")
        return state
    }
    override fun save(state: WidgetState) {
        if (failSaves) throw IllegalStateException("disk full")
        saves++
        this.state = state
    }
}
