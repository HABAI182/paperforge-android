plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cn.paperforge.office"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.paperforge.office"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("paperforgeVersionCode").get().toInt()
        versionName = providers.gradleProperty("paperforgeVersionName").get()
        ndk { abiFilters += providers.gradleProperty("paperforgeAbis").orNull?.split(',') ?: listOf("arm64-v8a", "x86_64") }
    }
    sourceSets.getByName("main") {
        jniLibs.srcDir("runtime/jniLibs")
        assets.srcDir("runtime/assets")
    }
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core:1.16.0") }

tasks.named("preBuild") {
    doFirst {
        check(file("runtime/assets/program/fundamentalrc").isFile) {
            "Run python tools/fetch_office_runtime.py before building the mobile Office module"
        }
    }
}
