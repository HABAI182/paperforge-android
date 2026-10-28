import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins { id("com.android.library") }

android {
    namespace = "cn.paperforge.archive"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val veraRuntime by configurations.creating { isCanBeConsumed = false }
val portedDirectory = layout.buildDirectory.dir("ported-jars")
val prepareArchiveJars =
    tasks.register("prepareArchiveJars") {
        inputs.files(veraRuntime)
        inputs.files(androidComponents.sdkComponents.bootClasspath)
        outputs.dir(portedDirectory)
        doLast {
            val directory = portedDirectory.get().asFile.apply { mkdirs() }
            val files = veraRuntime.resolve()
            val platformClasses =
                androidComponents.sdkComponents.bootClasspath
                    .get()
                    .flatMap { file ->
                        ZipFile(file.asFile).use { zip ->
                            zip.entries()
                                .asSequence()
                                .map { it.name }
                                .filter { it.endsWith(".class") }
                                .toList()
                        }
                    }
                    .toSet()
            val impl = files.single { it.name == "jaxb-impl-2.3.2.jar" }
            val duplicateCoreEntries =
                ZipFile(impl).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
            files.forEach { source ->
                val target = directory.resolve(source.name)
                if (
                    source.name in
                        setOf(
                            "parser-1.30.3.jar",
                            "core-1.30.3.jar",
                            "jaxb-core-2.3.0.1.jar",
                            "verapdf-xmp-core-1.30.3.jar",
                            "xml-apis-1.4.01.jar",
                        )
                ) {
                    ZipFile(source).use { zip ->
                        ZipOutputStream(target.outputStream()).use { out ->
                            zip.entries()
                                .asSequence()
                                .filterNot { entry ->
                                    entry.isDirectory ||
                                        source.name == "xml-apis-1.4.01.jar" &&
                                            entry.name in platformClasses ||
                                        entry.name == "org/verapdf/pd/colors/PDDeviceRGB.class" ||
                                        entry.name ==
                                            "org/verapdf/pd/colors/PDCIEDictionaryBased.class" ||
                                        entry.name ==
                                            "org/verapdf/pdfa/validation/validators/JavaScriptEvaluator.class" ||
                                        entry.name == "org/verapdf/xmp/tools/SecureXML.class" ||
                                        entry.name.matches(Regex("META-INF/.*[.](SF|RSA|DSA)")) ||
                                        source.name == "jaxb-core-2.3.0.1.jar" &&
                                            (entry.name in duplicateCoreEntries ||
                                                entry.name.startsWith("META-INF/"))
                                }
                                .forEach { entry ->
                                    out.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                                    zip.getInputStream(entry).use { it.copyTo(out) }
                                    out.closeEntry()
                                }
                        }
                    }
                } else source.copyTo(target, overwrite = true)
            }
        }
    }
val portedArchives =
    files(provider { veraRuntime.resolve().map { portedDirectory.get().file(it.name).asFile } })
        .builtBy(prepareArchiveJars)

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    veraRuntime("org.verapdf:validation-model:1.30.3")
    veraRuntime("xml-apis:xml-apis:1.4.01")
    veraRuntime("xerces:xercesImpl:2.12.2") { exclude(group = "xml-apis", module = "xml-apis") }
    api(portedArchives)
}
