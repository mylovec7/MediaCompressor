# MediaCompressor is platform-only: no FFmpeg/Media3 native payloads.
# Keep annotations used by AndroidX/Compose and let R8 aggressively shrink everything else.
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations

# Android may discover these through platform APIs.
-keep class com.vr3th.mediacompressor.MainActivity { *; }

# Preserve enum names used by HistoryStore JSON.
-keepclassmembers enum com.vr3th.mediacompressor.data.MediaType { *; }
