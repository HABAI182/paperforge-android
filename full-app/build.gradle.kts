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
        versionCode = providers.gradleProperty("paperforgeVersionCode").get().toInt() + 3
        versionName = providers.gradleProperty("paperforgeVersionName").get()
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
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
    // ScanModel reads model bytes; ORT is copied to a cache file before opening.
    // Both support compressed APK assets, saving download/storage without losing offline models.
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
            val optimize = providers.gradleProperty("paperforgeOptimizeFull").orNull != "false"
            isMinifyEnabled = optimize
            isShrinkResources = optimize
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("app/proguard-rules.pro"),
                "proguard-rules.pro",
            )
            if (providers.gradleProperty("paperforgeOptimizeCode").orNull == "false")
                proguardFile(rootProject.file("gradle/review-shrink.pro"))
            testProguardFiles("test-proguard-rules.pro")
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
            // Only restore optional permissions for the full package. Preserve component
            // merge directives, including WorkManager's on-demand Startup configuration.
            .replace(Regex("""(<uses-permission\b[^>]*?)\s+tools:node="remove"([^>]*>)"""), "$1$2")
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
// Execute the same transport/browser regression sources in the integrated installation.
val prepareLanTests = tasks.register<Sync>("prepareLanTests") {
    from(rootProject.file("network-engine/src/androidTest/java")) { include("**/Lan*.kt") }
    into(layout.buildDirectory.dir("generated/lan-tests"))
}
android.sourceSets.getByName("androidTest").java.srcDir(prepareLanTests.map { it.destinationDir })
// Exercise the existing workbook regression in the integrated installation too.
val prepareWorkbookTests = tasks.register<Sync>("prepareWorkbookTests") {
    from(rootProject.file("app/src/androidTest/java")) { include("**/quality/WorkbookEnhancementTest.kt") }
    into(layout.buildDirectory.dir("generated/workbook-tests"))
}
android.sourceSets.getByName("androidTest").java.srcDir(prepareWorkbookTests.map { it.destinationDir })
// v200 feedback regression (export visibility, paper whiteness, corner rim, preview cache) must run
// in the same integrated package the owner tested.
val prepareFeedbackTests = tasks.register<Sync>("prepareFeedbackTests") {
    from(rootProject.file("app/src/androidTest/java")) {
        include("**/V200FeedbackTest.kt")
        include("**/EdgeInk*Test.kt")
    }
    into(layout.buildDirectory.dir("generated/feedback-tests"))
}
android.sourceSets.getByName("androidTest").java.srcDir(prepareFeedbackTests.map { it.destinationDir })
val prepareWorkbookUnitTests = tasks.register<Sync>("prepareWorkbookUnitTests") {
    from(rootProject.file("app/src/test/java")) {
        include("**/DewarpGridTest.kt", "**/ImageGeometryTest.kt", "**/GeometryTest.kt")
    }
    into(layout.buildDirectory.dir("generated/workbook-unit-tests"))
}
android.sourceSets.getByName("test").java.srcDir(prepareWorkbookUnitTests.map { it.destinationDir })
tasks.matching { (it.name.startsWith("compile") || it.name.startsWith("ksp")) && it.name.contains("UnitTest") }.configureEach { dependsOn(prepareWorkbookUnitTests) }
tasks.matching { it.name.startsWith("compile") && it.name.contains("AndroidTest") || it.name.startsWith("ksp") && it.name.contains("AndroidTest") }.configureEach { dependsOn(prepareWorkbookTests) }
tasks.matching { it.name.startsWith("compile") && it.name.contains("AndroidTest") || it.name.startsWith("ksp") && it.name.contains("AndroidTest") }.configureEach { dependsOn(prepareFeedbackTests) }
// The owner's private repro page is NOT committed: the test APK packages a copy from the
// out-of-repo acceptance workspace, so the fixture exists on device without entering the source
// tree or a git object.
val prepareEdgeInkFixture = tasks.register<Copy>("prepareEdgeInkFixture") {
    from(rootProject.file("../../work/repro-edge/original.jpg"))
    into(layout.buildDirectory.dir("generated/edge-ink-assets"))
}
android.sourceSets.getByName("androidTest").assets.srcDir(prepareEdgeInkFixture.map { it.destinationDir })
tasks.matching { it.name.startsWith("compile") && it.name.contains("AndroidTest") || it.name.startsWith("ksp") && it.name.contains("AndroidTest") }.configureEach { dependsOn(prepareEdgeInkFixture) }
// v200 annotation batch: swipeable capture preview + baked annotation device tests.
val prepareAnnotationTests = tasks.register<Sync>("prepareAnnotationTests") {
    from(rootProject.file("app/src/androidTest/java")) { include("**/PageAnnotationTest.kt") }
    into(layout.buildDirectory.dir("generated/annotation-tests"))
}
android.sourceSets.getByName("androidTest").java.srcDir(prepareAnnotationTests.map { it.destinationDir })
tasks.matching { it.name.startsWith("compile") && it.name.contains("AndroidTest") || it.name.startsWith("ksp") && it.name.contains("AndroidTest") }.configureEach { dependsOn(prepareAnnotationTests) }
tasks.matching { it.name.startsWith("compile") && it.name.contains("AndroidTest") || it.name.startsWith("ksp") && it.name.contains("AndroidTest") }.configureEach { dependsOn(prepareLanTests) }
apply(from = rootProject.file("gradle/app-dependencies.gradle.kts"))
dependencies {
    implementation(project(":bundled-office"))
    implementation(project(":bundled-formats"))
    implementation(project(":bundled-network"))
}
