-keep class org.opencv.** { *; }
-keep class com.gemalto.jp2.** { *; }
-keep class cn.paperforge.app.imaging.ScanModel { native <methods>; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn javax.xml.stream.**

# JCA resolves provider implementations by class name and public constructors.
-keep class org.bouncycastle.jcajce.provider.** { public <init>(...); }
-keep class org.bouncycastle.jce.provider.** { public <init>(...); }
-keep class org.bouncycastle.pqc.jcajce.provider.** { public <init>(...); }
