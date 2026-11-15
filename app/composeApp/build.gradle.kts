import java.awt.Color
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.security.MessageDigest
import java.util.Locale
import javax.imageio.ImageIO
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything the Web build serves next to the app shell (never committed): one root per stageWeb* task.
val webStaticDir = layout.buildDirectory.dir("web/static")
val appVersionName = "2026.11.11-1"
val webStaticRoots = listOf("data", "opencc", "strokes", "sqlite3", "fonts", "models", "pwa", "cjk").map { name -> webStaticDir.map { it.dir(name) } }

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

// The app version for the iOS build (also CFBundleShortVersionString, see iosApp/Config.xcconfig).
val generateIosBuild by tasks.registering {
    val version = appVersionName
    val dir = layout.buildDirectory.dir("generated/iosBuild/kotlin")
    inputs.property("version", version)
    outputs.dir(dir)
    doLast {
        val file = dir.get().file("com/szdict/app/IosBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.szdict.app\n\ninternal object IosBuild {\n    const val VERSION_NAME = \"$version\"\n}\n")
    }
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // iOS (PROD-020): a static framework hosted by the Xcode project in ../iosApp, whose first build
    // phase runs `:composeApp:embedAndSignAppleFrameworkForXcode`.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            binaryOption("bundleId", "com.szdict.app.ComposeApp")
            isStatic = true
        }
    }

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
        iosMain { kotlin.srcDir(generateIosBuild) }
        iosMain.dependencies {
            // Voice destination: sherpa-onnx through its C API (ARCH-095).
            implementation(project(":speech"))
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

// ---- iOS bundle assets (ARCH-141) ----
// Everything the iOS app reads in place from its bundle, staged into one directory that the Xcode
// project copies as the folder `assets/` (never committed): `data/` (datasets, packs, recorded
// audio), `opencc/`, `strokes/` + `licenses/` (the animator's iOS stroke data), `models/` (OCR and
// handwriting graphs) and, when tools/fetch-voice-model.sh was run, `voice-model/`.
val strokeIosData by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies { add(strokeIosData.name, "engineering.carr:shenzhen-hanzi-animator-bundled-data:0.1.0:ios-data@zip") }
val iosAssetsDir = layout.buildDirectory.dir("ios/assets")
val stageIosAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../pipeline/out")) {
        include("mandarin.db", "mandarin-lm.db", "cantonese.db", "cantonese-lm.db")
        include("mandarin-widget.json", "cantonese-widget.json")
        include("packs/**")
        exclude("packs/packs-report.json")
        into("data")
    }
    from(stageOpenccData)
    from({ strokeIosData.files.map { zipTree(it) } }) { include("strokes/**", "licenses/**") }
    from(project(":recognition").file("src/androidMain/assets/models")) {
        include("ocr/*.tflite", "ocr/*-labels.txt", "ink/*.tflite", "ink/labels.txt", "ink/script-classes.txt")
        into("models")
    }
    // The voice model is bundled when present (VOICE-095: without it Voice shows its unavailable state).
    from(project(":voice").file("src/main/assets/voice-model")) {
        include("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
        into("voice-model")
    }
    into(iosAssetsDir)
}
tasks.matching { it.name == "embedAndSignAppleFrameworkForXcode" }.configureEach { dependsOn(stageIosAssets) }

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
        // ARCH-145: the dataset version, and per file its size and SHA-256 — the store worker verifies
        // every download against it and compares it with what is installed to find an update.
        val dir = destinationDir.resolve("data")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "db" }.sortedBy { it.path }.toList()
        val report = out.resolve("build-report.json").takeIf { it.isFile }?.let { groovy.json.JsonSlurper().parse(it) as? Map<*, *> }
        fun version(dataset: String): String? = ((report?.get(dataset) as? Map<*, *>)?.get("meta") as? Map<*, *>)?.get("dataset_version") as? String
        val versions = listOf("mandarin", "cantonese").associateWith { version(it) }
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
            val sha = digest.digest().joinToString("") { b -> "%02x".format(b) }
            val path = f.relativeTo(dir).invariantSeparatorsPath
            // Packs are shared by both modes and built with the Mandarin dataset.
            val fileVersion = versions[if (path.startsWith("cantonese")) "cantonese" else "mandarin"]
            "\"$path\":{\"size\":${f.length()},\"sha256\":\"$sha\"" + (fileVersion?.let { ",\"dataset_version\":\"$it\"" } ?: "") + "}"
        }
        val overall = versions.values.filterNotNull().distinct().joinToString("/").ifEmpty { "unversioned" }
        dir.resolve("manifest.json").writeText("{\"dataset_version\":\"$overall\",\"files\":{$entries}}")
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

