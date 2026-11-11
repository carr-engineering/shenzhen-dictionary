import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.components.resources)
            implementation(compose.foundation)
            implementation(compose.materialIconsExtended)
            implementation(compose.material3)
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.11.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.12.2")
            implementation("androidx.datastore:datastore-preferences:1.2.1")
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
        versionName = "2026.11.11-1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Datasets and SQLite packs are opened from extracted files; models are memory-mapped.
    androidResources { noCompress += listOf("tflite", "bin", "onnx", "opus", "m4a") }
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
