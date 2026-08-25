# MediaCompressor Core - aggressive release shrink rules
# Target: release APK < 450 KB

-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-repackageclasses ''
-mergeinterfacesaggressively
-overloadaggressively

-dontwarn android.**
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**

# Keep app entry point + manifest-referenced components
-keep public class com.vr3th.mediacompressor.MainActivity
-keep public class * extends android.app.Activity

# Keep EXIF library public API (reflection-safe minimum)
-keep class androidx.exifinterface.media.ExifInterface { *; }

# Kotlin metadata / intrinsics - keep null-check helpers to avoid runtime crashes
-keepclassmembers class kotlin.jvm.internal.Intrinsics { *; }

# Remove Kotlin debug metadata to shrink size further
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
}

# Strip Log calls in release for smaller/faster binary
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
