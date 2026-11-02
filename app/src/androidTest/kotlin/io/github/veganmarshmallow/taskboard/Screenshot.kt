package io.github.veganmarshmallow.taskboard

/**
 * Marks a test class whose tests capture PR screenshots. CI's PNG pass (`.github/scripts/emulator-tests.sh`)
 * runs exactly the classes carrying it (`-e annotation`), so a feature adds its own screenshot class
 * without editing a shared one or the script. The script fails if a class saves PNGs without it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Screenshot
