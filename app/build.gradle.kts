import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// Signing config is committed on purpose (private repo, personal app): every CI build must be
// signed with the same key so new APKs install over old ones. See the PR for the tradeoff.
val signing = Properties().apply { file("signing.properties").inputStream().use(::load) }

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
    }

    signingConfigs {
        create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
}
