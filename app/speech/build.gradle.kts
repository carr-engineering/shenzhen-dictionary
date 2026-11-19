import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Speech recognition engine for the Voice destination on iOS (ARCH-095): sherpa-onnx through its C
// API (cinterop over the static xcframework) and the streaming Paraformer bilingual model. The
// xcframeworks are large binaries fetched by tools/fetch-voice-model.sh, never committed. Without
// them the module compiles a stub engine, `Speech.AVAILABLE` is false and Voice reports itself
// unavailable; the rest of the app is unaffected (ARCH-122).
val frameworks = file("ios-frameworks")
val sherpaFramework = frameworks.resolve("sherpa-onnx.xcframework")
val ortFramework = frameworks.resolve("onnxruntime.xcframework")
val slices = mapOf("iosArm64" to "ios-arm64", "iosSimulatorArm64" to "ios-arm64_x86_64-simulator")
// -Pspeech.stub builds the stub even when the frameworks are there.
val hasSherpa = !providers.gradleProperty("speech.stub").isPresent && slices.values.all {
    sherpaFramework.resolve("$it/SherpaOnnxC.framework/SherpaOnnxC").isFile &&
        ortFramework.resolve("$it/onnxruntime.framework/onnxruntime").isFile
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        if (!hasSherpa) return@configureEach
        val slice = slices.getValue(name)
        val sherpa = sherpaFramework.resolve(slice)
        val ort = ortFramework.resolve(slice)
        compilations.getByName("main").cinterops.create("sherpa") {
            definitionFile.set(file("src/nativeInterop/cinterop/sherpa.def"))
            includeDirs(sherpa.resolve("SherpaOnnxC.framework/Headers"))
        }
        // The .def names the frameworks; their per-target search paths are given to whatever
        // Kotlin/Native links itself (this module's tests). A module that links a test binary or a
        // dynamic framework on top of :speech passes the same two -F paths; a static Kotlin
        // framework is not linked at all: the app target then links the two xcframeworks.
        binaries.all { linkerOpts("-F${sherpa.absolutePath}", "-F${ort.absolutePath}") }
    }

    sourceSets {
        // The engine uses the cinterop bindings, which exist per target only (no commonisation):
        // the shared source set declares it (`expect`), each target's own source set provides it.
        val engine = if (hasSherpa) "src/sherpa/kotlin" else "src/stub/kotlin"
        slices.keys.forEach { getByName("${it}Main").kotlin.srcDir(engine) }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The simulator test decodes real recordings with the real model, read from the host file system
// (both fetched by tools/fetch-voice-model.sh into :voice); it skips itself when they are absent.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_SPEECH_MODEL_DIR", rootProject.file("voice/src/main/voice-assets/voice-model").absolutePath)
    environment("SIMCTL_CHILD_SPEECH_WAV_DIR", rootProject.file("voice/src/androidTest/assets/voice-test-bilingual").absolutePath)
}
