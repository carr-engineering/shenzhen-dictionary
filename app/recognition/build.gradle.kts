import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
}

// On-device text recognition (OCR with per-character alternatives) and handwriting recognition.
// commonMain holds the platform-neutral contract plus all geometry, image preparation and decoding;
// androidMain only runs the neural graphs (LiteRT) and loads the bundled model assets.
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        instrumentedTestVariant.sourceSetTree.set(org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree.test)
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("com.google.ai.edge.litert:litert:1.4.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("androidx.test:runner:1.7.0")
                implementation("androidx.test.ext:junit:1.3.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}

android {
    namespace = "com.szdict.recognition"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Models are memory-mapped straight out of the APK.
    androidResources { noCompress += listOf("tflite") }
}

tasks.withType<Test>().configureEach {
    systemProperty("recognition.goldenDir", file("src/androidUnitTest/resources/golden").absolutePath)
    systemProperty("recognition.modelDir", file("src/androidMain/assets/models").absolutePath)
    // Optional host harness: runs the real graphs through a Python LiteRT process (tools/host_nets.py).
    System.getenv("RECOGNITION_HOST_PYTHON")?.let { systemProperty("recognition.hostPython", it) }
    System.getenv("RECOGNITION_TEST_IMAGES")?.let { systemProperty("recognition.testImages", it) }
    System.getenv("RECOGNITION_HOST_OUT")?.let { systemProperty("recognition.hostOut", it) }
    maxHeapSize = "1g"
}

// Restores the model binaries that are too large for git (see tools/fetch_models.sh).
tasks.register<Exec>("fetchModels") {
    group = "recognition"
    description = "Copies the large model binaries from the local reverse-engineering workspace into assets."
    commandLine("sh", file("tools/fetch_models.sh").absolutePath)
}
