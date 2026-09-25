import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun runGit(vararg args: String): String? = try {
    val process = ProcessBuilder("git", *args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() == 0 && output.isNotEmpty()) output else null
} catch (_: Exception) {
    null
}

private fun versionCodeFromTag(tag: String?): Int? {
    val match = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(tag.orEmpty())
        ?: return null
    val major = match.groupValues[1].toLongOrNull() ?: return null
    val minor = match.groupValues[2].toLongOrNull() ?: 0L
    val patch = match.groupValues[3].toLongOrNull() ?: 0L
    if (major > Int.MAX_VALUE.toLong() / 1_000_000L || minor !in 0..999L || patch !in 0..999L) {
        return null
    }
    val code = major * 1_000_000L + minor * 1_000L + patch
    return code.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}

fun gitVersionCode(): Int {
    val tag = runGit("describe", "--tags", "--abbrev=0")
    val base = versionCodeFromTag(tag)
    val commits = runGit(
        "rev-list",
        "--count",
        if (tag == null) "HEAD" else "$tag..HEAD",
    )?.toLongOrNull()
    if (base != null) {
        return (base + (commits ?: 0L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return (commits ?: 1L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

val releasePropsFile = rootProject.file("keystore.properties")

gradle.taskGraph.whenReady {
    val releaseArtifactRequested = allTasks.any {
        it.project == project && it.name == "packageRelease"
    }
    if (releaseArtifactRequested && !releasePropsFile.exists()) {
        throw GradleException(
            "Release signing key is missing; create android/keystore.properties before packaging"
        )
    }
}

android {
    namespace = "dev.apk.launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.apk.launcher"
        minSdk = 24
        targetSdk = 34
        versionCode = gitVersionCode()
        versionName = runGit("describe", "--tags", "--abbrev=0")?.removePrefix("v")
            ?: "1.0.0"
        buildConfigField("String", "UPDATER_REPO", "\"sj0404-collab/apk-launcher\"")
    }
    signingConfigs {
        if (releasePropsFile.exists()) {
            val props = Properties()
            releasePropsFile.inputStream().use { props.load(it) }
            val storeFile = rootProject.file(
                props.getProperty("storeFile")
                    ?: throw GradleException("keystore.properties: storeFile is required")
            )
            if (!storeFile.isFile) {
                throw GradleException("keystore.properties: storeFile does not exist: $storeFile")
            }
            create("release") {
                this.storeFile = storeFile
                storePassword = props.getProperty("storePassword")
                    ?: throw GradleException("keystore.properties: storePassword is required")
                keyAlias = props.getProperty("keyAlias")
                    ?: throw GradleException("keystore.properties: keyAlias is required")
                keyPassword = props.getProperty("keyPassword")
                    ?: throw GradleException("keystore.properties: keyPassword is required")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
}
