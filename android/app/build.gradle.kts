import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun runGit(vararg args: String): String? = try {
    ProcessBuilder("git", *args)
        .redirectErrorStream(true)
        .start()
        .apply { waitFor() }
        .inputStream.bufferedReader().readText().trim()
        .ifEmpty { null }
} catch (_: Exception) {
    null
}

android {
    namespace = "dev.apk.launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.apk.launcher"
        minSdk = 24
        targetSdk = 34
        // Автообновляемый ключ версии: code считается по коммитам, name берём из свежего тега.
        versionCode = runGit("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = runGit("describe", "--tags", "--abbrev=0")?.removePrefix("v")
            ?: "1.0.0"
        buildConfigField("String", "UPDATER_REPO", "\"sj0404-collab/apk-launcher\"")
    }
    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                props.load(FileInputStream(propsFile))
            }
            storeFile = rootProject.file(props.getProperty("storeFile", "keystore/release.jks"))
            storePassword = props.getProperty("storePassword") ?: "apk-launcher"
            keyAlias = props.getProperty("keyAlias") ?: "apk-launcher"
            keyPassword = props.getProperty("keyPassword") ?: "apk-launcher"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
}