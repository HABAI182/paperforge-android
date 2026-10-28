import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.paperforge.formats"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.paperforge.formats"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.6.1-dev"
        ndk {
            abiFilters +=
                providers.gradleProperty("paperforgeAbis").orNull?.split(',')
                    ?: listOf("arm64-v8a", "x86_64")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes +=
            setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/**",
            )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(project(":archive-core"))
    val fitzAar = providers.gradleProperty("paperforgeFitzAar").orNull
    if (fitzAar != null) {
        val artifact = file(fitzAar)
        val hash =
            MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        check(hash == "e6887a242882cdd2dce062430bb46b6727513e3eb94e222255cc1a37db7a7d8e") {
            "MuPDF artifact SHA-256 mismatch"
        }
        implementation(files(artifact))
    } else implementation("com.artifex.mupdf:fitz:1.28.5")
    implementation("androidx.core:core:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.james:apache-mime4j-dom:0.8.15")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation("ru.noties:jlatexmath-android-font-greek:0.2.0")
}
