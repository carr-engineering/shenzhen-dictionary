plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Speech recognition engine for the Voice destination on iOS (ARCH-095): sherpa-onnx through its C API.
kotlin {
    iosArm64()
    iosSimulatorArm64()
}
