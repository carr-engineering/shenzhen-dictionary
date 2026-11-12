import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything the Web build serves next to the app shell (never committed): one root per stageWeb* task.
val webStaticDir = layout.buildDirectory.dir("web/static")
val appVersionName = "2026.11.11-1"
val webStaticRoots = listOf("data", "opencc", "strokes", "sqlite3", "fonts").map { name -> webStaticDir.map { it.dir(name) } }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

// The app version for the Web build (Android reads it from BuildConfig).
val generateWebBuild by tasks.registering {
    val version = appVersionName
    val dir = layout.buildDirectory.dir("generated/webBuild/kotlin")
    inputs.property("version", version)
    outputs.dir(dir)
    doLast {
        val file = dir.get().file("com/szdict/app/WebBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.szdict.app\n\ninternal object WebBuild {\n    const val VERSION_NAME = \"$version\"\n}\n")
    }
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // Web (PROD-020): Compose on a canvas; `wasmJsBrowserDevelopmentRun` serves http://localhost:8081.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("szdict")
        browser {
            commonWebpackConfig {
                outputFileName = "szdict.js"
                devServer = (devServer ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()).apply {
                    port = 8081
                    open = false
                    // Datasets, packs, audio, OpenCC dictionaries, stroke data and the SQLite worker files.
                    static = (static ?: mutableListOf()).apply { webStaticRoots.forEach { add(it.get().asFile.absolutePath) } }
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            // OCR with per-character alternatives and handwriting recognition (bundled models).
            implementation(project(":recognition"))
            implementation(compose.components.resources)
            implementation(compose.foundation)
            implementation(compose.materialIconsExtended)
            implementation(compose.material3)
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.11.1")
            // Stroke order, practice quiz and stroke geometry (docs/04 MED-060…); published from the
            // sibling repo into local-maven by tools/publish-animator.sh.
            implementation("engineering.carr:shenzhen-hanzi-animator-compose:0.1.0")
            implementation("engineering.carr:shenzhen-hanzi-animator-bundled-data:0.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            // Script conversion and detection (ARCH-196); dictionaries are staged by stageOpenccData.
            implementation("io.github.opencckmp:opencc-core:0.1.0-SNAPSHOT")
            implementation("io.github.opencckmp:opencc-detect:0.1.0-SNAPSHOT")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        wasmJsMain { kotlin.srcDir(generateWebBuild) }
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
            // SQLite compiled to WebAssembly, run by the store worker (ARCH-026); copied by stageWebSqlite.
            implementation(npm("@sqlite.org/sqlite-wasm", "3.54.1-build1"))
        }
        androidMain.dependencies {
            // Voice destination: sherpa-onnx engine and the bundled speech model (ARCH-095).
            implementation(project(":voice"))
            implementation("androidx.activity:activity-compose:1.12.2")
            // OCR destination: camera preview/capture (CAM-020…) and EXIF orientation of picked images (CAM-041).
            implementation("androidx.camera:camera-core:1.5.3")
            implementation("androidx.camera:camera-camera2:1.5.3")
            implementation("androidx.camera:camera-lifecycle:1.5.3")
            implementation("androidx.camera:camera-view:1.5.3")
            implementation("androidx.exifinterface:exifinterface:1.4.1")
            implementation("androidx.datastore:datastore-preferences:1.2.1")
            implementation("androidx.glance:glance-appwidget:1.1.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
    }
}

android {
    namespace = "com.szdict.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.szdict.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = appVersionName
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Datasets and SQLite packs are opened from extracted files; models are memory-mapped.
    androidResources {
        noCompress += listOf("tflite", "bin", "onnx", "opus", "m4a", "hzb")
        // GLB-103: only the three UI languages ship; translations bundled by libraries are removed.
        // The locale config (res/xml/locales_config.xml) is maintained by hand, so it is not generated.
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

// Dataset, packs and audio come from the pipeline output (PROD-090); they are copied into the
// (gitignored) Android assets so the APK installs everything except the voice model (PROD-070).
val syncDatasetAssets by tasks.registering(Sync::class) {
    val out = rootProject.file("../pipeline/out")
    from(out) {
        include("mandarin.db", "mandarin-lm.db", "cantonese.db", "cantonese-lm.db")
        include("mandarin-widget.json", "cantonese-widget.json")
        include("packs/**")
        into("data")
    }
    into(layout.projectDirectory.dir("src/androidMain/assets"))
    preserve { include("models/**") }
}
tasks.named("preBuild") { dependsOn(syncDatasetAssets) }

// OpenCC dictionaries and configs for script conversion (ARCH-196, DATA-195): unpacked from the
// opencc-kmp data publication into generated assets (`opencc/…`), without the Jieba and Japanese
// resources the app never uses. The same directory is on the unit-test classpath for host tests.
val openccData by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies { add(openccData.name, "io.github.opencckmp:opencc-data:0.1.0-SNAPSHOT:resources@zip") }
val openccAssetsDir = layout.buildDirectory.dir("generated/opencc/assets")
val stageOpenccData by tasks.registering(Sync::class) {
    from({ openccData.files.map { zipTree(it) } }) {
        include("opencc/*.ocd2", "opencc/NOTICE", "opencc/LICENSE", "opencc/AUTHORS")
        listOf("t2s", "tw2s", "hk2s", "s2t", "tw2t", "hk2t", "s2hk", "t2hk", "s2tw", "t2tw", "s2twp")
            .forEach { include("opencc/$it.json") }
        exclude("opencc/JP*")
    }
    into(openccAssetsDir)
}
android.sourceSets.getByName("main").assets.srcDir(openccAssetsDir)
android.sourceSets.getByName("test").resources.srcDir(openccAssetsDir)
tasks.named("preBuild") { dependsOn(stageOpenccData) }

// ---- Web static files (ARCH-143, ARCH-145) ----
// `data/` is the pipeline output with a manifest of sizes and content tags: the store worker keeps
// each SQLite file in OPFS under its tag and downloads it again only when the tag changes.
val stageWebData by tasks.registering(Sync::class) {
    val out = rootProject.file("../pipeline/out")
    from(out) {
        include("mandarin.db", "mandarin-lm.db", "cantonese.db", "cantonese-lm.db")
        include("mandarin-widget.json", "cantonese-widget.json")
        include("packs/**")
        exclude("packs/packs-report.json")
        into("data")
    }
    into(webStaticRoots[0])
    preserve { include("data/manifest.json") }
    doLast {
        val dir = destinationDir.resolve("data")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "db" }.sortedBy { it.path }.toList()
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = files.joinToString(",") { f ->
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val tag = digest.digest().take(8).joinToString("") { b -> "%02x".format(b) }
            "\"${f.relativeTo(dir).invariantSeparatorsPath}\":{\"size\":${f.length()},\"tag\":\"$tag\"}"
        }
        dir.resolve("manifest.json").writeText("{\"files\":{$entries}}")
    }
}

// OpenCC dictionaries (ARCH-196), fetched on first use; `index.json` lists them.
val stageWebOpencc by tasks.registering(Sync::class) {
    from(stageOpenccData)
    into(webStaticRoots[1])
    doLast {
        val dir = destinationDir.resolve("opencc")
        val names = dir.listFiles().orEmpty().filter { it.extension == "json" || it.extension == "ocd2" }.map { it.name }.sorted()
        dir.resolve("index.json").writeText(names.joinToString(",", "[", "]") { "\"$it\"" })
    }
}

// Stroke graphics of the animator library's browser reader (`strokes/<shard>.hzb`).
val strokeWebData by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies { add(strokeWebData.name, "engineering.carr:shenzhen-hanzi-animator-bundled-data:0.1.0:web-data@zip") }
val stageWebStrokes by tasks.registering(Sync::class) {
    from({ strokeWebData.files.map { zipTree(it) } }) { include("strokes/*.hzb", "licenses/**") }
    into(webStaticRoots[2])
}

// sqlite-wasm's ES module and binary, loaded by the store worker (wasmJsMain/resources/sqlite-worker.js).
val stageWebSqlite by tasks.registering(Sync::class) {
    dependsOn(rootProject.tasks.named("kotlinWasmNpmInstall"))
    from(rootProject.layout.buildDirectory.dir("wasm/node_modules/@sqlite.org/sqlite-wasm/dist")) {
        include("index.mjs", "sqlite3.wasm", "sqlite3-opfs-async-proxy.js")
        into("sqlite3")
    }
    into(webStaticRoots[3])
}

// Optional: serve the CJK fallback font from the app's own origin instead of the font host
// (ARCH-005), e.g. `-Pszdict.web.cjkFont=$HOME/Library/Fonts/NotoSansCJKsc-Regular.otf` — for
// development without the network. The file is served as `fonts/NotoSansCJKsc-Regular.otf`.
val stageWebFont by tasks.registering(Sync::class) {
    val font = providers.gradleProperty("szdict.web.cjkFont").orNull
    if (font != null) from(font) { into("fonts"); rename { "NotoSansCJKsc-Regular.otf" } }
    into(webStaticRoots[4])
}

val stageWebStatic by tasks.registering {
    dependsOn(stageWebData, stageWebOpencc, stageWebStrokes, stageWebSqlite, stageWebFont)
}
tasks.matching { it.name == "wasmJsBrowserDevelopmentRun" || it.name == "wasmJsBrowserProductionRun" }.configureEach {
    dependsOn(stageWebStatic)
}
// The distribution carries the static files beside the app shell.
tasks.matching { it.name == "wasmJsBrowserDistribution" || it.name == "wasmJsBrowserDevelopmentExecutableDistribution" }.configureEach {
    dependsOn(stageWebStatic)
    webStaticRoots.forEach { (this as Copy).from(it) }
}
