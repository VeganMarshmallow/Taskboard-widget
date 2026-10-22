package io.github.veganmarshmallow.taskboard

import android.graphics.Bitmap
import java.io.File

/** Saves PNGs in the app's private files dir; CI pulls them with `adb exec-out run-as` (debug build). */
object Screenshots {
    fun save(context: android.content.Context, name: String, bitmap: Bitmap): File {
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }
}
