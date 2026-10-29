pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.ghostscript.com") {
            content { includeGroup("com.artifex.mupdf") }
        }
    }
}

rootProject.name = "PaperForge"

include(":app", ":office-engine", ":ocr-paddle", ":format-engine", ":jpeg2000", ":opencv-mobile")

include(":archive-core")
include(":network-engine")

include(":full-app", ":bundled-office", ":bundled-formats", ":bundled-network")
project(":bundled-office").apply { projectDir = file("office-engine"); buildFileName = "bundled.gradle.kts" }
project(":bundled-formats").apply { projectDir = file("format-engine"); buildFileName = "bundled.gradle.kts" }
project(":bundled-network").apply { projectDir = file("network-engine"); buildFileName = "bundled.gradle.kts" }