// The CJK font (ARCH-005) is not built here: `tools/stage-web-fonts.py` cuts Noto Sans CJK into
// frequency subsets under `cjk/fonts/` (see stageWebStatic); without them the app fetches the
// complete font from the font host. Nothing stages a single complete font any more, so the
// `fonts` root stays empty.
val stageWebFont by tasks.registering(Sync::class) {
    into(webStaticRoots[4])
}

// Recognition models and their runtime for the recognition worker (ARCH-093, ARCH-197): the same
// graph files as the Android assets plus the TFLite Web runtime, with `models/manifest.json` naming
// each file's size and SHA-256 and the files of each set. The worker downloads a set on first use,
// verifies it against the manifest and keeps it in Cache Storage (CAM-134). The ink model and the
// runtime are not in git: `./gradlew :recognition:fetchModels` restores them.
val stageWebModels by tasks.registering(Sync::class) {
    val assets = project(":recognition").file("src/androidMain/assets/models")
    val runtime = project(":recognition").file("web-runtime")
    val required = listOf(assets.resolve("ink/chinese_lstm_4x192.tflite"), runtime.resolve("tflite_web_api_cc_simd.wasm"), runtime.resolve("tflite_web_api_cc_simd.js"))
    from(assets) {
        include("ocr/*.tflite", "ocr/*-labels.txt", "ink/*.tflite", "ink/labels.txt", "ink/script-classes.txt")
        into("models")
    }
    from(runtime) {
        include("tflite_web_api_cc_simd.js", "tflite_web_api_cc_simd.wasm")
        into("models/runtime")
    }
    into(webStaticRoots[5])
    preserve { include("models/manifest.json") }
    doFirst {
        required.filterNot { it.isFile }.let { missing ->
            if (missing.isNotEmpty()) throw GradleException("Missing ${missing.joinToString { it.name }}: run ./gradlew :recognition:fetchModels")
        }
    }
    doLast {
        val dir = destinationDir.resolve("models")
        val files = dir.walkTopDown().filter { it.isFile && it.name != "manifest.json" }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b) }
        val hashes = files.associate { it.relativeTo(dir).invariantSeparatorsPath to (it.length() to sha256(it.readBytes())) }
        val version = sha256(hashes.entries.joinToString("\n") { "${it.key}:${it.value.second}" }.toByteArray()).take(16)
        fun set(vararg folders: String) = hashes.keys.filter { path -> folders.any { path.startsWith("$it/") } }.joinToString(",") { "\"$it\"" }
        val entries = hashes.entries.joinToString(",") { (path, value) -> "\"$path\":{\"size\":${value.first},\"sha256\":\"${value.second}\"}" }
        dir.resolve("manifest.json").writeText("{\"version\":\"$version\",\"sets\":{\"ocr\":[${set("runtime", "ocr")}],\"ink\":[${set("runtime", "ink")}]},\"files\":{$entries}}")
    }
}

val stageWebStatic by tasks.registering {
    dependsOn(stageWebData, stageWebOpencc, stageWebStrokes, stageWebSqlite, stageWebFont, stageWebModels)
}
tasks.matching { it.name == "wasmJsBrowserDevelopmentRun" || it.name == "wasmJsBrowserProductionRun" }.configureEach {
    dependsOn(stageWebStatic)
}
// The distribution carries the static files beside the app shell.
tasks.matching { it.name == "wasmJsBrowserDistribution" || it.name == "wasmJsBrowserDevelopmentExecutableDistribution" }.configureEach {
    dependsOn(stageWebStatic)
    webStaticRoots.forEach { (this as AbstractCopyTask).from(it) }
}

