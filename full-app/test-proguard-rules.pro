# Instrumentation-only optional vendor Window extensions and compile-time annotations.
-dontwarn androidx.window.extensions.embedding.ActivityStack$Token
-dontwarn androidx.window.extensions.embedding.SplitInfo$Token
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed

# Stable selectors for `am instrument -e class`, JUnit rules and test methods.
-keep class cn.paperforge.app.**Test { *; }
-keep class cn.paperforge.network.**Test { *; }
-keepclassmembers class * {
    @org.junit.Test <methods>;
    @org.junit.Rule <fields>;
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
}
