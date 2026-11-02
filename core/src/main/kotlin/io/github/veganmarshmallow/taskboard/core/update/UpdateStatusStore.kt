package io.github.veganmarshmallow.taskboard.core.update

/** Where the last update check is cached (the app keeps it in SharedPreferences). */
interface UpdateStatusStore {
    fun load(): UpdateStatus

    fun save(status: UpdateStatus)
}
