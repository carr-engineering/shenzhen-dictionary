// Kotlin 2.4 requires R8 9.1.29 or newer; AGP 8.13 bundles an older shrinker.
buildscript {
    repositories {
        ivy {
            url = uri("https://storage.googleapis.com/r8-releases/raw")
            patternLayout { artifact("[revision]/[artifact].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.android.tools", "r8") }
        }
        google()
        mavenCentral()
    }
    dependencies { classpath("com.android.tools:r8:9.1.29") }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
}
