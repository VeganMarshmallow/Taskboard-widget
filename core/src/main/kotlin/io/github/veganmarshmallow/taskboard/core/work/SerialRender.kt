package io.github.veganmarshmallow.taskboard.core.work

/**
 * Runs read-then-draw steps one at a time, so draws reach the screen in the order their reads
 * happened and the last draw always carries the newest values.
 *
 * Task #33: the widget's header (and so the tab highlight) is pushed with whatever tab was read
 * when its views were built, on whichever thread was drawing. A redraw on a background thread (a
 * refresh finishing, the sweep, the update dot) could read the old tab, be overtaken by the tap's
 * draw, and land last: the old highlight stayed while the list, which re-reads the tab itself,
 * showed the new one. With every read and push inside one lock that order can no longer happen.
 *
 * [read] and [draw] must not block on slow work (network); they only read local state and hand
 * views to the system. The widget's receivers draw on the main thread, so a tap may wait for one
 * background draw to finish (milliseconds), never for a refresh. Nothing inside the gate waits for
 * another thread, so it cannot deadlock, and a nested run on the same thread re-enters the lock.
 */
class SerialRender {
    private val lock = Any()

    fun <T> run(read: () -> T, draw: (T) -> Unit) {
        synchronized(lock) { draw(read()) }
    }
}
