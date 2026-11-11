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
    }
}

rootProject.name = "ShenzhenDictionary"
include(":core")
include(":composeApp")