// ---- PWA shell (ARCH-145, ARCH-199, GLB-114) ----
// The web app manifest, its icons (drawn from the vector drawables of the Android adaptive icon) and
// the static privacy page, rendered from the policy sources the apps bundle so the two can never differ.

/** One `<path>` of a vector drawable: its outline and a flat colour or a gradient (colour stops in path space). */
class IconPath(val data: String, val evenOdd: Boolean, val color: String?, val gradient: IconGradient?)
class IconGradient(val radial: Boolean, val numbers: Map<String, Float>, val stops: List<Pair<Float, String>>)
class IconGroup(val rotation: Double, val scale: Double, val translateX: Double, val translateY: Double, val paths: List<IconPath>)

/** The groups of a vector drawable (`M L H V C Z` paths, group rotation / scale / translation, gradients). */
fun readVectorDrawable(file: File): List<IconGroup> {
    val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    fun org.w3c.dom.Element.number(name: String, default: Double) = getAttribute("android:$name").toDoubleOrNull() ?: default
    fun org.w3c.dom.Element.children(tag: String): List<org.w3c.dom.Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().filter { it.tagName == tag }
    return document.documentElement.children("group").map { group ->
        IconGroup(
            group.number("rotation", 0.0), group.number("scaleX", 1.0), group.number("translateX", 0.0), group.number("translateY", 0.0),
            group.children("path").map { path ->
                val gradient = path.children("aapt:attr").firstOrNull()?.children("gradient")?.firstOrNull()?.let { g ->
                    IconGradient(
                        radial = g.getAttribute("android:type") == "radial",
                        numbers = listOf("startX", "startY", "endX", "endY", "centerX", "centerY", "gradientRadius")
                            .mapNotNull { name -> g.getAttribute("android:$name").toFloatOrNull()?.let { name to it } }.toMap(),
                        stops = g.children("item").map { it.getAttribute("android:offset").toFloat() to it.getAttribute("android:color") },
                    )
                }
                IconPath(
                    path.getAttribute("android:pathData"), path.getAttribute("android:fillType") == "evenOdd",
                    path.getAttribute("android:fillColor").takeIf { it.startsWith("#") }, gradient,
                )
            },
        )
    }
}

