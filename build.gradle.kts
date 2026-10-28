plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.20-2.0.1" apply false
}

// One external identity signs the app and signature-protected optional phone engines.
val paperforgeSigningStore = providers.environmentVariable("PAPERFORGE_SIGNING_STORE").orNull?.takeIf { it.isNotBlank() }
subprojects {
    plugins.withId("com.android.application") {
        if (paperforgeSigningStore != null) {
            extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
                val identity = signingConfigs.create("paperforgeExternal") {
                    storeFile = file(paperforgeSigningStore)
                    storePassword = providers.environmentVariable("PAPERFORGE_STORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("PAPERFORGE_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("PAPERFORGE_KEY_PASSWORD").get()
                }
                buildTypes.configureEach { signingConfig = identity }
            }
        }
    }
}
