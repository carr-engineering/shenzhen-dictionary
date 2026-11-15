import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
}

val onnxRuntime = "1.23.2"

// On-device text recognition (OCR with per-character alternatives) and handwriting recognition.
// commonMain holds the platform-neutral contract plus all geometry, image preparation and decoding;
// androidMain only runs the neural graphs (LiteRT) and loads the bundled model assets.
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        instrumentedTestVariant.sourceSetTree.set(org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree.test)
    }

    // Web: the same pipeline; the graphs run in a worker with the TFLite Web runtime (wasmJsMain).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    // iOS: the same pipeline; the graphs run through the TensorFlow Lite C API (iosMain).
    // The runtime is the static TensorFlowLiteC.xcframework in ios-frameworks/ (tools/fetch_ios_frameworks.sh).
    // The cinterop klib records `-framework TensorFlowLiteC -lc++`; the framework search path is
    // added here for this module's own binaries (tests). An app linking a static Kotlin framework
    // links the xcframework itself.
    listOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-arm64_x86_64-simulator").forEach { (target, slice) ->
        val frameworks = file("ios-frameworks/TensorFlowLiteC.xcframework/$slice")
        target.compilations.getByName("main").cinterops.create("tflite") {
            definitionFile.set(file("src/nativeInterop/cinterop/tflite.def"))
            packageName("com.szdict.recognition.tflite")
            compilerOpts("-F${frameworks.absolutePath}")
        }
        target.binaries.all { linkerOpts("-F${frameworks.absolutePath}") }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("com.google.ai.edge.litert:litert:1.4.2")
            // OCR graphs (ocr2). 1.23.2 is the last release with a 19 MB arm64 library; later ones
            // are 26 to 36 MB and, from 1.32, carry a telemetry client.
            implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntime")
        }
        val androidUnitTest by getting {
            dependencies {
                // The same Java API with desktop libraries: the host tests run the real graphs.
                implementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")
            }
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
    androidResources { noCompress += listOf("tflite", "onnx") }
}

tasks.withType<Test>().configureEach {
    systemProperty("recognition.goldenDir", file("src/androidUnitTest/resources/golden").absolutePath)
    systemProperty("recognition.modelDir", file("src/androidMain/assets/models").absolutePath)
    // Optional host harness: runs the real graphs through a Python LiteRT process (tools/host_nets.py).
    System.getenv("RECOGNITION_HOST_PYTHON")?.let { systemProperty("recognition.hostPython", it) }
    System.getenv("RECOGNITION_TEST_IMAGES")?.let { systemProperty("recognition.testImages", it) }
    System.getenv("RECOGNITION_HOST_OUT")?.let { systemProperty("recognition.hostOut", it) }
    // Optional accuracy evaluation over <name>.png|jpg + <name>.txt pairs (tools/make_eval_set.py).
    System.getenv("RECOGNITION_EVAL_DIR")?.let { systemProperty("recognition.evalDir", it) }
    // Benchmark run of the Android/iOS OCR pipeline on the host (Ocr2BenchmarkRun).
    for (name in listOf("RECOGNITION_OCR2_IMAGES", "RECOGNITION_OCR2_OUT", "RECOGNITION_OCR2_OPTIONS")) {
        System.getenv(name)?.let { environment(name, it); outputs.upToDateWhen { false } }
    }
    // Host-harness runs read images from outside the project: never treat them as up to date.
    if (System.getenv("RECOGNITION_HOST_PYTHON") != null) outputs.upToDateWhen { false }
    maxHeapSize = "2g"
}

// Restores the TensorFlow Lite C runtime of the iOS build (see tools/fetch_ios_frameworks.sh); a no-op once in place.
val fetchIosFrameworks = tasks.register<Exec>("fetchIosFrameworks") {
    group = "recognition"
    description = "Restores TensorFlowLiteC.xcframework (pinned URL and SHA-256) into ios-frameworks/."
    commandLine("sh", file("tools/fetch_ios_frameworks.sh").absolutePath)
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach { dependsOn(fetchIosFrameworks) }

// The iOS simulator tests run the real graphs on the reference images and drawings, read from the
// project directory (the simulator shares the host file system).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_RECOGNITION_MODEL_DIR", file("src/androidMain/assets/models").absolutePath)
    environment("SIMCTL_CHILD_RECOGNITION_GOLDEN_DIR", file("src/androidUnitTest/resources/golden").absolutePath)
    environment("SIMCTL_CHILD_RECOGNITION_TESTDATA_DIR", file("src/androidInstrumentedTest/assets/testdata").absolutePath)
}

// Restores the OCR models of the Android and iOS apps (see tools/fetch_ocr2_models.sh); a no-op once in place.
tasks.register<Exec>("fetchOcr2Models") {
    group = "recognition"
    description = "Restores the PP-OCR detector, classifier and recogniser (pinned URLs and SHA-256) into assets/models/ocr2/."
    commandLine("sh", file("tools/fetch_ocr2_models.sh").absolutePath)
}

// Restores the model binaries that are too large for git (see tools/fetch_models.sh).
tasks.register<Exec>("fetchModels") {
    group = "recognition"
    description = "Restores the large model binaries (and the TFLite Web runtime of the browser build) that are not in git."
    commandLine("sh", file("tools/fetch_models.sh").absolutePath)
}
