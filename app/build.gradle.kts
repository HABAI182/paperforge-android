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
        versionCode = providers.gradleProperty("paperforgeVersionCode").get().toInt()
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
            path = file("src/main/cpp/CMakeLists.txt")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    packaging {
        resources.excludes +=
            setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

apply(from = rootProject.file("gradle/app-dependencies.gradle.kts"))
