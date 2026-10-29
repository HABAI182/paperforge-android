plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cn.paperforge.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.paperforge.app"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("paperforgeVersionCode").get().toInt() + 1
        versionName = providers.gradleProperty("paperforgeVersionName").get()
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                providers.gradleProperty("paperforgeMnnSource").orNull?.let {
                    arguments += "-DPAPERFORGE_MNN_SOURCE=$it"
                }
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters +=
                providers.gradleProperty("paperforgeAbis").orNull?.split(',')
                    ?: listOf("arm64-v8a", "x86_64")
        }
    }
    buildFeatures { compose = true }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = rootProject.file("app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources { noCompress += listOf("ort", "mnn") }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            providers.gradleProperty("paperforgeIdSuffix").orNull?.let { applicationIdSuffix = it }
        }
        release {
            // Preserve the same reflective archive/runtime classes as the standalone engines.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes +=
            setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    testBuildType = if (providers.gradleProperty("paperforgeTestRelease").orNull == "true") "release" else "debug"
}

ksp { arg("room.schemaLocation", rootProject.file("app/schemas").path) }

val generatedManifest = layout.buildDirectory.file("generated/full-manifest/AndroidManifest.xml")
val prepareFullManifest = tasks.register("prepareFullManifest") {
    inputs.file(rootProject.file("app/src/main/AndroidManifest.xml"))
    outputs.file(generatedManifest)
    doLast {
        val original = rootProject.file("app/src/main/AndroidManifest.xml").readText()
        val content = original
            .replace("tools:node=\"remove\"", "")
            .replace("android:name=\".PaperForgeApp\"", "android:name=\".FullPaperForgeApp\" android:extractNativeLibs=\"true\"")
            .replace("<activity android:name=\".MainActivity\"", "<meta-data android:name=\"paperforge.full\" android:value=\"true\" /><activity android:name=\".MainActivity\"")
        generatedManifest.get().asFile.apply { parentFile.mkdirs(); writeText(content) }
    }
}
android.sourceSets.getByName("main") {
    java.srcDir(rootProject.file("app/src/main/java"))
    res.srcDir(rootProject.file("app/src/main/res"))
    assets.srcDir(rootProject.file("app/src/main/assets"))
    manifest.srcFile(generatedManifest)
}
tasks.named("preBuild") { dependsOn(prepareFullManifest) }
apply(from = rootProject.file("gradle/app-dependencies.gradle.kts"))
dependencies {
    implementation(project(":bundled-office"))
    implementation(project(":bundled-formats"))
    implementation(project(":bundled-network"))
}
