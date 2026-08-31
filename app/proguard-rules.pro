# MediaCompressor Core - release shrink rules
# Target: release APK < 450 KB
#
# NOTE (startup-crash fix): this file previously combined R8 full-mode with
# -overloadaggressively, -mergeinterfacesaggressively, and an
# -assumenosideeffects strip of Kotlin's own null-check helper, while giving
# R8 almost no -keep guidance about this app's own classes (only MainActivity
# and Activity subclasses were protected). That combination is a well-known
# recipe for a release build that compiles and installs fine but instantly
# force-closes on launch: R8 full mode is allowed to rename, inline, and
# merge classes/methods it can't prove are still needed from outside the
# app's own call graph, and with no keep rules for MediaEngine, TempSweeper,
# or the ~20-file market.* package, entry points like MediaEngine(this) or
# MarketRealtimeManager.stop() in MainActivity can resolve, at runtime, to a
# class/method R8 has stripped or restructured -> NoClassDefFoundError /
# NoSuchMethodError the moment onCreate() (or the first screen it builds)
# touches them. -overloadaggressively/-mergeinterfacesaggressively add to
# that same risk by deliberately colliding method signatures and merging
# interfaces, which is far more likely to misfire under full-mode than under
# classic ProGuard. Removed below, along with the Intrinsics strip (Kotlin
# inserts checkNotNullParameter at the top of most public/internal methods;
# discarding those calls doesn't meaningfully change APK size but does
# remove a load-bearing null check R8 full mode can otherwise interact with
# unpredictably during aggressive inlining). Everything else that was
# already safe (5 optimization passes, access modification, repackaging,
# Log stripping) is kept, so this still shrinks aggressively — it just no
# longer does so blindly against the app's own code.

-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-repackageclasses ''

-dontwarn android.**
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**

# ---------------------------------------------------------------------------
# App entry point + manifest-referenced components
# ---------------------------------------------------------------------------
-keep public class com.vr3th.mediacompressor.MainActivity
-keep public class * extends android.app.Activity

# ---------------------------------------------------------------------------
# This app's own runtime-critical classes.
#
# MainActivity reaches these only through ordinary Kotlin calls (no
# reflection), so in principle default reachability analysis should keep
# them — but full-mode R8 is aggressive about inlining/merging singletons
# and small classes it considers "safe" to restructure, and this app has no
# instrumented/automated test coverage of a real release build to catch it
# if it guesses wrong. Explicit keeps here cost a small amount of shrinkage
# in exchange for guaranteeing these can never be the thing R8 removes.
# ---------------------------------------------------------------------------

# Core engine + its temp-file sweeper (constructed directly in onCreate(),
# before any UI is shown — if either were stripped/mismerged, the crash
# would be instant and on every single launch).
-keep class com.vr3th.mediacompressor.MediaEngine { *; }
-keep class com.vr3th.mediacompressor.MediaEngine$* { *; }
-keep class com.vr3th.mediacompressor.TempSweeper { *; }

# The entire Quant Terminal / market data package: models, singleton
# managers (MarketRealtimeManager, MarketLiquidationFeed, MarketCache, ...),
# enums (MarketCategory, AssetClass, DataState, ...), and custom Canvas
# views (MarketCandlestickView, MarketSparklineView). MainActivity references
# many of these from property initializers and Activity-lifecycle callbacks
# (e.g. quantCategory = MarketCategory.CRYPTO at class-construction time,
# MarketRealtimeManager.stop() in onPause()) — a class in this package being
# renamed out from under those references, or an enum's static initializer
# being restructured, is exactly the shape of bug that only shows up at
# runtime in a minified build, never in debug and never at compile time.
-keep class com.vr3th.mediacompressor.market.** { *; }

# Enum values()/valueOf() — normally covered by the default Android
# optimize rules this file already includes via build.gradle.kts
# (getDefaultProguardFile("proguard-android-optimize.txt")), but made
# explicit here as well since this app leans on several enums
# (MarketCategory, AssetClass, DataState, SignalOutcome, StreamConnectionState,
# ...) for exhaustive `when` dispatch — a shape of code that throws instead
# of silently misbehaving if R8 ever mishandles it.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep EXIF library public API (reflection-safe minimum)
-keep class androidx.exifinterface.media.ExifInterface { *; }

# Kotlin metadata / intrinsics - keep the null-check helper class AND its
# calls (no -assumenosideeffects here — see note at top of file).
-keepclassmembers class kotlin.jvm.internal.Intrinsics { *; }

# Strip Log calls in release for smaller/faster binary — this one is safe:
# it only discards calls to android.util.Log, a platform class the app
# doesn't subclass or otherwise depend on structurally.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

-keepattributes SourceFile,LineNumberTable,Signature,Exceptions,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
