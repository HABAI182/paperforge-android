plugins { id("com.android.library") }

android {
    namespace = "com.gemalto.jp2"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += providers.gradleProperty("paperforgeAbis").orNull?.split(',') ?: listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { buildConfig = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation("androidx.annotation:annotation:1.3.0") }
