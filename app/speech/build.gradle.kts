import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Speech recognition engine for the Voice destination on iOS (ARCH-095): sherpa-onnx through its C
// API (cinterop over the static xcframework) and the streaming Paraformer trilingual model. The
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
            // Used when Kotlin/Native links a binary itself (tests, a dynamic framework). A static
            // Kotlin framework is not linked: the app target then links the same two xcframeworks.
            linkerOpts(
                "-F${sherpa.absolutePath}", "-framework", "SherpaOnnxC",
                "-F${ort.absolutePath}", "-framework", "onnxruntime",
            )
        }
    }

    sourceSets {
        // The engine uses the cinterop bindings, which exist per target only (no commonisation):
        // its sources go to each target's own source set, the stub to the shared one.
        if (hasSherpa) {
            slices.keys.forEach { getByName("${it}Main").kotlin.srcDir("src/sherpa/kotlin") }
        } else {
            iosMain { kotlin.srcDir("src/stub/kotlin") }
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
