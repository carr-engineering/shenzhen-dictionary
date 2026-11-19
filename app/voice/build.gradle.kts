import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Speech recognition engine for the Voice destination (ARCH-095): sherpa-onnx (JNI, onnxruntime
// linked statically) and the streaming Paraformer bilingual model. The library and the model are
// large binaries fetched by tools/fetch-voice-model.sh, never committed. Without the library the
// module compiles a stub engine and Voice reports itself unavailable; the rest of the app is
// unaffected (ARCH-122).
val sherpaJar = file("libs/sherpa-onnx-classes.jar")
val sherpaNative = file("src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so")
val hasSherpa = sherpaJar.exists() && sherpaNative.exists()
val voiceAssets = file("src/main/voice-assets")
val modelDir = voiceAssets.resolve("voice-model")
val modelFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")

android {
    namespace = "com.szdict.voice"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").kotlin.srcDir(if (hasSherpa) "src/sherpa/kotlin" else "src/stub/kotlin")
    // Use a separate asset root so an older trilingual download cannot enter the APK.
    sourceSets.getByName("main").assets.setSrcDirs(listOf(voiceAssets))
    // The model is handed to the native runtime straight out of the APK.
    androidResources { noCompress += listOf("onnx") }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    if (hasSherpa) implementation(files(sherpaJar))
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// VOICE-023: a direct APK without the model is a build error. CI passes -PrequireVoiceModel; a
// developer build only warns, and Voice then shows itself unavailable.
val checkVoiceModel by tasks.registering {
    val missing = modelFiles.filter { !modelDir.resolve(it).isFile }
    val library = hasSherpa
    val strict = providers.gradleProperty("requireVoiceModel").isPresent
    doLast {
        val problems = buildList {
            if (!library) add("the sherpa-onnx library")
            if (missing.isNotEmpty()) add("voice model files $missing")
        }
        if (problems.isNotEmpty()) {
            val message = "Voice: missing ${problems.joinToString(" and ")}; run app/tools/fetch-voice-model.sh"
            if (strict) throw GradleException(message) else logger.warn("WARNING: $message")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkVoiceModel) }
