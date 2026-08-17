-keep class com.vr3th.mediacompressor.** { *; }

-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.squareup.gifencoder.** { *; }
-keep class com.fpliu.ndk.pkg.prefab.android.21.** { *; }

# PDFBox references optional JPEG-2000 classes. The app does not bundle that optional codec.
-dontwarn com.gemalto.jp2.**
