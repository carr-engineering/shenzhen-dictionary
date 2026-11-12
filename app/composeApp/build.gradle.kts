import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.security.MessageDigest
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
// The web app manifest, its icons (from the Android launcher icon) and the static privacy page,
// rendered from the policy sources the apps bundle so the two can never differ.
val stageWebPwa by tasks.registering {
    val launcherIcon = layout.projectDirectory.file("src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png")
    val policyDir = layout.projectDirectory.dir("src/commonMain/kotlin/com/szdict/app/feature/settings")
    val policySources = listOf("PrivacyPolicy.kt", "PrivacyPolicyEn.kt", "PrivacyPolicyZhHans.kt", "PrivacyPolicyZhHant.kt").map { policyDir.file(it) }
    val target = webStaticRoots[6]
    inputs.file(launcherIcon)
    inputs.files(policySources)
    inputs.property("layout", 3)
    outputs.dir(target)
    doLast {
        val dir = target.get().asFile
        dir.deleteRecursively()
        dir.resolve("icons").mkdirs()

        // Icons: the launcher bitmap as is, scaled, and on the icon's own paper colour for the
        // opaque (Apple) and maskable (Android adaptive) variants.
        System.setProperty("java.awt.headless", "true")
        val source = ImageIO.read(launcherIcon.asFile)
        fun icon(name: String, size: Int, inset: Double, background: Color?) {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            if (background != null) {
                g.color = background
                g.fillRect(0, 0, size, size)
            }
            val edge = Math.round(size * inset).toInt()
            g.drawImage(source, edge, edge, size - 2 * edge, size - 2 * edge, null)
            g.dispose()
            ImageIO.write(image, "png", dir.resolve("icons/$name"))
        }
        val paper = Color(0xF4, 0xEF, 0xE3)
        icon("icon-192.png", 192, 0.0, null)
        icon("icon-512.png", 512, 0.0, null)
        icon("icon-maskable-512.png", 512, 0.1, paper)
        icon("apple-touch-icon.png", 180, 0.0, paper)
        icon("favicon-48.png", 48, 0.0, null)

        dir.resolve("manifest.webmanifest").writeText(
            """{
  "name": "SZ Dict",
  "short_name": "SZ Dict",
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
    { "src": "icons/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any" },
    { "src": "icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" },
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
<title>${html(pageTitle)} — SZ Dict</title>
<meta name="description" content="Privacy policy of Shenzhen Dictionary (SZ Dict, 深圳词典): no accounts, no advertising, no telemetry.">
<link rel="canonical" href="https://szdict.com/privacy">
<meta name="robots" content="index, follow">
<meta name="date" content="$updated">
<link rel="icon" type="image/png" href="/icons/favicon-48.png">
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
<header><a class="app" href="/">SZ Dict</a><nav aria-label="Languages">$nav</nav></header>
<main>
$body</main>
</body>
</html>
""",
        )
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
            path.substringBefore('/') !in lazyRoots && path != "sw.js" && (!path.startsWith("icons/") || path == "icons/favicon-48.png") &&
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
