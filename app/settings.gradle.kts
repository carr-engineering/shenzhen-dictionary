pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Locally published sibling libraries (opencc-kmp, shenzhen-hanzi-animator); see app/README.
        maven { url = uri(rootDir.resolve("local-maven")) }
        // opencc-kmp (ARCH-196) is consumed from its own local publication until it is on Maven Central.
        maven {
            url = uri(rootDir.resolve("../../opencc-kmp/.cache/maven"))
            content { includeGroup("io.github.opencckmp") }
        }
        google()
        mavenCentral()
        // Kotlin/Wasm toolchain (Node.js, Yarn, Binaryen): the Kotlin plugin would add these per
        // project, which FAIL_ON_PROJECT_REPOS forbids.
        ivy("https://nodejs.org/dist/") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "ShenzhenDictionary"
include(":core")
include(":composeApp")
include(":recognition")
include(":voice")