fun iconOutline(path: IconPath): Path2D.Double {
    val out = Path2D.Double(if (path.evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
    val tokens = Regex("[MLHVCZ]|-?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?").findAll(path.data).map { it.value }.toList()
    var i = 0
    var command = ' '
    fun next() = tokens[i++].toDouble()
    while (i < tokens.size) {
        if (tokens[i].length == 1 && tokens[i][0] in "MLHVCZ") command = tokens[i++][0]
        when (command) {
            'M' -> { out.moveTo(next(), next()); command = 'L' }
            'L' -> out.lineTo(next(), next())
            'H' -> out.lineTo(next(), out.currentPoint.y)
            'V' -> out.lineTo(out.currentPoint.x, next())
            'C' -> out.curveTo(next(), next(), next(), next(), next(), next())
            'Z' -> out.closePath()
            else -> throw GradleException("launcher icon: unsupported path command in ${path.data.take(40)}")
        }
    }
    return out
}

fun iconGroupTransform(group: IconGroup): AffineTransform = AffineTransform().apply {
    translate(group.translateX, group.translateY)
    rotate(Math.toRadians(group.rotation))
    scale(group.scale, group.scale)
}

/**
 * One icon of the Web set, as placed on the 1024-unit canvas of the drawables: the mark (the
 * foreground) scaled about its own centre to [markSize] of the square and centred, over the
 * background, the whole clipped to a rounded square of [corner] (a fraction of the side; 0 = none).
 */
class WebIcon(val foreground: List<IconGroup>, val background: List<IconGroup>, val markSize: Double, val corner: Double) {
    private val bounds = foreground.flatMap { group -> group.paths.map { iconGroupTransform(group).createTransformedShape(iconOutline(it)).bounds2D } }
        .reduce { a, b -> a.createUnion(b) }
    /** Fits the mark's bounding box, or — for a circular safe zone — its half diagonal, into [markSize]. */
    private val scale = markSize * 1024.0 / maxOf(bounds.width, bounds.height)
    val markTransform: AffineTransform = AffineTransform().apply {
        translate(512.0, 512.0)
        scale(scale, scale)
        translate(-bounds.centerX, -bounds.centerY)
    }

    private fun paint(path: IconPath): Paint {
        val g = path.gradient ?: return Color.decode(path.color ?: "#000000")
        val fractions = g.stops.map { it.first }.toFloatArray()
        // Java2D wants strictly increasing fractions.
        for (k in 1 until fractions.size) if (fractions[k] <= fractions[k - 1]) fractions[k] = fractions[k - 1] + 1e-4f
        val colors = g.stops.map { Color.decode(it.second) }.toTypedArray()
        val n = g.numbers
        return if (g.radial) RadialGradientPaint(n.getValue("centerX"), n.getValue("centerY"), n.getValue("gradientRadius"), fractions, colors)
        else LinearGradientPaint(n.getValue("startX"), n.getValue("startY"), n.getValue("endX"), n.getValue("endY"), fractions, colors)
    }

    fun png(size: Int, target: File) {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.scale(size / 1024.0, size / 1024.0)
        val square = RoundRectangle2D.Double(0.0, 0.0, 1024.0, 1024.0, 2048 * corner, 2048 * corner)
        fun draw(groups: List<IconGroup>, outer: AffineTransform?) = groups.forEach { group ->
            val saved = g.transform
            outer?.let(g::transform)
            g.transform(iconGroupTransform(group))
            group.paths.forEach { path -> g.paint = paint(path); g.fill(iconOutline(path)) }
            g.transform = saved
        }
        // The background is filled through the rounded outline itself, so its edge is anti-aliased.
        background.forEach { group -> group.paths.forEach { path -> g.paint = paint(path); g.fill(square) } }
        draw(foreground, markTransform)
        g.dispose()
        ImageIO.write(image, "png", target)
    }

    fun svg(): String {
        val defs = StringBuilder()
        val body = StringBuilder()
        var id = 0
        fun fill(path: IconPath): String {
            val g = path.gradient ?: return path.color ?: "#000"
            val name = "g${id++}"
            val n = g.numbers
            val stops = g.stops.joinToString("") { (offset, color) -> "<stop offset=\"$offset\" stop-color=\"$color\"/>" }
            defs.append(
                if (g.radial) "<radialGradient id=\"$name\" gradientUnits=\"userSpaceOnUse\" cx=\"${n["centerX"]}\" cy=\"${n["centerY"]}\" r=\"${n["gradientRadius"]}\">$stops</radialGradient>"
                else "<linearGradient id=\"$name\" gradientUnits=\"userSpaceOnUse\" x1=\"${n["startX"]}\" y1=\"${n["startY"]}\" x2=\"${n["endX"]}\" y2=\"${n["endY"]}\">$stops</linearGradient>",
            )
            return "url(#$name)"
        }
        fun number(value: Double) = "%.4f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
        fun matrix(t: AffineTransform) =
            "matrix(${number(t.scaleX)} ${number(t.shearY)} ${number(t.shearX)} ${number(t.scaleY)} ${number(t.translateX)} ${number(t.translateY)})"
        background.forEach { group ->
            group.paths.forEach { path -> body.append("<rect width=\"1024\" height=\"1024\" rx=\"${number(1024 * corner)}\" fill=\"${fill(path)}\"/>\n") }
        }
        foreground.forEach { group ->
            val transform = AffineTransform(markTransform).apply { concatenate(iconGroupTransform(group)) }
            body.append("<g transform=\"${matrix(transform)}\">\n")
            group.paths.forEach { path ->
                body.append("<path d=\"${path.data}\" fill=\"${fill(path)}\"${if (path.evenOdd) " fill-rule=\"evenodd\"" else ""}/>\n")
            }
            body.append("</g>\n")
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1024 1024\">\n<defs>$defs</defs>\n$body</svg>\n"
    }
}

val stageWebPwa by tasks.registering {
    val drawables = layout.projectDirectory.dir("src/androidMain/res/drawable")
    val iconForeground = drawables.file("ic_launcher_foreground.xml")
    val iconBackground = drawables.file("ic_launcher_background.xml")
    val policyDir = layout.projectDirectory.dir("src/commonMain/kotlin/com/szdict/app/feature/settings")
    val policySources = listOf("PrivacyPolicy.kt", "PrivacyPolicyEn.kt", "PrivacyPolicyZhHans.kt", "PrivacyPolicyZhHant.kt").map { policyDir.file(it) }
    // The app's English names (short for `short_name`, full for `name` and the privacy page title).
    val appNameSource = layout.projectDirectory.file("src/commonMain/kotlin/com/szdict/app/AppName.kt")
    val target = webStaticRoots[6]
    inputs.files(iconForeground, iconBackground)
    inputs.files(policySources)
    inputs.file(appNameSource)
    inputs.property("layout", 4)
    outputs.dir(target)
    doLast {
        val dir = target.get().asFile
        dir.deleteRecursively()
        dir.resolve("icons").mkdirs()

        // Icons, all square and drawn from the vectors at their own size (nothing is upscaled):
        // the tab and install icons are the mark filling a rounded square of the icon's paper; the
        // Apple icon is the same on a full square (iOS rounds it); the maskable icon is a full
        // square with the mark inside the central safe zone (a circle of 80 % of the side).
        System.setProperty("java.awt.headless", "true")
        val foreground = readVectorDrawable(iconForeground.asFile)
        val background = readVectorDrawable(iconBackground.asFile)
        val tab = WebIcon(foreground, background, markSize = 0.86, corner = 0.22)
        val apple = WebIcon(foreground, background, markSize = 0.80, corner = 0.0)
        val maskable = WebIcon(foreground, background, markSize = 0.66, corner = 0.0)
        dir.resolve("icons/favicon.svg").writeText(tab.svg())
        tab.png(32, dir.resolve("icons/favicon-32.png"))
        tab.png(192, dir.resolve("icons/icon-192.png"))
        tab.png(512, dir.resolve("icons/icon-512.png"))
        apple.png(180, dir.resolve("icons/apple-touch-icon.png"))
        maskable.png(192, dir.resolve("icons/icon-maskable-192.png"))
        maskable.png(512, dir.resolve("icons/icon-maskable-512.png"))

        val appNames = appNameSource.asFile.readText()
        fun appName(key: String) = Regex("const val $key = \"([^\"]+)\"").find(appNames)?.groupValues?.get(1)
            ?: throw GradleException("AppName.kt: $key not found")
        val shortName = appName("EN_SHORT")
        val fullName = appName("EN_FULL")
        dir.resolve("manifest.webmanifest").writeText(
            """{
  "name": "$fullName",
  "short_name": "$shortName",
  "description": "Offline Chinese–English dictionary for Mandarin and Cantonese",
  "id": "/",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "background_color": "#FAF7F1",
  "theme_color": "#FAF7F1",
  "lang": "en",
  "categories": ["education", "reference"],
  "icons": [
    { "src": "icons/favicon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any" },
    { "src": "icons/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any" },
    { "src": "icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" },
    { "src": "icons/icon-maskable-192.png", "sizes": "192x192", "type": "image/png", "purpose": "maskable" },
    { "src": "icons/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }
  ]
}
""",
        )

        // The privacy page: the literals of the three policy sources, in source order.
        fun literal(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    when (val e = text[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> { out.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                        else -> out.append(e)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
        fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val string = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        fun strings(text: String) = string.findAll(text).map { literal(it.groupValues[1]) }.toList()
        val updated = Regex("PRIVACY_LAST_UPDATED: String = \"([0-9-]+)\"").find(policySources[0].asFile.readText())?.groupValues?.get(1)
            ?: throw GradleException("PRIVACY_LAST_UPDATED not found in PrivacyPolicy.kt")
        val languages = listOf("en" to "English", "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文")
        val body = StringBuilder()
        var pageTitle = ""
        policySources.drop(1).forEachIndexed { index, file ->
            val text = file.asFile.readText()
            if (text.contains("\"\"\"") || Regex("\"\\s*\\+").containsMatchIn(text) || Regex("(?<!\\\\)\\$[a-zA-Z{]").containsMatchIn(text)) {
                throw GradleException("${file.asFile.name}: only plain string literals can be rendered into the privacy page")
            }
            val title = Regex("title = (\"(?:[^\"\\\\]|\\\\.)*\")").find(text)?.let { strings(it.groupValues[1]).single() }
            val lastUpdated = Regex("lastUpdated = (\"(?:[^\"\\\\]|\\\\.)*\")").find(text)?.let { strings(it.groupValues[1]).single() }
            val summaryStart = text.indexOf("summary = listOf(")
            val sectionsStart = text.indexOf("sections = listOf(")
            if (title == null || lastUpdated == null || summaryStart < 0 || sectionsStart < summaryStart) {
                throw GradleException("${file.asFile.name}: unexpected shape; update stageWebPwa with the policy sources")
            }
            val summary = strings(text.substring(summaryStart, sectionsStart))
            val sections = text.substring(sectionsStart).split("PolicySection(").drop(1).map { part ->
                val all = strings(part)
                all.first() to all.drop(1)
            }
            if (summary.isEmpty() || sections.isEmpty() || sections.any { it.second.isEmpty() }) {
                throw GradleException("${file.asFile.name}: no policy text found")
            }
            val (tag, _) = languages[index]
            if (index == 0) pageTitle = title
            body.append("<article id=\"$tag\" lang=\"$tag\">\n<h1>${html(title)}</h1>\n<p class=\"updated\">${html(lastUpdated)}</p>\n")
            summary.forEach { body.append("<p class=\"summary\">${html(it)}</p>\n") }
            sections.forEach { (heading, paragraphs) ->
                body.append("<h2>${html(heading)}</h2>\n")
                paragraphs.forEach { body.append("<p>${html(it)}</p>\n") }
            }
            body.append("</article>\n")
        }
        val nav = languages.joinToString(" · ") { (tag, name) -> "<a href=\"#$tag\" lang=\"$tag\" hreflang=\"$tag\">$name</a>" }
        dir.resolve("privacy.html").writeText(
            """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>${html(pageTitle)} — ${html(fullName)}</title>
<meta name="description" content="Privacy policy of Shenzhen Dictionary (SZ Dict, 深圳词典): no accounts, no advertising, no telemetry.">
<link rel="canonical" href="https://szdict.com/privacy">
<meta name="robots" content="index, follow">
<meta name="date" content="$updated">
<link rel="icon" type="image/svg+xml" href="/icons/favicon.svg">
<link rel="icon" type="image/png" sizes="32x32" href="/icons/favicon-32.png">
<style>
:root { --bg: #faf7f1; --fg: #2b2925; --muted: #6b665c; --accent: #a53a2b; --border: #ddd7c9; }
@media (prefers-color-scheme: dark) { :root { --bg: #17140f; --fg: #ece8df; --muted: #a39d90; --accent: #e08a78; --border: #37342f; } }
html { background: var(--bg); color: var(--fg); font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans", "PingFang SC", "PingFang HK", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif; }
body { max-width: 44rem; margin: 0 auto; padding: 1.5rem 1.25rem 4rem; }
header { display: flex; flex-wrap: wrap; gap: .5rem 1.5rem; justify-content: space-between; align-items: baseline; padding-bottom: 1rem; border-bottom: 1px solid var(--border); }
header .app { font-weight: 600; font-size: 1.15rem; color: var(--fg); text-decoration: none; }
a { color: var(--accent); }
article { padding-top: 1rem; border-bottom: 1px solid var(--border); padding-bottom: 1.5rem; }
h1 { font-size: 1.6rem; margin: 1rem 0 .25rem; }
h2 { font-size: 1.1rem; margin: 1.6rem 0 .4rem; }
p { margin: .6rem 0; }
.updated { color: var(--muted); margin-top: 0; }
.summary { font-size: 1.05rem; }
</style>
</head>
<body>
<header><a class="app" href="/">${html(fullName)}</a><nav aria-label="Languages">$nav</nav></header>
<main>
$body</main>
</body>
</html>
""",
        )
    }
}
// The iOS app icon (1024 px, full square: iOS masks it), drawn from the same vectors. Run once after
// the launcher drawables change: `./gradlew :composeApp:generateIosAppIcon`; the PNG is committed.
val generateIosAppIcon by tasks.registering {
    val drawables = layout.projectDirectory.dir("src/androidMain/res/drawable")
    val iconForeground = drawables.file("ic_launcher_foreground.xml")
    val iconBackground = drawables.file("ic_launcher_background.xml")
    val target = rootProject.layout.projectDirectory.file("iosApp/SZDict/Assets.xcassets/AppIcon.appiconset/icon-1024.png")
    inputs.files(iconForeground, iconBackground)
    outputs.file(target)
    doLast {
        System.setProperty("java.awt.headless", "true")
        val icon = WebIcon(readVectorDrawable(iconForeground.asFile), readVectorDrawable(iconBackground.asFile), markSize = 0.80, corner = 0.0)
        val file = target.asFile
        file.parentFile.mkdirs()
        icon.png(1024, file)
        // App Store icons must be opaque: flatten onto the background's own pixels (already full square).
        val image = ImageIO.read(file)
        val opaque = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        opaque.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        ImageIO.write(opaque, "png", file)
    }
}

tasks.named("stageWebStatic") {
    dependsOn(stageWebPwa)
    // `cjk/fonts/` holds the CJK font subsets when tools/stage-web-fonts.sh was run (ARCH-005); empty otherwise.
    val cjk = webStaticRoots[7]
    doLast {
        // The app always finds an index: `{}` says nothing is staged.
        val index = cjk.get().asFile.resolve("fonts/index.json")
        if (!index.isFile) {
            index.parentFile.mkdirs()
            index.writeText("{}")
        }
    }
}

// The distribution's service worker names the files of this build: the shell it keeps for offline
// use (code, workers, SQLite, the privacy page — never the datasets, media, fonts or models) under a
// version derived from their content.
tasks.matching { it.name == "wasmJsBrowserDistribution" }.configureEach {
    doLast {
        val dist = layout.buildDirectory.dir("dist/wasmJs/productionExecutable").get().asFile
        val lazyRoots = setOf("data", "opencc", "strokes", "fonts", "models", "licenses")
        val shell = dist.walkTopDown().filter { it.isFile }.map { it.relativeTo(dist).invariantSeparatorsPath }.filter { path ->
            // Of the icons only the tab icon is part of a start; the installer fetches the others itself.
            path.substringBefore('/') !in lazyRoots && path != "sw.js" && (!path.startsWith("icons/") || path == "icons/favicon.svg" || path == "icons/favicon-32.png") &&
                !path.endsWith(".map") && !path.endsWith(".LICENSE.txt") && !path.endsWith(".br") && !path.endsWith(".gz")
        }.sorted().toList()
        val digest = MessageDigest.getInstance("SHA-256")
        shell.forEach { path ->
            digest.update(path.toByteArray())
            digest.update(dist.resolve(path).readBytes())
        }
        val version = digest.digest().take(8).joinToString("") { b -> "%02x".format(b) }
        val worker = dist.resolve("sw.js")
        val marker = "const BUILD = null;"
        val source = worker.readText()
        if (marker !in source) throw GradleException("sw.js: the build marker is missing")
        worker.writeText(source.replace(marker, "const BUILD = {\"version\":\"$version\",\"shell\":[${shell.joinToString(",") { "\"$it\"" }}]};"))
    }
}
