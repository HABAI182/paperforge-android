plugins { id("com.android.library") }

android {
    namespace = "org.opencv"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += providers.gradleProperty("paperforgeAbis").orNull?.split(',')
                ?: listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                targets += "opencv_java"
                arguments += "-DANDROID_STL=c++_static"
                providers.gradleProperty("paperforgeOpenCvSource").orNull?.let {
                    arguments += "-DPAPERFORGE_OPENCV_SOURCE=$it"
                }
            }
        }
    }
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

dependencies { api(files("libs/opencv-java-4.12.0.jar")) }
