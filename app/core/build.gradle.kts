import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Platform-shared dictionary engine, store, domain use cases and state reduction (ARCH-003, ARCH-011).
// No UI or platform SDK dependencies in commonMain. Host tests run on the JVM target.
kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("androidx.sqlite:sqlite:2.7.1")
            implementation("androidx.sqlite:sqlite-bundled:2.7.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "com.szdict.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    // Engine contract tests read the pipeline output (fixture and, when present, the real datasets).
    systemProperty("szdict.datasetDir", rootProject.file("../pipeline/out").absolutePath)
    maxHeapSize = "2g"
}
