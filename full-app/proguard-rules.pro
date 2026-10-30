# The integrated Office/format engines cross Java/native and reflective runtime boundaries.
# Keep these entry points while allowing R8 to trim unused app/framework/icon code.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# AndroidJUnitRunner and external startup tracing call this shared runtime boundary.
# Retain its tiny public facade so optimized installs remain instrumentable/profileable.
-keep class androidx.tracing.Trace { *; }

# Preserve the public runtime ABI used across the integrated modules and instrumentation.
# R8 otherwise merges Kotlin facades / enum types and removes APIs that a separate test APK
# still links against, even when AGP applies the name mapping. Unused icon factories and
# other unreferenced dependency implementation code remain shrinkable.
-keep class cn.paperforge.** { public protected *; }
-keep class kotlin.** { public protected *; }
-keep class kotlinx.coroutines.** { public protected *; }
-keep class androidx.compose.runtime.** { public protected *; }
-keep class androidx.compose.ui.** { public protected *; }
-keep class androidx.lifecycle.** { public protected *; }
-keep class androidx.window.layout.** { public protected *; }
-keep class androidx.concurrent.futures.** { public protected *; }
-keep class androidx.collection.** { public protected *; }
-keep class androidx.work.** { public protected *; }
-keep class androidx.room.Room { public protected *; }
-keep class androidx.core.os.ConfigurationCompat { public protected *; }
-keep class androidx.core.view.ViewConfigurationCompat { public protected *; }
-keep class androidx.core.view.ViewGroupKt { public protected *; }
-keep class com.tom_roush.pdfbox.** { public protected *; }
-keep class com.tom_roush.fontbox.** { public protected *; }

# LibreOfficeKit JNI callbacks and MuPDF JNI wrapper names are looked up by native code.
-keep class org.libreoffice.** { *; }
-keep class com.artifex.mupdf.fitz.** { *; }

# veraPDF loads model/parser types, validation profiles and JAXB adapters by name.
# Its reflection surface spans generated model families; retain it as one runtime boundary.
-keep class org.verapdf.** { *; }
# Rhino selects VMBridge and JavaScript built-ins through class names/reflection.
-keep class !org.mozilla.javascript.tools.**,org.mozilla.javascript.** { *; }
-keep class javax.xml.bind.** { *; }
-keep class com.sun.xml.bind.** { *; }
-keep class com.sun.istack.** { *; }
-keep class org.apache.xerces.** { *; }
-keep class org.apache.xml.serialize.** { *; }

# TeX font tables and PDFBox resource implementations are selected through class names.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-keep class com.tom_roush.fontbox.cmap.** { *; }

# Existing desktop-only/optional branches in the Android-ported archive dependencies.
# AndroidProfiles reads profiles directly; no AWT/ImageIO, JAXB binary XML/MIME,
# desktop process management, legacy external XML catalogs, SLF4J backend or PDF/UA engine
# is selected by ArchiveValidator. Keep exact missing types rather than ignoring all warnings.
-dontwarn com.sun.activation.registries.LogSupport
-dontwarn com.sun.activation.registries.MailcapFile
-dontwarn com.sun.xml.fastinfoset.sax.AttributesHolder
-dontwarn com.sun.xml.fastinfoset.stax.StAXDocumentParser
-dontwarn com.sun.xml.fastinfoset.stax.StAXDocumentSerializer
-dontwarn java.awt.Component
-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Graphics
-dontwarn java.awt.Image
-dontwarn java.awt.MediaTracker
-dontwarn java.awt.datatransfer.DataFlavor
-dontwarn java.awt.datatransfer.Transferable
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.ImageWriter
-dontwarn javax.imageio.stream.ImageOutputStream
-dontwarn javax.imageio.stream.MemoryCacheImageInputStream
-dontwarn javax.lang.model.SourceVersion
-dontwarn org.apache.xml.resolver.Catalog
-dontwarn org.apache.xml.resolver.CatalogManager
-dontwarn org.apache.xml.resolver.readers.CatalogReader
-dontwarn org.apache.xml.resolver.readers.SAXCatalogReader
-dontwarn org.jvnet.fastinfoset.VocabularyApplicationData
-dontwarn org.jvnet.staxex.Base64Data
-dontwarn org.jvnet.staxex.XMLStreamReaderEx
-dontwarn org.jvnet.staxex.XMLStreamWriterEx
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
-dontwarn org.verapdf.gf.model.impl.sa.GFSAPDFDocument
-dontwarn org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers
