# App entry points
-keep class com.vr3th.mediacompressor.MainActivity { *; }
-keep class com.vr3th.mediacompressor.MediaOps { *; }
-keep class com.vr3th.mediacompressor.VideoCompressor { *; }
-keep class com.vr3th.mediacompressor.LegacyVideoFallback { *; }

# FFmpegKit/FFprobeKit are accessed through native/JNI code. Keep the wrapper
# and related package metadata from release shrinking.
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**
