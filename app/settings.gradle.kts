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
        google()
        mavenCentral()
    }
}

rootProject.name = "ShenzhenDictionary"
include(":core")
include(":composeApp")
