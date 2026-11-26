import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

if (System.getProperty("os.name").lowercase().contains("win") &&
    rootDir.absolutePath.contains("OneDrive", ignoreCase = true)
) {
    val localBase = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "helix-build")
    allprojects {
        layout.buildDirectory.set(File(localBase, if (path == ":") "root" else name))
    }
}
