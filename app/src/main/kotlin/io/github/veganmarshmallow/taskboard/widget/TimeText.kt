package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.util.Date

/** Clock times in the device's time zone and 12/24-hour preference. */
class TimeText(context: Context) {
    private val format = DateFormat.getTimeFormat(context.applicationContext)

    fun format(instant: Instant): String = format.format(Date.from(instant))
}
