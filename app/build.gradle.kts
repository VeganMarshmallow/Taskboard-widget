plugins {
    id("com.android.application")
    kotlin("android")
}

// The release key is never in the repo (#27). CI decodes it from the SIGNING_KEYSTORE_B64 secret
// into a temp file and passes its path and password through the environment. Every published build
// must be signed with this same key so new APKs install over old ones. Without it (a fork, or a
// local build), release falls back to the debug key, and CI doesn't publish that APK.
val signingStoreFile = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseKey = signingStoreFile != null && signingStorePassword != null && file(signingStoreFile).isFile

// CI passes -PversionCode=<github.run_number> so each build installs as an update.
val ciVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "io.github.veganmarshmallow.taskboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.veganmarshmallow.taskboard"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "taskboard"
                keyPassword = signingStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        textReport = true
        // The token note links to github.com on purpose; no other remote resources are used.
        disable += setOf("GradleDependency", "OldTargetApi", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    // Instrumented tests only (emulator job in CI): view checks and the PR screenshots.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
}
