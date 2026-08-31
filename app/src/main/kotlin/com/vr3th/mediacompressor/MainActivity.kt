package com.vr3th.mediacompressor

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.*
import com.vr3th.mediacompressor.market.AiVerdict
import com.vr3th.mediacompressor.market.AssetClass
import com.vr3th.mediacompressor.market.BacktestResult
import com.vr3th.mediacompressor.market.BreakoutState
import com.vr3th.mediacompressor.market.DataState
import com.vr3th.mediacompressor.market.DisplayCurrency
import com.vr3th.mediacompressor.market.FxRates
import com.vr3th.mediacompressor.market.isUsable
import com.vr3th.mediacompressor.market.LoggedSignal
import com.vr3th.mediacompressor.market.MarketAnalysis
import com.vr3th.mediacompressor.market.MarketAnalysisEngine
import com.vr3th.mediacompressor.market.MarketBacktestEngine
import com.vr3th.mediacompressor.market.MarketCache
import com.vr3th.mediacompressor.market.MarketCandlestickView
import com.vr3th.mediacompressor.market.MarketCategory
import com.vr3th.mediacompressor.market.MarketInstrument
import com.vr3th.mediacompressor.market.MarketInstrumentIndex
import com.vr3th.mediacompressor.market.MarketLiquidationFeed
import com.vr3th.mediacompressor.market.MarketQuote
import com.vr3th.mediacompressor.market.MarketRealtimeManager
import com.vr3th.mediacompressor.market.MarketRegime
import com.vr3th.mediacompressor.market.MarketRepository
import com.vr3th.mediacompressor.market.MarketSignalLog
import com.vr3th.mediacompressor.market.MarketSparklineView
import com.vr3th.mediacompressor.market.QuantSignal
import com.vr3th.mediacompressor.market.RealtimeQuoteState
import com.vr3th.mediacompressor.market.SRLevelType
import com.vr3th.mediacompressor.market.SignalOutcome
import com.vr3th.mediacompressor.market.SignalQuality
import com.vr3th.mediacompressor.market.StreamConnectionState
import com.vr3th.mediacompressor.market.StructureTrend
import com.vr3th.mediacompressor.market.VolatilityRegime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : Activity() {

    // ---- Cyber Hacker Obsidian palette (All-Green Matrix HUD, zero pink) ---
    // 100% pink/magenta removal pass: every former-Rose hex now resolves to
    // Matrix Cyber Green, Diamond White, or (for negative/error/sell market
    // readings only, where a same-hue-as-"good" signal would be misleading)
    // a plain alert red — never pink or magenta. Variable names are kept
    // as-is (legacy, from the original Rose Noir build) to avoid touching
    // every call site; only the hex values changed.
    private val bg = Color.parseColor("#07060B")          // Deepest Void Obsidian (AMOLED)
    private val panel = Color.parseColor("#0F0E17")       // Obsidian Glass panel/card fill
    private val panelStroke = Color.parseColor("#251F38") // specular hairline border
    private val activeCardBg = Color.parseColor("#141F1A") // active card fill (green-tinted chamber)
    private val pinkSoft = Color.parseColor("#35E58C")    // Electric Mint — secondary accent (labels/subtitles)
    private val pinkElectric = Color.parseColor("#FF3B57") // Alert Crimson (NOT pink/magenta) — negative/sell/error readings only
    private val cyberBlossom = Color.parseColor("#00FF9D") // Matrix Cyber Green — checkmarks, highlights
    private val rippleTint = Color.argb(70, 0, 255, 157) // translucent Cyber Green — masked tap-feedback ripple
    private val neonRose = Color.parseColor("#35E58C")     // Electric Mint — progress-wave glow tail
    private val roseGold = Color.parseColor("#E0DCE8")     // Soft Muted White — telemetry captions
    private val pastelPink = Color.parseColor("#FFFFFF")   // Pure Diamond White
    private val white = Color.parseColor("#FFFFFF")       // Pure Diamond White
    private val gray = Color.parseColor("#E0DCE8")        // Soft Muted White (secondary telemetry text)
    private val metaGray = Color.parseColor("#8E899E")    // Velvet Gray
    private val mint = Color.parseColor("#00FF9D")        // Matrix Cyber Green / Electric Mint — primary status
    private val warn = Color.parseColor("#00FF9D")        // All alert/cancelled text renders in Cyber Green per spec
    private val err = Color.parseColor("#00FF9D")         // All error/failed/rejected text renders in Cyber Green per spec
    private val dimBackdrop = Color.parseColor("#B007060B")

    private val ease = PathInterpolator(0.25f, 1f, 0.5f, 1f)          // screen morphs / transitions
    private val springEase = PathInterpolator(0.34f, 1.56f, 0.64f, 1f) // touch-release bounce

    // ---- Adaptive Hybrid Bilingual Localization (auto Indonesian / English) ----
    // Detected once from the device locale; "in"/"id" both denote Indonesian.
    private val isIndonesian: Boolean by lazy {
        val lang = Locale.getDefault().language
        lang == "in" || lang == "id"
    }
    /** Returns [id] on an Indonesian device, [en] everywhere else. */
    private fun bi(id: String, en: String): String = if (isIndonesian) id else en

    // Explicit screen-state tracking backing the back-stack rules below.
    private enum class ScreenState { HOME, ANALYZE, CONFIG, PROCESSING, RESULT, OTHER }
    private var screenState: ScreenState = ScreenState.HOME

    private lateinit var pageFrame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var engine: MediaEngine

    private var selectedUris: MutableList<Uri> = mutableListOf()
    private var selectedType: String = "UNKNOWN"
    private var currentOp: String = ""

    // Op configuration state, gathered by the config screen before START.
    private var cfgRangeStart = 0.0
    private var cfgRangeEnd = 10.0
    // Raw text the user typed into the range fields, so navigating back into
    // Feature Configuration (from Processing/Result) restores it verbatim
    // instead of resetting to the screen's literal defaults.
    private var cfgRangeStartText: String? = null
    private var cfgRangeEndText: String? = null
    private var cfgQuality = MediaEngine.Quality.MEDIUM
    private var cfgMute = false
    private var cfgSpeed = 1.0
    private var cfgImageFormat = MediaEngine.ImageFormatTarget.JPG
    private var cfgGainDb = 0.0
    private var cfgTargetKb: Int? = null
    private var cfgPassword = ""
    private var cfgWatermarkText = "MEDIACOMPRESSOR"
    private var cfgRotateMode = MediaEngine.RotateMode.ROT_90
    private var cfgFps = 12
    private var cfgGifMaxWidth = 480
    private var cfgDelayCentis = 20

    private val cancelled = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // ---- Background task survival (Dynamic Origin Back-Stack §5.3) --------
    // A running task is never tied to the Processing screen being on-screen:
    // system/gesture back (and the "<" button) always just navigate away —
    // ONLY the explicit in-app [ ✕ Cancel ] button (on either the Processing
    // screen or the Home HUD card) calls cancelled.set(true) + interrupt().
    // Whichever screen is currently visible opts in to live ticks via the
    // three hooks below; base() clears them on every navigation so a screen
    // that doesn't care about progress (e.g. Settings) is never called into.
    private var taskRunning = false
    private var taskType: String = ""
    private var taskOp: String = ""
    private var taskPercent: Int = 0
    private var taskStage: String = ""
    private var taskJustFinished: List<FinalizedOutcome>? = null
    private var onTaskTick: ((Int, String) -> Unit)? = null
    private var onTaskDone: ((List<FinalizedOutcome>) -> Unit)? = null
    private var onTaskCancelled: (() -> Unit)? = null
    /** The LIVE Monitor's scanner view currently on-screen (if any) — paused/resumed by Activity onPause/onResume so its animator never spins while backgrounded. */
    private var activeLiveScanner: LiveScannerView? = null
    /** The AKSES LUMI header's zigzag waveform (if any on-screen) — same pause/resume lifecycle treatment as [activeLiveScanner]. */
    private var activeLumiWaveform: ZigzagWaveformView? = null
    /** The Processing screen's active-job ECG view (if any on-screen) — same pause/resume lifecycle treatment as [activeLiveScanner]. */
    private var activeWaveProgress: WaveProgressView? = null
    /** Set once, universally, whenever a task finishes (see launchWorker) — while now < this, the LIVE Monitor shows its brief COMPLETED flash regardless of which screen was visible at the actual moment of completion. */
    private var liveMonitorCompletedUntil: Long = 0L
    /** Sentinel identifying the System Monitor card currently on-screen (if any) — its polling ticker stops once this no longer matches its own token (torn down by base()). */
    private var activeSystemMonitorToken: Any? = null
    private var lastCpuSampleRealtime = 0L
    private var lastCpuSampleProcessTime = 0L

    /** A per-file result after MediaEngine.finalize() + MediaStore publish have already run — computed exactly once, in the worker thread's completion handler, regardless of which screen (if any) is visible when it happens. */
    private sealed class FinalizedOutcome {
        data class Done(val displayName: String, val inputBytes: Long, val outputBytes: Long, val note: String, val publishedUri: Uri?) : FinalizedOutcome()
        data class Rejected(val reason: String) : FinalizedOutcome()
        data class Failed(val error: String) : FinalizedOutcome()
    }

    // Navigation: a lightweight back handler updated by every screen, plus
    // a drawer overlay that intercepts back when open.
    private var backHandler: (() -> Unit)? = null
    private var drawerOpen = false
    private var drawerRoot: FrameLayout? = null
    private var tooltipRoot: FrameLayout? = null

    // Dynamic Origin Back-Stack: showCategory() is reachable from Home's
    // Command Center rows AND from the Drawer's category list. Back must
    // return to whichever one launched it — this closure is set right
    // before each showCategory() call site below.
    private var categoryOrigin: () -> Unit = { showHome() }

    // Same dynamic-origin treatment for the Drawer's own screens: MEDIA
    // VAULT is reachable both from the Drawer and from its Home command
    // card, while SETTINGS/ABOUT are Drawer-only — either way, Back must
    // return to Home with the Drawer re-opened whenever the Drawer was how
    // the user got there, and to a plain Home otherwise.
    private var vaultOrigin: () -> Unit = { showHome() }
    private var settingsOrigin: () -> Unit = { showHome(); openDrawer() }
    private var aboutOrigin: () -> Unit = { showHome(); openDrawer() }

    // Repick: after tapping "Ganti File" we relaunch the picker and, on
    // success, hand the newly picked file straight back into the screen
    // that asked for it — no trip back to Home.
    private var repickCallback: ((List<Uri>) -> Unit)? = null

    // ---- Global Quant Algorithmic Market Terminal — screen-local state ----
    // All data/provider/cache/indicator logic lives in the isolated `market`
    // package (see MARKET_MODULE.md) — this Activity only holds UI state and
    // renders using its own existing view-styling helpers, same as before.
    private var quantCategory: MarketCategory = MarketCategory.CRYPTO
    private var quantTimeframe: String = "1D"
    private var quantCurrencyOverride: DisplayCurrency? = null
    private var quantPinned: MutableSet<String> = mutableSetOf()
    private var quantFxRates: FxRates? = null
    private var quantMarketAlive = false
    private var airGapDialogRoot: FrameLayout? = null

    // ---- Operation catalogue (30 engines across 5 module groups) --------
    private val videoOps = listOf(
        "Video Compress & Mute", "Video Trim & Reverse", "Video Speed",
        "Video to Audio", "Video to GIF", "Extract Frame", "Video Merge", "Video Rotate & Flip"
    )
    private val gifFileOps = listOf("GIF to Video", "GIF Compress")
    private val imageOps = listOf(
        "Photo Compress", "Batch Photo Compress", "Image Converter", "Remove EXIF", "Photo to GIF", "Photo to PDF"
    )
    private val audioOps = listOf(
        "Audio Trim & Reverse", "Audio Merge", "Audio Compress", "Audio Volume Booster", "Audio Silence Trimmer"
    )
    private val pdfOps = listOf(
        "PDF to Photo", "Merge PDF", "Split & Reverse PDF",
        "Compress PDF", "PDF to Grayscale", "Watermark PDF", "Lock PDF", "Unlock PDF"
    )
    private val archiveOps = listOf("Create ZIP", "Extract ZIP", "ZIP Recompress")

    private val multiInputOps = setOf(
        "Video Merge", "Audio Merge", "Merge PDF", "Batch Photo Compress",
        "Photo to GIF", "Photo to PDF", "Create ZIP"
    )

    // Single-file engines that additionally accept multiple files and queue
    // them through the same configured settings, one output per input file
    // (distinct from multiInputOps above, which combine many inputs into a
    // single output). Covers all 20 engines listed in the batch spec.
    //
    // "Photo Compress" is deliberately NOT here: it's strictly single-file
    // (allowMultiple = false everywhere it's picked/routed). "Batch Photo
    // Compress" is the dedicated multi-file counterpart for that same
    // engine — the two are kept unambiguous and mutually exclusive so
    // there's never a question of which one a multi-select lands in.
    private val batchableOps = setOf(
        "Video Compress & Mute", "Video to Audio", "Video Speed", "Video Trim & Reverse",
        "Video to GIF", "Extract Frame", "Video Rotate & Flip",
        "Image Converter", "Remove EXIF",
        "GIF to Video", "GIF Compress",
        "Audio Trim & Reverse", "Audio Compress", "Audio Volume Booster", "Audio Silence Trimmer",
        "PDF to Photo", "Compress PDF", "PDF to Grayscale", "Watermark PDF",
        "Extract ZIP", "ZIP Recompress"
    )

    private fun acceptsMultiple(op: String): Boolean = op in multiInputOps || op in batchableOps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = MediaEngine(this)
        pageFrame = FrameLayout(this).apply { setBackgroundColor(bg) }
        setContentView(pageFrame)
        registerBackHandling()
        // Silent, safe temp-file maintenance — never touches original files,
        // finished outputs, or an in-progress task's temp files (guarded by
        // !taskRunning at every call site below).
        engine.purgeTempFiles()
        showBoot()
    }

    // Activity-lifecycle awareness for every periodic ticker on Home (clock,
    // LIVE monitor scanner animation, system stats poll): each one checks
    // this flag before doing its real work, so backgrounding the app costs
    // zero extra battery even if the Home screen is left attached underneath.
    private var activityActive = true

    override fun onResume() {
        super.onResume()
        activityActive = true
        activeLiveScanner?.resumeAnim()
        activeLumiWaveform?.resumeAnim()
        activeWaveProgress?.resumeAnim()
    }

    override fun onPause() {
        super.onPause()
        activityActive = false
        activeLiveScanner?.pauseAnim()
        activeLumiWaveform?.pauseAnim()
        activeWaveProgress?.pauseAnim()
        // Lifecycle backstop for the Phase 3 WebSocket feeds: leaveDetail() already stops these on
        // in-app back navigation (including the system back gesture, which routes through the same
        // backHandler), but if the user leaves via the home button / app switcher / a notification
        // while the detail screen is showing, only this Activity-level callback fires — stop()ing
        // here as well guarantees the sockets never keep running in the background regardless of how
        // the screen was left. Both stop() calls are idempotent/safe to call when already stopped.
        MarketRealtimeManager.stop()
        MarketLiquidationFeed.stop()
    }

    // System back / predictive-back gesture, API 24 through API 35+.
    private fun registerBackHandling() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBackNav() }
        }
    }

    override fun onBackPressed() {
        // On API 33+ the OnBackInvokedCallback registered above handles this instead.
        if (Build.VERSION.SDK_INT >= 33) return
        handleBackNav()
    }

    /**
     * Single source of truth for back navigation — both the in-app "<"
     * button (via topBar's onBack) and the Android system/gesture back
     * (via registerBackHandling / onBackPressed above) resolve through this
     * same function, so they can never diverge. [screenState] tracks which
     * screen is current (HOME / ANALYZE / CONFIG / PROCESSING / RESULT /
     * OTHER); each screen's [backHandler] (set by topBar, or overridden
     * directly on RESULT/PROCESSING in runOperation) encodes the specific
     * ScreenState -> ScreenState transition described in the nav spec:
     * RESULT -> HOME (universally — a completed or cancelled result never
     * loops back into its workspace configuration screen), PROCESSING ->
     * (while running) HOME / (once finished) HOME, CONFIG -> ANALYZE,
     * ANALYZE -> HOME, and only HOME allows the Activity to finish.
     */
    private fun handleBackNav() {
        when {
            drawerOpen -> closeDrawer()
            backHandler != null -> backHandler?.invoke()
            else -> finish() // only reachable with screenState == HOME, per the nav spec
        }
    }

    // =========================================================================
    // Layout primitives
    // =========================================================================

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(n: Int): Float = n * resources.displayMetrics.density

    // Scroll-position memory (bug fix #2): keyed by a caller-chosen string
    // (not ScreenState — several unrelated screens share ScreenState.OTHER,
    // which would cross-contaminate their saved positions). Only screens
    // that opt in via rememberScroll(key) get their position restored;
    // everything else behaves exactly as before.
    private val scrollMemory = HashMap<String, Int>()
    private var scrollMemoryKey: String? = null
    private lateinit var currentScrollView: ScrollView

    /**
     * Tears down the current screen and builds a fresh one.
     * [animate] controls the fade/slide-in entrance — pass false for a
     * same-screen re-entry (e.g. tapping the File Header Card while already
     * in Feature Configuration) so it updates in place instead of visibly
     * blinking/re-fading.
     */
    private fun base(animate: Boolean = true): LinearLayout {
        closeDrawer(animated = false)
        // Whichever screen was previously wired into live task ticks is
        // being torn down now — the new screen must opt back in if it cares.
        onTaskTick = null; onTaskDone = null; onTaskCancelled = null
        activeLiveScanner = null
        activeLumiWaveform = null
        activeSystemMonitorToken = null
        closeAirGapDialog()
        // Save the outgoing screen's exact scroll position before it's torn
        // down, keyed by whatever key it registered via rememberScroll().
        if (::currentScrollView.isInitialized) {
            scrollMemoryKey?.let { key -> scrollMemory[key] = currentScrollView.scrollY }
        }
        scrollMemoryKey = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(36))
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(root)
        pageFrame.removeAllViews()
        pageFrame.addView(scroll)
        currentScrollView = scroll
        val builtRoot = root
        if (animate) {
            builtRoot.alpha = 0f
            builtRoot.translationY = dpf(6)
            // Crash-hardening: the whole hardware-layer + fade-in is
            // deferred to builtRoot.post {} so it only ever runs once this
            // view is actually attached and measured — never against an
            // unattached/zero-size view, which is where a hardware layer
            // has been known to misbehave on some OEM builds. Both the
            // layer-type call and the animation itself are wrapped so any
            // unexpected throw there degrades to "just show the screen at
            // full alpha, no fade" instead of ever crashing the app.
            builtRoot.post {
                try {
                    builtRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    builtRoot.animate().alpha(1f).translationY(0f).setInterpolator(ease).setDuration(260)
                        .withEndAction {
                            try { builtRoot.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Exception) {}
                        }
                        .start()
                } catch (_: Exception) {
                    builtRoot.alpha = 1f
                    builtRoot.translationY = 0f
                }
            }
        } else {
            builtRoot.alpha = 1f
            builtRoot.translationY = 0f
        }
        return root
    }

    /**
     * Opts the just-built screen into scroll-position memory under [key]:
     * if a position was previously saved for this key, restores it exactly
     * (`scrollView.post { scrollTo(0, savedY) }`) instead of leaving the
     * user at the top; either way, this screen's position is now tracked
     * so the next navigation-away saves it correctly. Call once, after all
     * content has been added to `root`.
     */
    private fun rememberScroll(key: String) {
        scrollMemoryKey = key
        val target = scrollMemory[key] ?: return
        val sv = currentScrollView
        sv.post { sv.scrollTo(0, target) }
    }

    /** Lightens [color]'s value channel by [amount] (0f–1f) in HSV space, alpha preserved — used only for the subtle top specular sheen in [shape]. */
    private fun lighten(color: Int, amount: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] + amount).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    /**
     * Floating glass card/button fill: a subtle top-to-bottom specular
     * sheen (a soft ~6% lighter band at the top, settling into the exact
     * base [color] by the bottom) instead of one flat fill, plus the
     * existing 1dp hairline [stroke] — together this is the "elegant
     * floating glass" idle-state depth used by every card/button/chip/row
     * in the app. No extra draw calls or elevation shadow: it's still a
     * single GradientDrawable, just a 2-stop gradient instead of a solid.
     */
    private fun shape(color: Int, stroke: Int? = null, radius: Float = 16f, strokeWidthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(lighten(color, 0.06f), color)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (stroke != null) setStroke(dp(strokeWidthDp), stroke)
        }

    private fun text(s: String, size: Float, color: Int = white): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(4), 0, dp(4))
            letterSpacing = if (size <= 12f) 0.05f else 0.005f
        }

    private fun heading(s: String, size: Float, color: Int = white): TextView =
        text(s, size, color).apply {
            typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
            letterSpacing = -0.005f
        }

    private fun mono(s: String, size: Float, color: Int = white): TextView =
        text(s, size, color).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.01f }

    /**
     * Native system touch feedback ("bawaan HP") — no touch hijacking of
     * any kind, and clipped to the EXACT corner radius of whatever card or
     * button it's applied to (no square white overflow past rounded
     * corners).
     *
     * No `setOnTouchListener`, no manual `ACTION_DOWN/MOVE/UP` consumption,
     * no scale/translation hacks. `View`/`ViewGroup` already own gesture
     * disambiguation internally (they track
     * `ViewConfiguration.get(context).scaledTouchSlop`, and a parent
     * `ScrollView` intercepts — and cancels — a child's touch the instant a
     * drag exceeds that slop), so a click only ever fires for a genuinely
     * stationary tap. Cards and buttons never see, delay, or consume a
     * scroll gesture; scrolling is left 100% to the ScrollView itself.
     *
     * The press effect itself is a `RippleDrawable` masked to a clone of
     * the view's own `background` shape — every card/button in the app
     * sets its `background` via [shape], a `GradientDrawable` with its own
     * corner radius (12dp/14dp/16dp/pill, per call site) — so the ripple's
     * clip geometry always matches that exact radius, never a generic or
     * hardcoded one. Reading `v.background` is deferred to `v.post {}`
     * rather than done inline here, because a few call sites in this file
     * assign `background` in a `refresh()` pass that runs *after*
     * `applyTapFeedback()` is called (e.g. the Quality Profile cards); by
     * the time the posted Runnable fires, that synchronous setup has
     * already finished, so the mask is always correct on first paint —
     * `post()` only defers to later in the same frame/attach cycle, never
     * to a moment the user could actually see or touch. Views with no
     * `GradientDrawable` background (full-bleed dim overlays, plain drawer
     * rows) fall back to the stock unmasked ripple, which is already
     * correct for a shape with no rounded corners to overflow.
     *
     * On top of the ripple, a `StateListAnimator` drives the tactile
     * micro-scale compression toward [scale] (default 0.96x, tuned per
     * call site) the instant the view's own `pressed` drawable-state turns
     * on, springing back to 1.0x with `PathInterpolator(0.34f, 1.56f,
     * 0.64f, 1.0f)` the instant it turns off. This is deliberately NOT a
     * touch listener — `View.isPressed` is the same built-in,
     * touch-slop-aware state the ripple above already reacts to (set by
     * the framework on a stationary ACTION_DOWN, cleared the moment a
     * parent `ScrollView` intercepts for a drag), so the scale can never
     * fire, jitter, or get stuck mid-scroll the way a manual
     * ACTION_DOWN/MOVE/UP scale hack used to.
     *
     * Crash-hardening: both the ripple-mask and the StateListAnimator setup
     * are deferred to `v.post {}` (only ever runs once the view is attached
     * and measured, never against a zero-size/unattached view) and each
     * wrapped in its own try-catch. A failure in either — an unexpected
     * OEM graphics-driver quirk, a null constantState on some ROM, etc. —
     * degrades silently to "no ripple" / "no press-scale" for that one
     * view instead of ever taking down the app; the click listener itself
     * is set unconditionally and outside both try blocks, so tapping the
     * view always works even in the worst case.
     */
    private fun applyTapFeedback(v: View, scale: Float = 0.96f, action: () -> Unit) {
        v.isClickable = true
        v.isFocusable = true
        v.post {
            if (v.foreground == null) {
                try {
                    val mask = (v.background as? GradientDrawable)
                        ?.constantState?.newDrawable(resources)?.mutate() as? GradientDrawable
                    v.foreground = if (mask != null) {
                        RippleDrawable(ColorStateList.valueOf(rippleTint), null, mask)
                    } else {
                        val rippleAttr = TypedValue()
                        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleAttr, true) && rippleAttr.resourceId != 0) {
                            getDrawable(rippleAttr.resourceId)
                        } else null
                    }
                } catch (_: Exception) { /* no ripple for this view — never fatal */ }
            }
            if (scale < 1f && v.stateListAnimator == null) {
                try {
                    v.stateListAnimator = StateListAnimator().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), ObjectAnimator.ofPropertyValuesHolder(
                            null, PropertyValuesHolder.ofFloat(View.SCALE_X, scale), PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)
                        ).setDuration(70))
                        addState(intArrayOf(), ObjectAnimator.ofPropertyValuesHolder(
                            null, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)
                        ).apply { duration = 260; interpolator = springEase })
                    }
                } catch (_: Exception) { /* no press-scale for this view — never fatal */ }
            }
        }
        v.setOnClickListener { action() }
    }

    private fun button(s: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = s
            setTextColor(white)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.015f
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = shape(panel, panelStroke, 12f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            applyTapFeedback(this, 0.95f, action)
        }

    private fun primaryButton(s: String, action: () -> Unit): TextView =
        button(s, action).apply {
            setTextColor(white)
            textSize = 13.5f
            gravity = Gravity.CENTER
            background = shape(panel, mint, 16f, 2)
            typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }

    /** 44x44dp square icon button (back / drawer) — glyph mathematically centered, 0.95x spring feedback. */
    private fun iconButton(symbol: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = 20f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = shape(panel, panelStroke, 12f)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            applyTapFeedback(this, 0.95f, action)
        }

    private fun line(): View = View(this).apply {
        setBackgroundColor(panelStroke)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(16) }
    }

    private fun section(title: String, subtitle: String = "") {
        root.addView(text(title, 10f, pinkSoft).apply { letterSpacing = 0.12f })
        if (subtitle.isNotBlank()) root.addView(text(subtitle, 12.5f, gray))
        root.addView(line())
    }

    private fun editText(hintText: String, initial: String): EditText =
        EditText(this).apply {
            hint = hintText
            setText(initial)
            setTextColor(white)
            setHintTextColor(metaGray)
            typeface = Typeface.MONOSPACE
            setBackground(shape(panel, panelStroke, 12f))
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }

    /** Glowing pulsing status capsule, e.g. "• Engine Ready". Animator auto-cancels on detach. */
    private fun statusPill(label: String, dotColor: Int, glow: Int = mint): LinearLayout {
        val dot = View(this).apply { background = shape(dotColor, null, 6f) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(panel, glow, 20f)
            setPadding(dp(10), dp(6), dp(12), dp(6))
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(7) })
        row.addView(text(label, 10f, gray).apply { setPadding(0, 0, 0, 0); letterSpacing = 0.04f })
        val pulse = ValueAnimator.ofFloat(1f, 0.35f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
        }
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { pulse.start() }
            override fun onViewDetachedFromWindow(v: View) { pulse.cancel() }
        })
        return row
    }

    private fun glassCard(radius: Float = 14f, stroke: Int = panelStroke): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(panel, stroke, radius)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }

    /** Live HH:mm:ss clock + localized date, auto-adapting to the device's locale/timezone. Ticks while attached, stops on detach. */
    private fun liveClockWidget(): LinearLayout {
        val card = glassCard(stroke = mint)
        val timeView = mono("00:00:00", 26f, white).apply { setPadding(0, 0, 0, 0) }
        val dateView = text("", 11f, mint).apply { setPadding(0, dp(2), 0, 0); letterSpacing = 0.04f }
        card.addView(timeView)
        card.addView(dateView)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy  •  zzz", Locale.getDefault())
        val handler = Handler(mainLooper)
        val ticker = object : Runnable {
            override fun run() {
                val now = Date()
                timeView.text = timeFmt.format(now)
                dateView.text = dateFmt.format(now)
                handler.postDelayed(this, 1000)
            }
        }
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { ticker.run() }
            override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(ticker) }
        })
        return card
    }

    private fun addToolRow(label: String, detail: String = "", action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(panel, panelStroke, 14f)
            setPadding(dp(14), dp(13), dp(12), dp(13))
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
        applyTapFeedback(row) { action() }

        val rail = View(this).apply { background = shape(pinkSoft, null, 4f) }
        row.addView(rail, LinearLayout.LayoutParams(dp(3), dp(30)).apply { rightMargin = dp(13) })

        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = .02f
        })
        if (detail.isNotBlank()) copy.addView(text(detail, 10.5f, metaGray).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(text("›", 19f, metaGray).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(dp(26), dp(38)))

        root.addView(row)
    }

    // =========================================================================
    // Top bar (glassmorphic, back / drawer, live status pill)
    // =========================================================================

    private fun topBar(title: String, showBack: Boolean, statusLabel: String = "READY", statusColor: Int = mint, onBack: (() -> Unit)? = null) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(panel, panelStroke, 16f)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        }
        if (showBack) {
            bar.addView(iconButton("‹") { (onBack ?: { showHome() })() })
        } else {
            bar.addView(iconButton("☰") { openDrawer() })
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 14.5f
            setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.03f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        bar.addView(statusPill(statusLabel, statusColor))
        root.addView(bar)
        backHandler = if (showBack) (onBack ?: { showHome() }) else null
    }

    // =========================================================================
    // Interactive educational tooltips  [ ? ]
    // =========================================================================

    /** 28x28dp Cyber Green glass "[ ? ]" button that opens a frosted educational modal. */
    private fun infoButton(explainer: String): TextView =
        TextView(this).apply {
            text = "?"
            textSize = 13f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(mint)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = shape(panel, mint, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(10) }
            applyTapFeedback(this, 0.9f) { showTooltipModal(explainer) }
        }

    /** A section header ("ROTATION", "PLAYBACK SPEED"...) with a [?] tooltip button aligned top-right. */
    private fun headerWithInfo(r: LinearLayout, title: String, explainer: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text("\n$title", 13f, pinkSoft), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(infoButton(explainer))
        r.addView(row)
    }

    private fun showTooltipModal(body: String) {
        closeTooltipModal(animated = false)
        val overlay = FrameLayout(this).apply { setBackgroundColor(dimBackdrop); alpha = 0f }
        applyTapFeedback(overlay, 1f) { closeTooltipModal() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(panel, mint, 18f, 2)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            scaleX = 0.92f; scaleY = 0.92f; alpha = 0f
        }
        card.addView(text(bi("PENJELASAN", "GUIDE"), 10.5f, mint).apply { letterSpacing = 0.12f })
        card.addView(mono(body, 12.5f, white).apply { setPadding(0, dp(8), 0, 0) })
        card.addView(button(bi("[ Tutup ]", "[ Close ]")) { closeTooltipModal() }
            .apply { background = shape(panel, mint, 12f); setTextColor(white) })
        val holder = FrameLayout(this)
        holder.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(26); rightMargin = dp(26)
        })
        pageFrame.addView(holder)
        tooltipRoot = holder
        overlay.animate().alpha(1f).setDuration(180).start()
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(springEase).start()
    }

    private fun closeTooltipModal(animated: Boolean = true) {
        val holder = tooltipRoot ?: return
        tooltipRoot = null
        if (!animated) { pageFrame.removeView(holder); return }
        holder.animate().alpha(0f).setDuration(150).withEndAction { pageFrame.removeView(holder) }.start()
    }

    // =========================================================================
    // Drawer navigation
    // =========================================================================

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true

        val overlay = FrameLayout(this).apply { setBackgroundColor(dimBackdrop); alpha = 0f }
        applyTapFeedback(overlay, 1f) { closeDrawer() }

        val panelWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(28), dp(20), dp(24))
            translationX = -panelWidth.toFloat()
        }
        drawer.addView(heading("MEDIACOMPRESSOR", 18f, white))
        drawer.addView(text("Vr3tH🇵🇸  /  ROSE NOIR", 10.5f, pinkSoft))
        drawer.addView(line())

        fun item(label: String, detail: String, go: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(12), dp(2), dp(12))
            }
            applyTapFeedback(row) { closeDrawer(); go() }
            row.addView(TextView(this).apply {
                text = label; textSize = 14.5f; setTextColor(white)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = .02f
            })
            row.addView(text(detail, 10.5f, metaGray).apply { setPadding(0, dp(2), 0, 0) })
            drawer.addView(row)
        }
        item("VIDEO", "Compress • merge • trim / reverse • speed") { openCategoryFromDrawer("VIDEO") }
        item("IMAGE", "Compression • conversion • batch") { openCategoryFromDrawer("IMAGE") }
        item("GIF", "GIF conversion • compression") { openCategoryFromDrawer("GIF") }
        item("AUDIO", "Trim / reverse • merge • processing") { openCategoryFromDrawer("AUDIO") }
        item("PDF", "Compression • split / reverse • security") { openCategoryFromDrawer("PDF") }
        item("ARCHIVE", "ZIP create • extract • recompress") { openCategoryFromDrawer("ARCHIVE") }
        drawer.addView(line())
        item("MEDIA VAULT", "Browse & manage processed files") { openVaultFromDrawer() }
        item("SETTINGS", "Application behavior and preferences") { openSettingsFromDrawer() }
        item("ABOUT", "Developer identity • links • engine info") { openAboutFromDrawer() }

        val scrollDrawer = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(drawer)
        }

        val holder = FrameLayout(this)
        holder.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.addView(scrollDrawer, FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        pageFrame.addView(holder)
        drawerRoot = holder

        overlay.animate().alpha(1f).setDuration(220).setInterpolator(ease).start()
        drawer.animate().translationX(0f).setDuration(260).setInterpolator(ease).start()
    }

    private fun closeDrawer(animated: Boolean = true) {
        val holder = drawerRoot ?: run { drawerOpen = false; return }
        drawerRoot = null
        drawerOpen = false
        if (!animated) { pageFrame.removeView(holder); return }
        val drawer = (holder.getChildAt(1) as? ScrollView)?.getChildAt(0)
        val overlay = holder.getChildAt(0)
        overlay?.animate()?.alpha(0f)?.setDuration(180)?.start()
        drawer?.animate()?.translationX(-(drawer.width.takeIf { it > 0 } ?: dp(280)).toFloat())
            ?.setDuration(200)?.setInterpolator(ease)?.withEndAction {
                pageFrame.removeView(holder)
            }?.start() ?: pageFrame.removeView(holder)
    }

    // =========================================================================
    // Boot sequence
    // =========================================================================

    /**
     * Intimidating cascading hacker boot terminal — replaces the plain
     * "loading processors" checklist with an authentic dark cyber-threat
     * penetration-terminal cascade (kernel/root/air-gap/codec/AI-core/
     * engine-standby diagnostics), still pure Matrix Cyber Green monospace
     * on the Deepest Void background, same lightweight per-line reveal via
     * one self-reposting Handler Runnable as before (no new dependencies).
     * Tuned to a fast ~1.2s total cascade, then a 120 FPS fade straight
     * into the Home Dashboard via base()'s own hardware-layered transition.
     */
    /**
     * Intimidating cascading hacker boot terminal — pure, borderless,
     * high-density terminal text streaming directly onto the Deepest Void
     * canvas (no glassCard box, no border, no title chrome — just the
     * stream itself in Matrix Cyber Green monospace), a long dense
     * top-to-bottom ~23-line cascade ending with a blinking `_` cursor
     * before a 120 FPS fade into the Home Dashboard. Same lightweight
     * per-line reveal via one self-reposting Handler Runnable as before (no
     * new dependencies), defensively hardened: every tick is wrapped in
     * try-catch and bails out straight to showHome() on any unexpected
     * exception (a device quirk mid-cascade should never stick the user on
     * a frozen boot screen), and checks `isFinishing`/`isDestroyed` before
     * touching any view so a Runnable that fires after the Activity is
     * already going away is a safe no-op instead of a crash.
     */
    private fun showBoot() {
        val r = base()
        val lines = listOf(
            "[BOOT_STAGE_01] 0x7FFF01",
            "> KERNEL: ATTACHED",
            "> ROOT_UID: 0 (SUPERUSER)",
            "> BYPASS_SANDBOX: ACTIVE",
            "> AIRGAP_PROTOCOL: ENFORCED",
            "> ZERO_CLOUD_LEAK: VERIFIED",
            "> VPU_HARDWARE: MOUNTED",
            "> AVC_DECODER: ARMED",
            "> HEVC_PIPELINE: READY",
            "> AUDIO_PCM_DSP: ACTIVE",
            "> DIRECT_BUFFER: 256KB",
            "> GC_OVERHEAD: 0ms",
            "> PDF_RASTER_CORE: ONLINE",
            "> LZW_GIF_ENGINE: LOADED",
            "> AES_CRYPTO_VAULT: LOCKED",
            "> SCOPED_STORAGE: IS_PENDING",
            "> QUANT_AI_ENGINE: ARMED",
            "> TECHNICAL_CONVERGENCE: OK",
            "> MEMORY_HEAP: 0x7FFF80",
            "> 999999_MEDIA_ENGINES: STANDBY",
            "> SYSTEM_INTEGRITY: 100%",
            "> EXPLOIT_PAYLOAD: GRANTED",
            "> OVERCLOCK: 6.66.0_SYSTEM",
            "> STATUS: ROOT_OPERATIONAL"
        )
        val tv = mono("", 10.5f, cyberBlossom)
        r.addView(tv)

        val handler = Handler(mainLooper)
        var i = 0
        var blinkCount = 0

        val run = object : Runnable {
            override fun run() {
                try {
                    if (isFinishing || isDestroyed) return
                    when {
                        i < lines.size -> {
                            tv.text = lines.take(i + 1).joinToString("\n") + "_"
                            i++
                            handler.postDelayed(this, 50)
                        }
                        blinkCount < 3 -> {
                            val revealed = lines.joinToString("\n")
                            tv.text = if (blinkCount % 2 == 0) revealed else "${revealed}_"
                            blinkCount++
                            handler.postDelayed(this, 70)
                        }
                        else -> showHome()
                    }
                } catch (_: Exception) {
                    // Whatever went wrong mid-cascade, the user must never
                    // be stranded on a frozen boot screen — fail straight
                    // through to Home.
                    try { if (!isFinishing && !isDestroyed) showHome() } catch (_: Exception) { }
                }
            }
        }
        handler.postDelayed(run, 80)
    }

    // =========================================================================
    // Home
    // =========================================================================

    // ---- Real system stats (no hardcoded/randomized values; every read is
    // wrapped so a device quirk degrades to "N/A" instead of crashing) ------

    private fun readRamInfo(): Pair<Long, Long> = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.availMem / (1024 * 1024)) to (mi.totalMem / (1024 * 1024))
    } catch (e: Exception) { -1L to -1L }

    private fun readStorageInfo(): Pair<Long, Long> = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        (stat.availableBlocksLong * blockSize) to (stat.blockCountLong * blockSize)
    } catch (e: Exception) { -1L to -1L }

    private fun readBatteryPercent(): Int = try {
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) { -1 }

    /** This app process's own CPU usage % between successive samples (via Process.getElapsedCpuTime(), no /proc parsing — safe on every API level, never throws SecurityException). */
    private fun readAppCpuPercent(): Int = try {
        val now = SystemClock.elapsedRealtime()
        val cpuNow = Process.getElapsedCpuTime()
        val percent = if (lastCpuSampleRealtime > 0 && now > lastCpuSampleRealtime) {
            (((cpuNow - lastCpuSampleProcessTime).toDouble() / (now - lastCpuSampleRealtime).toDouble()) * 100).toInt().coerceIn(0, 100)
        } else 0
        lastCpuSampleRealtime = now; lastCpuSampleProcessTime = cpuNow
        percent
    } catch (e: Exception) { 0 }

    private fun fmtGb(bytes: Long): String = if (bytes >= 0) String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0) else "N/A"

    // ---- 02: Time of day (Pagi / Siang / Sore / Malam) ---------------------

    private fun timeOfDayRow(): LinearLayout {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val (idLabel, enLabel, icon) = when (hour) {
            in 4..10 -> Triple("Selamat Pagi", "Good Morning", "🌅")
            in 11..14 -> Triple("Selamat Siang", "Good Afternoon", "☀️")
            in 15..17 -> Triple("Selamat Sore", "Good Evening", "🌇")
            else -> Triple("Selamat Malam", "Good Night", "🌙")
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(icon, 22f, white).apply { setPadding(0, 0, dp(10), 0) })
        row.addView(heading(bi(idLabel, enLabel), 20f, white))
        return row
    }

    // ---- 04: LIVE Monitor — one integrated card, three states --------------

    /**
     * ONE integrated LIVE component: label + pulsing dot + calm-idle/active
     * scanner + engine state, matching the IDLE → PROCESSING → COMPLETED →
     * IDLE state machine. Tapping it while a task is running (or just
     * finished) opens the full Processing/Result screen without losing it.
     * Polls its own state every 500ms rather than relying on onTaskTick, so
     * it works correctly whether or not a task happens to be active yet.
     */
    private fun liveMonitorCard(): LinearLayout {
        val card = glassCard(stroke = mint)
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = text("●", 11f, mint)
        topRow.addView(dot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        topRow.addView(TextView(this).apply {
            text = "LIVE"; textSize = 11f; setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.12f
        })
        val stateLabel = text(bi("PEMANTAUAN ENGINE", "ENGINE MONITORING"), 10.5f, mint).apply { letterSpacing = 0.06f }
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        topRow.addView(stateLabel)
        card.addView(topRow)

        val jobLabel = text(bi("Tidak ada proses aktif", "No active process"), 13.5f, gray)
            .apply {
                setPadding(0, dp(6), 0, dp(10))
                // Rock-solid container: this label's text length varies a lot
                // across the 30+ engines and their per-tick stage strings —
                // locking it to a single ellipsized line means the card's
                // height never jumps as that text changes underneath it.
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        card.addView(jobLabel)

        val scanner = LiveScannerView(this, panel, cyberBlossom, neonRose).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24))
        }
        card.addView(scanner)
        activeLiveScanner = scanner

        applyTapFeedback(card, 0.97f) {
            if (taskRunning || taskJustFinished != null) showProcessingScreen(taskType, taskOp, startNew = false)
        }

        val handler = Handler(mainLooper)
        val refresh = object : Runnable {
            override fun run() {
                if (activeLiveScanner !== scanner) return // this card is no longer on-screen — stop polling
                if (activityActive) {
                    when {
                        taskRunning -> {
                            scanner.mode = LiveScannerView.PROCESSING
                            scanner.percent = taskPercent
                            stateLabel.text = bi("MEMPROSES", "PROCESSING")
                            jobLabel.text = "${displayOp(taskOp)}  •  $taskPercent%  •  ${taskStage.ifBlank { "…" }}"
                        }
                        System.currentTimeMillis() < liveMonitorCompletedUntil -> {
                            scanner.mode = LiveScannerView.COMPLETED
                            stateLabel.text = bi("SELESAI", "COMPLETED")
                            jobLabel.text = bi("Proses selesai", "Process finished")
                        }
                        else -> {
                            scanner.mode = LiveScannerView.IDLE
                            stateLabel.text = bi("PEMANTAUAN ENGINE", "ENGINE MONITORING")
                            jobLabel.text = bi("Tidak ada proses aktif", "No active process")
                        }
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        refresh.run()
        return card
    }

    // ---- 05: System Monitor — real CPU / RAM / Storage / Battery ----------

    private fun systemMonitorCard(): LinearLayout {
        val card = glassCard(stroke = panelStroke)
        card.addView(text(bi("MONITOR SISTEM", "SYSTEM MONITOR"), 10f, metaGray).apply { letterSpacing = 0.1f; setPadding(0, 0, 0, dp(10)) })

        val cpuVal = mono("--", 15f, white)
        val ramVal = mono("--", 15f, white)
        val storageVal = mono("--", 15f, white)
        val batteryVal = mono("--", 15f, white)

        fun metricCell(label: String, valueView: TextView): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(label, 9.5f, metaGray))
                addView(valueView.apply { setPadding(0, dp(2), 0, 0) })
            }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(metricCell("CPU", cpuVal))
        row1.addView(metricCell("RAM", ramVal))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        row2.addView(metricCell(bi("PENYIMPANAN", "STORAGE"), storageVal))
        row2.addView(metricCell(bi("BATERAI", "BATTERY"), batteryVal))
        card.addView(row1); card.addView(row2)

        val token = Any()
        activeSystemMonitorToken = token
        val handler = Handler(mainLooper)
        val refresh = object : Runnable {
            override fun run() {
                if (activeSystemMonitorToken !== token) return
                if (activityActive) {
                    cpuVal.text = "${readAppCpuPercent()}%"
                    val (availMb, totalMb) = readRamInfo()
                    ramVal.text = if (totalMb > 0) "${totalMb - availMb}/${totalMb} MB" else "N/A"
                    val (availBytes, totalBytes) = readStorageInfo()
                    storageVal.text = if (totalBytes > 0) "${fmtGb(totalBytes - availBytes)} / ${fmtGb(totalBytes)}" else "N/A"
                    val battery = readBatteryPercent()
                    batteryVal.text = if (battery >= 0) "$battery%" else "N/A"
                }
                handler.postDelayed(this, 4000) // low-frequency refresh — efficient, no meaningful battery cost
            }
        }
        refresh.run()
        return card
    }

    // ---- 06: Akses Lumi — header (title + live waveform) + 2 access cards --

    /** "AKSES LUMI" title (Diamond White) on the left, a fast animated Cyber Green zigzag neural-voice waveform on the right. */
    private fun aksesLumiHeader(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = "AKSES LUMI"
            textSize = 10f
            setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val wave = ZigzagWaveformView(this, cyberBlossom).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(20))
        }
        row.addView(wave)
        activeLumiWaveform = wave
        return row
    }

    /** Left: [ ⌸ MEDIA VAULT ] — in-app file manager (single + Select-All bulk delete, already implemented in showVault()). Right: [ 📈 QUANT // MARKET ] — opens the Quant Terminal. */
    private fun aksesLumiCards(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun card(icon: String, label: String, action: () -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = shape(panel, panelStroke, 14f)
                setPadding(dp(14), dp(18), dp(14), dp(18))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
                addView(text(icon, 21f, cyberBlossom).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) })
                addView(text(label, 11f, white).apply { gravity = Gravity.CENTER; letterSpacing = 0.03f })
                applyTapFeedback(this) { action() }
            }
        row.addView(card("⌸", "MEDIA VAULT") { openVaultFromHome() })
        row.addView(card("📈", "QUANT // MARKET") { openQuantMarket() }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 0 })
        return row
    }

    private fun showHome() {
        val r = base()
        screenState = ScreenState.HOME
        // Silent, safe temp-file maintenance on every Home visit — never
        // while a task is actively running (never touches an in-progress
        // operation's own temp files), never original/output files.
        if (!taskRunning) engine.purgeTempFiles()

        // 01 — Header: minimal navigation & identity only.
        // Status capsule is PINNED to "• Engine Ready" in every device
        // language, Indonesian included — never "• Mesin Siap" — per spec.
        topBar("MEDIACOMPRESSOR", showBack = false, statusLabel = "• Engine Ready", statusColor = mint)

        // 02 — Time of day.
        r.addView(timeOfDayRow().apply { setPadding(0, dp(20), 0, 0) })
        r.addView(text("Vr3tH🇵🇸  •  ${bi("Mesin lokal, offline sepenuhnya", "Fully local, fully offline engine")}", 11.5f, pinkSoft).apply { setPadding(0, dp(4), 0, 0) })

        // 03 — Realtime clock.
        r.addView(liveClockWidget())
        r.addView(line())

        // 04 — LIVE monitor.
        r.addView(liveMonitorCard())
        r.addView(line())

        // 05 — System monitor.
        r.addView(systemMonitorCard())
        r.addView(line())

        // 06 — Akses Lumi.
        r.addView(aksesLumiHeader().apply { setPadding(0, dp(18), 0, dp(10)) })
        r.addView(line())
        r.addView(aksesLumiCards())

        // 07 — Command Center.
        section("COMMAND CENTER", bi("Pilih ranah media", "Choose a media domain"))
        addToolRow("VIDEO", "Compress  •  Merge  •  Trim / Reverse  •  Speed") { openCategoryFromHome("VIDEO") }
        addToolRow("IMAGE", "Compress  •  Batch  •  Convert  •  GIF") { openCategoryFromHome("IMAGE") }
        addToolRow("GIF", "Convert  •  Compress  •  Photo sequence") { openCategoryFromHome("GIF") }
        addToolRow("AUDIO", "Trim / Reverse  •  Merge  •  Volume") { openCategoryFromHome("AUDIO") }
        addToolRow("PDF", "Compress  •  Merge  •  Split / Reverse  •  Secure") { openCategoryFromHome("PDF") }
        addToolRow("ARCHIVE", "Create  •  Extract  •  Recompress") { openCategoryFromHome("ARCHIVE") }

        r.addView(text("LOCAL PROCESSING • NO CLOUD UPLOAD", 10f, metaGray).apply { setPadding(0, dp(16), 0, 0) })

        // Bug fix #2: restore the exact scroll position the user was at
        // last time they left Home (e.g. via Back from a category), instead
        // of always resetting to the very top.
        rememberScroll("HOME")
    }

    private fun openCategoryFromHome(type: String) {
        categoryOrigin = { showHome() }
        showCategory(type)
    }

    /** Drawer item tapped: back from the category screen returns to Home with the Drawer re-opened. */
    private fun openCategoryFromDrawer(type: String) {
        categoryOrigin = { showHome(); openDrawer() }
        showCategory(type)
    }

    private fun openVaultFromHome() {
        vaultOrigin = { showHome() }
        showVault()
    }

    /** Drawer item tapped: back from Media Vault returns to Home with the Drawer re-opened. */
    private fun openVaultFromDrawer() {
        vaultOrigin = { showHome(); openDrawer() }
        showVault()
    }

    /** Settings is a Drawer-only screen: Back always returns to Home with the Drawer re-opened. */
    private fun openSettingsFromDrawer() {
        settingsOrigin = { showHome(); openDrawer() }
        showSettings()
    }

    /** About is a Drawer-only screen: Back always returns to Home with the Drawer re-opened. */
    private fun openAboutFromDrawer() {
        aboutOrigin = { showHome(); openDrawer() }
        showAbout()
    }

    private fun showCategory(type: String) {
        val r = base()
        screenState = ScreenState.OTHER
        topBar(type, showBack = true, onBack = { categoryOrigin() })
        r.addView(heading("$type PROCESSOR", 23f, white).apply { setPadding(0, dp(22), 0, dp(2)) })
        r.addView(text("Select an operation", 12f, metaGray))
        r.addView(line())
        val ops = when (type) { "VIDEO" -> videoOps; "GIF" -> gifFileOps; "IMAGE" -> imageOps; "AUDIO" -> audioOps; "PDF" -> pdfOps; else -> archiveOps }
        ops.forEach { op ->
            val clean = displayOp(op)
            addToolRow(clean, operationSubtitle(op)) {
                currentOp = op
                cfgRangeStartText = null
                cfgRangeEndText = null
                // Smart file retention: a file of this exact category is
                // already loaded in memory — jump straight into the tool's
                // Workspace with it instead of reopening the picker. Only a
                // type mismatch (or nothing loaded yet) still needs a fresh
                // pick; the picker also reopens later if the user explicitly
                // taps "↻ Ganti File" inside the Workspace. Single-only ops
                // (e.g. "Photo Compress") deterministically use just the
                // first loaded file rather than silently carrying an
                // ambiguous multi-file selection into a workspace that can
                // only ever process one of them.
                if (selectedUris.isNotEmpty() && selectedType == type) {
                    if (!acceptsMultiple(op) && selectedUris.size > 1) {
                        val first = selectedUris.first()
                        selectedUris = mutableListOf(first)
                    }
                    showFeature(type, op, if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else fileName(selectedUris.first()))
                } else {
                    selectedType = type
                    selectedUris.clear()
                    pickFile(acceptsMultiple(op))
                }
            }
        }
        r.addView(button("‹  BACK TO COMMAND CENTER") { categoryOrigin() })

        // Bug fix #2: restore this Category List's exact scroll position on
        // Back re-entry, keyed per-category so VIDEO/IMAGE/etc. each keep
        // their own remembered position independently.
        rememberScroll("CATEGORY:$type")
    }

    private fun displayOp(op: String): String = when (op) {
        "Video Rotate & Flip" -> "Video Rotate"
        "Video Compress & Mute" -> "Video Compress"
        else -> op.replace(" & ", " / ")
    }

    private fun operationSubtitle(op: String): String = when (op) {
        "Video Compress & Mute" -> "H.264 • quality profiles • keep / mute audio"
        "Video Trim & Reverse" -> "One timeline • A < B trim • A > B reverse"
        "Video Speed" -> "0.25× — 2.0× playback speed"
        "Video to Audio" -> "Extract original audio stream • M4A"
        "Video to GIF" -> "Clip range • FPS • output width"
        "Extract Frame" -> "Capture a frame at an exact timestamp"
        "Video Merge" -> "Multiple clips • ordered output"
        "Video Rotate & Flip" -> "Rotation • 90° / 180° / 270° • Flip H / V"
        "GIF to Video" -> "Convert animated GIF to MP4"
        "GIF Compress" -> "Re-encode animated GIF with size control"
        "Photo Compress" -> "Target size in KB • optional"
        "Batch Photo Compress" -> "Multiple images • one compression profile"
        "Image Converter" -> "JPG • PNG • WEBP"
        "Remove EXIF" -> "Strip image metadata"
        "Photo to GIF" -> "Image sequence • frame delay • width"
        "Audio Trim & Reverse" -> "One timeline • A < B trim • A > B reverse"
        "Audio Merge" -> "Multiple tracks • ordered output"
        "Audio Compress" -> "Auto Smart AAC VBR • True Size Guard"
        "Audio Volume Booster" -> "Gain control with anti-clip limit"
        "Audio Silence Trimmer" -> "Detect and remove leading / trailing silence"
        "Photo to PDF" -> "Multiple images • one PDF"
        "PDF to Photo" -> "Render PDF pages to images"
        "Merge PDF" -> "Multiple documents • ordered output"
        "Split & Reverse PDF" -> "Page range • forward or reverse output"
        "Compress PDF" -> "Optimize PDF size locally"
        "PDF to Grayscale" -> "Convert pages to grayscale"
        "Watermark PDF" -> "Text watermark • custom content"
        "Lock PDF" -> "Password protection"
        "Unlock PDF" -> "Remove password protection"
        "Create ZIP" -> "Pack multiple files into ZIP"
        "Extract ZIP" -> "Extract archive contents"
        "ZIP Recompress" -> "Rebuild ZIP archive locally"
        else -> "Offline processing workspace"
    }

    // =========================================================================
    // File picking (initial pick + quick re-pick)
    // =========================================================================

    /** Contextual MIME type per category, so the chooser (and every app that responds to it) can filter/prioritize sensibly instead of showing every file type for an Image or Audio pick. Archives/unknown stay wide open. */
    private fun mimeHintFor(category: String): String = when (category) {
        "IMAGE" -> "image/*"
        "VIDEO" -> "video/*"
        "AUDIO" -> "audio/*"
        "PDF" -> "application/pdf"
        "GIF" -> "image/*"
        else -> "*/*"
    }

    /**
     * Builds a universal file-selection chooser: `ACTION_GET_CONTENT` as the
     * primary intent (resolves to gallery/media apps like Google Photos, and
     * to file managers such as ZArchiver, MT Manager, or MiXplorer that only
     * implement GET_CONTENT, not the Storage Access Framework), with
     * `ACTION_OPEN_DOCUMENT` offered alongside it via `EXTRA_INITIAL_INTENTS`
     * so SAF-only document providers still show up too. `Intent.createChooser()`
     * merges both sets of matching apps into one system picker drawer, so the
     * widest possible range of third-party apps — file managers, the default
     * device Gallery, Google Photos — appears across API 24 through 35+,
     * regardless of which intent action they individually declared support
     * for. [category] (VIDEO/IMAGE/AUDIO/PDF/GIF/ARCHIVE/UNKNOWN) selects a
     * contextual MIME type via [mimeHintFor] rather than always `*/*`, so the
     * chooser and every app in it can filter sensibly for what's actually
     * being picked.
     */
    private fun buildFileChooserIntent(allowMultiple: Boolean, category: String = selectedType): Intent {
        val mime = mimeHintFor(category)
        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return Intent.createChooser(getContentIntent, bi("Buka dengan File Manager / Galeri", "Open with File Manager / Gallery")).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(docIntent))
        }
    }

    private fun pickFile(allowMultiple: Boolean) {
        repickCallback = null
        startActivityForResult(buildFileChooserIntent(allowMultiple, selectedType), if (allowMultiple) REQ_PICK_MULTI else REQ_PICK_SINGLE)
    }

    /** "↻ Ganti File" — relaunches the picker and routes the new file straight back, no Home hop. */
    private fun quickRepick(allowMultiple: Boolean, onPicked: (List<Uri>) -> Unit) {
        repickCallback = onPicked
        startActivityForResult(buildFileChooserIntent(allowMultiple, selectedType), if (allowMultiple) REQ_REPICK_MULTI else REQ_REPICK_SINGLE)
    }

    /**
     * "[ + Tambah File Lain ]" — for multi-input ops only: relaunches the
     * picker for another folder/source and ACCUMULATES whatever's picked
     * onto the existing [selectedUris] (see REQ_ADD_MORE_MULTI in
     * onActivityResult) instead of replacing it, fixing the multi-folder
     * reset bug in Batch Photo Compress, Merge PDF, Create ZIP, Video
     * Merge, Audio Merge, and every other multi-input op.
     */
    private fun quickAddMore(onPicked: (List<Uri>) -> Unit) {
        repickCallback = onPicked
        startActivityForResult(buildFileChooserIntent(true, selectedType), REQ_ADD_MORE_MULTI)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_SINGLE -> {
                data?.data?.let {
                    selectedUris = mutableListOf(it)
                    val op = currentOp
                    if (op.isNotBlank()) showFeature(selectedType, op, fileName(it)) else analyze(it)
                }
            }
            REQ_PICK_MULTI -> {
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) } ?: data?.data?.let { uris.add(it) }
                selectedUris.addAll(uris.filter { it !in selectedUris })
                if (selectedUris.isNotEmpty()) showFeature(selectedType, currentOp, fileName(selectedUris.first()))
            }
            REQ_REPICK_SINGLE -> {
                data?.data?.let { uri ->
                    selectedUris = mutableListOf(uri)
                    repickCallback?.invoke(selectedUris)
                }
            }
            REQ_REPICK_MULTI -> {
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) } ?: data?.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) {
                    selectedUris = uris
                    repickCallback?.invoke(selectedUris)
                }
            }
            REQ_ADD_MORE_MULTI -> {
                // "[ + Tambah File Lain ]" — picking from another folder here
                // must ACCUMULATE onto the existing selection, never replace
                // it (that was the multi-folder reset bug: selecting 5 photos
                // from Folder A then adding more from Folder B used to wipe
                // the first 5). Dedupe by URI so re-picking the same file
                // twice doesn't double it up in the batch.
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) } ?: data?.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) {
                    selectedUris.addAll(uris.filter { it !in selectedUris })
                    repickCallback?.invoke(selectedUris)
                }
            }
        }
    }

    private fun fileName(uri: Uri): String {
        var name = "unknown"
        val c: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        c?.use { if (it.moveToFirst()) name = it.getString(0) }
        return name
    }

    private fun fileSize(uri: Uri): Long {
        var size = -1L
        val c: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        c?.use { if (it.moveToFirst()) size = it.getLong(0) }
        return size
    }

    /** Obsidian glass file-metadata card with the inline "↻ Ganti File" quick re-pick capsule. */
    /**
     * File header for the Feature Configuration screen. Single-file ops
     * ([allowMultiple] = false) keep the plain "[ ↻ Ganti File ]" swap
     * button. Multi-input ops instead show an accumulation badge plus two
     * dedicated actions: "[ + Tambah File Lain ]" ADDS another folder's
     * picks onto the existing selection (via [quickAddMore], which
     * accumulates rather than replaces — the fix for the bug where picking
     * more files from a second folder used to silently wipe out the first
     * folder's picks), and "[ ✕ Reset ]" clears the batch and returns to
     * the category list to start over.
     */
    private fun fileHeaderCard(name: String, metaLine: String, allowMultiple: Boolean, onReplaced: () -> Unit) {
        val card = glassCard(stroke = panelStroke)
        // Tapping anywhere on the card re-enters Feature Configuration with
        // previous settings retained; the nested capsule button(s) below
        // still consume their own tap to launch their own flow instead.
        applyTapFeedback(card, 0.985f) { onReplaced() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = name; textSize = 16.5f; setTextColor(white); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleCol.addView(text(metaLine, 11f, roseGold).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!allowMultiple) {
            val repickBtn = TextView(this).apply {
                text = bi("[ ↻ Ganti File ]", "[ ↻ Change File ]")
                textSize = 11.5f
                setTextColor(cyberBlossom)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = shape(Color.TRANSPARENT, cyberBlossom, 20f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            applyTapFeedback(repickBtn, 0.95f) {
                quickRepick(false) { onReplaced() }
            }
            row.addView(repickBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        }
        card.addView(row)

        if (allowMultiple) {
            card.addView(text(bi("[ ${selectedUris.size} FILE TERKUMPUL ]", "[ ${selectedUris.size} FILES ACCUMULATED ]"), 11f, cyberBlossom).apply {
                setPadding(0, dp(10), 0, 0)
            })
            val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            val addMoreBtn = TextView(this).apply {
                text = bi("[ + Tambah File Lain ]", "[ + Add More Files ]")
                textSize = 11.5f
                setTextColor(cyberBlossom)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = shape(Color.TRANSPARENT, cyberBlossom, 20f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            applyTapFeedback(addMoreBtn, 0.95f) {
                quickAddMore { onReplaced() }
            }
            actionRow.addView(addMoreBtn)
            val resetBtn = TextView(this).apply {
                text = bi("[ ✕ Reset ]", "[ ✕ Reset ]")
                textSize = 11.5f
                setTextColor(warn)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = shape(Color.TRANSPARENT, warn, 20f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            applyTapFeedback(resetBtn, 0.95f) {
                selectedUris.clear()
                showCategory(selectedType)
            }
            actionRow.addView(resetBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
            card.addView(actionRow)
        }

        root.addView(card)
    }

    // =========================================================================
    // Analyze screen
    // =========================================================================

    private fun analyze(uri: Uri) {
        val name = fileName(uri)
        val size = fileSize(uri)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        val type = when {
            ext in setOf("mp4", "mkv", "mov", "3gp", "3g2", "webm", "avi", "m4v", "ts") -> "VIDEO"
            ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr") -> "AUDIO"
            ext == "gif" -> "GIF"
            ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif") -> "IMAGE"
            ext == "pdf" -> "PDF"
            ext == "zip" -> "ARCHIVE"
            else -> when (MimeDetector.sniff(this, uri, name)) {
                MimeDetector.Kind.VIDEO -> "VIDEO"; MimeDetector.Kind.AUDIO -> "AUDIO"; MimeDetector.Kind.IMAGE -> "IMAGE"
                MimeDetector.Kind.PDF -> "PDF"; MimeDetector.Kind.ARCHIVE -> "ARCHIVE"; else -> "UNKNOWN"
            }
        }
        selectedType = type
        val r = base()
        screenState = ScreenState.ANALYZE
        topBar("INPUT", showBack = true, statusLabel = "Verified", statusColor = mint, onBack = { showHome() })
        fileHeaderCard(name, "$type  •  ${formatSize(size)}", allowMultiple = false) {
            // Bug fix #3: if an operation is already active for this file
            // (currentOp set by a previously-tapped tool row), tapping the
            // header card re-opens that Feature Configuration in place
            // instead of just refreshing this Analysis screen.
            if (currentOp.isNotBlank() && selectedUris.isNotEmpty()) {
                showFeature(type, currentOp, fileName(selectedUris.first()), quiet = true)
            } else {
                analyze(selectedUris.first())
            }
        }

        if (type == "VIDEO" || type == "AUDIO") {
            try {
                val mmr = MediaMetadataRetriever(); mmr.setDataSource(this, uri)
                val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                r.addView(glassCard().apply {
                    addView(mono("DURATION     ${formatDuration(duration?.toLongOrNull() ?: 0)}\nFORMAT       ${mime ?: "unknown"}", 11f, gray))
                })
                mmr.release()
            } catch (_: Exception) { r.addView(text("INPUT METADATA LIMITED", 11f, warn)) }
        }
        if (type == "UNKNOWN") {
            r.addView(text("\nPROCESSING LOCKED\nUnsupported media type.", 13f, err))
            return
        }
        section("AVAILABLE TOOLS", "Choose one operation")
        val ops = when (type) { "VIDEO" -> videoOps; "GIF" -> gifFileOps; "IMAGE" -> imageOps; "AUDIO" -> audioOps; "PDF" -> pdfOps; else -> archiveOps }
        ops.forEach { op ->
            addToolRow(displayOp(op), operationSubtitle(op)) {
                currentOp = op
                cfgRangeStartText = null
                cfgRangeEndText = null
                // Smart file retention: a file is already loaded on this
                // very screen (that's how Analysis got here), so tapping
                // any tool for it — multi-input ops included — goes
                // straight into that tool's Workspace with what's already
                // loaded. The picker only reopens if the user explicitly
                // taps "↻ Ganti File" once inside the Workspace (which
                // already offers multi-select there via fileHeaderCard's
                // allowMultiple = acceptsMultiple(op)), never automatically
                // on tool-selection.
                if (selectedUris.isNotEmpty()) {
                    selectedType = type
                    showFeature(type, op, name)
                } else {
                    selectedType = type
                    if (acceptsMultiple(op)) pickFile(true) else showFeature(type, op, name)
                }
            }
        }
        r.addView(button("‹  NEW MEDIA") { showHome() })
    }

    // =========================================================================
    // Feature configuration screen
    // =========================================================================

    /**
     * [quiet] = true is a same-screen re-entry (tapping the File Header
     * Card while this exact Feature Configuration is already showing) —
     * skips the fade/slide-in entrance so it updates in place instead of
     * visibly blinking, per bug fix #3. All cfg* parameter state is
     * class-level already, so it's preserved automatically either way.
     */
    private fun showFeature(type: String, op: String, name: String, quiet: Boolean = false) {
        val r = base(animate = !quiet)
        screenState = ScreenState.CONFIG
        topBar(displayOp(op), showBack = true, onBack = { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
        r.addView(text("$type WORKSPACE", 10f, pinkSoft).apply { setPadding(0, dp(16), 0, 0) })
        fileHeaderCard(
            if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else name,
            "LOCAL PROCESSING", allowMultiple = acceptsMultiple(op)
        ) {
            // Tapping the header card re-opens THIS same Feature
            // Configuration view — every parameter (quality, range, gain,
            // password, etc.) is already retained via the class-level cfg*
            // fields, so this is a pure "re-enter in place" with no reset.
            showFeature(type, op, if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else fileName(selectedUris.first()), quiet = true)
        }
        r.addView(line())

        when {
            op == "Video Trim & Reverse" -> {
                val totalSec = (selectedUris.firstOrNull()?.let { probeVideo(it) }?.first ?: 0L) / 1_000_000.0
                val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
                rangeUI(r, "TIMELINE", "0", "10", "sec", badgeBuilder = { a, b ->
                    val dur = kotlin.math.abs(b - a)
                    val est = if (totalSec > 0 && sourceBytes > 0) sourceBytes * (dur / totalSec) else -1.0
                    "TIMELINE: ${fmtDuration(a)} \u2192 ${fmtDuration(b)}\n[DURATION: ${String.format(Locale.US, "%.3f", dur)}s]" +
                        (if (est > 0) "  [EST. ~${formatSize(est.toLong())}]" else "")
                }) { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            }
            op == "Audio Trim & Reverse" -> {
                val totalSec = (selectedUris.firstOrNull()?.let { probeVideo(it) }?.first ?: 0L) / 1_000_000.0
                val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
                rangeUI(r, "TIMELINE", "0", "10", "sec", badgeBuilder = { a, b ->
                    val dur = kotlin.math.abs(b - a)
                    val est = if (totalSec > 0 && sourceBytes > 0) sourceBytes * (dur / totalSec) else -1.0
                    "TIMELINE: ${fmtDuration(a)} \u2192 ${fmtDuration(b)}\n[DURATION: ${String.format(Locale.US, "%.3f", dur)}s]" +
                        (if (est > 0) "  [EST. ~${formatSize(est.toLong())}]" else "")
                }) { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            }
            op == "Split & Reverse PDF" -> {
                val totalPages = selectedUris.firstOrNull()?.let { probePdfPageCount(it) } ?: 0
                val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
                rangeUI(r, "PAGE RANGE", "1", "10", "pg", badgeBuilder = { a, b ->
                    val extracted = (kotlin.math.abs(b - a) + 1).toInt()
                    val est = if (totalPages > 0 && sourceBytes > 0) sourceBytes.toDouble() * extracted / totalPages else -1.0
                    val totalLabel = if (totalPages > 0) "$totalPages" else "?"
                    "PAGES: ${a.toInt()} to ${b.toInt()} of $totalLabel\n[$extracted PAGES EXTRACTED / REVERSED]" +
                        (if (est > 0) "  [EST. ~${formatSize(est.toLong())}]" else "")
                }) { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            }
            op == "Video to GIF" -> {
                var refreshGifBadge: (() -> Unit)? = null
                rangeUI(r, "CLIP RANGE", "0", "3", "sec", tooltip = bi(
                    "Rentang Klip (Awal/Akhir)\n24 FPS = Super mulus\n15/12 FPS = Standar\n8 FPS = Retro",
                    "Clip Range (Start/End)\n24 FPS = Super smooth\n15/12 FPS = Standard\n8 FPS = Retro"
                )) { a, b -> cfgRangeStart = a; cfgRangeEnd = b; refreshGifBadge?.invoke() }
                fpsUI(r) { refreshGifBadge?.invoke() }
                val badge = telemetryBadge(r)
                refreshGifBadge = {
                    val durationSec = kotlin.math.abs(cfgRangeEnd - cfgRangeStart)
                    val frames = (durationSec * cfgFps).roundToInt().coerceAtLeast(0)
                    val estBytes = (frames * cfgGifMaxWidth * cfgGifMaxWidth * 0.018).toLong().coerceAtLeast(1)
                    badge.text = "FRAMES: $frames | SPEED: $cfgFps FPS | WIDTH: ${cfgGifMaxWidth}px\n[EST. ~${formatSize(estBytes)}]"
                }
                refreshGifBadge?.invoke()
            }
            op == "Extract Frame" -> rangeUI(r, "TIMESTAMP", "1", "1", "sec", badgeBuilder = { a, _ ->
                val ms = ((a - a.toLong()) * 1000).toInt().coerceIn(0, 999)
                "TARGET TIMESTAMP: ${fmtDuration(a)}.${String.format(Locale.US, "%03d", ms)}\n[HIGH-RES JPEG OUTPUT]"
            }) { a, _ -> cfgRangeStart = a }
            op == "Video Compress & Mute" -> { qualityProfileUI(r); pillSwitchAudioUI(r) }
            op == "Video Speed" -> speedUI(r)
            op == "Video Rotate & Flip" -> rotateUI(r)
            op == "Image Converter" -> formatUI(r)
            op == "Audio Volume Booster" -> gainUI(r)
            op == "Audio Compress" -> audioCompressUI(r)
            op == "Audio Silence Trimmer" -> silenceTrimUI(r)
            op == "Photo Compress" || op == "Batch Photo Compress" -> targetSizeUI(r)
            op == "Lock PDF" || op == "Unlock PDF" -> passwordUI(r)
            op == "Compress PDF" -> pdfCompressUI(r)
            op == "PDF to Grayscale" -> pdfGrayscaleUI(r)
            op == "Watermark PDF" -> watermarkUI(r)
            op == "Photo to GIF" -> {
                var refreshBadge: (() -> Unit)? = null
                fpsUI(r, tooltip = bi("Frame Rate (FPS)\nFrame Delay (ms)", "Frame Rate (FPS)\nFrame Delay (ms)")) { refreshBadge?.invoke() }
                delayUI(r) { refreshBadge?.invoke() }
                val badge = telemetryBadge(r)
                refreshBadge = {
                    val delayMs = cfgDelayCentis * 10
                    val photoCount = selectedUris.size.coerceAtLeast(1)
                    val estBytes = (photoCount * cfgGifMaxWidth * cfgGifMaxWidth * 0.03).toLong().coerceAtLeast(1)
                    badge.text = "FRAMES: $photoCount | SPEED: $cfgFps FPS | DELAY: ${delayMs}ms | WIDTH: ${cfgGifMaxWidth}px\n[EST. ~${formatSize(estBytes)}]"
                }
                refreshBadge?.invoke()
            }
            else -> r.addView(text("No additional parameters required.", 13f, mint))
        }
        r.addView(line())
        r.addView(glassCard(stroke = pinkSoft).apply {
            addView(mono("ENGINE STATUS\n● INPUT VERIFIED\n● CONFIGURATION READY\n● OUTPUT PROTECTED", 11f, pinkSoft))
        })
        r.addView(primaryButton("START PROCESSING   ›") { runOperation(type, op) })
    }

    private fun rangeUI(
        r: LinearLayout, title: String, start: String, end: String, unit: String,
        tooltip: String? = null, badgeBuilder: ((Double, Double) -> String)? = null,
        onValid: (Double, Double) -> Unit
    ) {
        if (tooltip != null) headerWithInfo(r, title, tooltip) else r.addView(text("\n$title", 13f, pinkSoft))
        val s = editText("START", cfgRangeStartText ?: start)
        val e = editText("END", cfgRangeEndText ?: end)
        r.addView(s); r.addView(e)
        val direction = text("", 13f, pinkSoft)
        var badge: TextView? = null
        fun validate() {
            cfgRangeStartText = s.text.toString()
            cfgRangeEndText = e.text.toString()
            val a = parseRange(s.text.toString())
            val b = parseRange(e.text.toString())
            var color = err
            direction.text = when {
                a == null || b == null -> "✕ RANGE REJECTED\nERROR // INVALID_INPUT"
                a == b -> "✕ RANGE REJECTED\nERROR // EMPTY_RANGE"
                else -> {
                    onValid(a, b)
                    val amount = String.format(Locale.US, "%.3f", kotlin.math.abs(b - a))
                    val forward = a < b
                    color = if (forward) mint else pinkElectric
                    badge?.text = badgeBuilder?.invoke(a, b) ?: ""
                    if (forward) "● FORWARD CUT [$amount$unit]" else "● REVERSE SEQUENCE [$amount$unit]"
                }
            }
            direction.setTextColor(color)
        }
        s.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) validate() }
        e.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) validate() }
        r.addView(direction)
        if (badgeBuilder != null) badge = telemetryBadge(r)
        validate()
    }

    private fun parseRange(s: String): Double? {
        return try {
            val parts = s.trim().split(":")
            when (parts.size) {
                1 -> s.toDouble()
                2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                else -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
            }
        } catch (_: Exception) { null }
    }

    // ---- Interactive selection cards (Quality Profiles) --------------------

    /** Quick MediaMetadataRetriever probe: (durationUs, width, height). Best-effort — returns null on any failure. */
    private fun probeVideo(uri: Uri): Triple<Long, Int, Int>? = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(this, uri)
        val d = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        mmr.release()
        Triple(d, w, h)
    } catch (_: Exception) { null }

    /** Quick page-count probe for a PDF Uri. Best-effort — returns null on any failure. */
    private fun probePdfPageCount(uri: Uri): Int? = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (_: Exception) { null }

    private fun fmtDuration(sec: Double): String {
        val total = sec.roundToInt().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun qualityProfileUI(r: LinearLayout) {
        r.addView(text("QUALITY PROFILE", 12f, pinkSoft).apply { setPadding(0, dp(4), 0, 0) })
        val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
        val info = selectedUris.firstOrNull()?.let { uri ->
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(this, uri)
                val d = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                val audio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                mmr.release()
                d to audio
            } catch (_: Exception) { null }
        }

        data class Profile(val label: String, val subtitle: String, val quality: MediaEngine.Quality, val bitrate: Int, val edge: Int)
        val profiles = listOf(
            Profile("HIGH", "Maximum quality profile", MediaEngine.Quality.HIGH, 6_000_000, 1920),
            Profile("MEDIUM", "Balanced size / quality", MediaEngine.Quality.MEDIUM, 3_000_000, 1280),
            Profile("LOW", "Maximum size reduction", MediaEngine.Quality.LOW, 1_200_000, 854)
        )

        val cards = mutableListOf<Pair<LinearLayout, Profile>>()

        fun estimateFor(p: Profile): Long {
            val durationSec = (info?.first ?: 0L) / 1_000_000.0
            val audio = if (info?.second == true) 128_000 else 0
            val raw = if (durationSec > 0) (durationSec * (p.bitrate + audio) / 8.0) else -1.0
            return raw.toLong()
        }

        fun refresh() {
            cards.forEach { (card, p) ->
                val active = p.quality == cfgQuality
                card.background = shape(if (active) activeCardBg else panel, if (active) cyberBlossom else panelStroke, 16f, if (active) 2 else 1)
                val check = card.findViewWithTag<TextView>("check")
                check?.visibility = if (active) View.VISIBLE else View.INVISIBLE
                val badge = card.findViewWithTag<TextView>("badge")
                val est = estimateFor(p)
                if (est > 0 && sourceBytes > 0) {
                    val pct = ((1 - est.toDouble() / sourceBytes) * 100).toInt().coerceIn(-999, 99)
                    val comparison = "${formatSize(sourceBytes)} → ${formatSize(est)} "
                    val delta = "[${if (pct >= 0) "-$pct" else "+${-pct}"}%]"
                    val spannable = android.text.SpannableString(comparison + delta)
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(if (active) gray else metaGray),
                        0, comparison.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(mint),
                        comparison.length, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    badge?.text = spannable
                } else {
                    badge?.text = "EST. OUTPUT UNAVAILABLE"
                    badge?.setTextColor(metaGray)
                }
            }
        }

        profiles.forEach { p ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            }
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            titleCol.addView(TextView(this).apply {
                text = p.label; textSize = 14.5f; setTextColor(white)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = .02f
            })
            titleCol.addView(text(p.subtitle, 10.5f, metaGray).apply { setPadding(0, dp(2), 0, 0) })
            topRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(TextView(this).apply {
                text = "✓"; tag = "check"; textSize = 16f; setTextColor(cyberBlossom)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                visibility = View.INVISIBLE
            })
            card.addView(topRow)
            card.addView(text("", 11f, metaGray).apply { tag = "badge"; setPadding(0, dp(4), 0, 0) })
            applyTapFeedback(card) { cfgQuality = p.quality; refresh() }
            r.addView(card)
            cards.add(card to p)
        }
        refresh()
    }

    // ---- Segmented pill switch (audio ON / MUTE) ----------------------------

    private fun pillSwitchAudioUI(r: LinearLayout) {
        r.addView(text("\nAUDIO", 13f, pinkSoft))
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(panel, panelStroke, 14f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val underlineOn = View(this).apply { background = shape(cyberBlossom, null, 2f) }
        val underlineOff = View(this).apply { background = shape(cyberBlossom, null, 2f); visibility = View.INVISIBLE }
        val repaintCallbacks = mutableListOf<() -> Unit>()

        fun cell(label: String, active: () -> Boolean, underline: View, onTap: () -> Unit): LinearLayout {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(11), dp(10), dp(9))
            }
            val lbl = TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            cell.addView(lbl)
            cell.addView(underline, LinearLayout.LayoutParams(dp(28), dp(3)).apply { topMargin = dp(6) })
            val paint: () -> Unit = {
                val on = active()
                lbl.setTextColor(if (on) white else metaGray)
                underline.visibility = if (on) View.VISIBLE else View.INVISIBLE
            }
            applyTapFeedback(cell) { onTap(); repaintCallbacks.forEach { it() } }
            repaintCallbacks.add(paint)
            return cell
        }

        val onCell = cell("[ ON ]  Keep audio", { !cfgMute }, underlineOn) { cfgMute = false }
        val offCell = cell("[ MUTE ]  Strip instantly", { cfgMute }, underlineOff) { cfgMute = true }
        track.addView(onCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        track.addView(offCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(track)
        repaintCallbacks.forEach { it() }
    }

    private fun speedUI(r: LinearLayout) {
        headerWithInfo(r, "PLAYBACK SPEED", bi(
            "0.25× / 0.5× = Gerak lambat\n0.75× = Sedikit lambat\n1.0× = Normal\n1.25× / 1.5× = Cepat\n2.0× = Timelapse",
            "0.25× / 0.5× = Slow motion\n0.75× = Slightly slower\n1.0× = Normal\n1.25× / 1.5× = Faster\n2.0× = Timelapse"
        ))
        val originalSec = (selectedUris.firstOrNull()?.let { probeVideo(it) }?.first ?: 0L) / 1_000_000.0
        lateinit var badge: TextView
        fun refreshBadge() {
            badge.text = if (originalSec > 0) {
                val newSec = originalSec / cfgSpeed
                val pctChange = (newSec - originalSec) / originalSec * 100
                String.format(
                    Locale.US, "ORIGINAL: %s  →  NEW: %s\n[%+.0f%% TIME (%.2f\u00D7 SPEED)]",
                    fmtDuration(originalSec), fmtDuration(newSec), pctChange, cfgSpeed
                )
            } else "DURATION UNAVAILABLE"
        }
        selectionRow(r, listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0).map { "${it}×" to it }, { cfgSpeed }) { cfgSpeed = it; refreshBadge() }
        badge = telemetryBadge(r)
        refreshBadge()
    }

    private fun rotateUI(r: LinearLayout) {
        headerWithInfo(r, "ROTATION / FLIP", bi(
            "90° = Putar ke kanan (searah jarum jam)\n180° = Putar terbalik atas-bawah\n270° = Putar ke kiri (berlawanan jarum jam)\nFlip H = Cermin horizontal (kiri-kanan)\nFlip V = Cermin vertikal (atas-bawah)",
            "90° = Rotate right (clockwise)\n180° = Flip upside down\n270° = Rotate left (counter-clockwise)\nFlip H = Horizontal mirror (left-right)\nFlip V = Vertical mirror (top-bottom)"
        ))
        val srcInfo = selectedUris.firstOrNull()?.let { probeVideo(it) }
        val srcW = srcInfo?.second ?: 0
        val srcH = srcInfo?.third ?: 0
        lateinit var badge: TextView
        fun refreshBadge() {
            val modeLabel = when (cfgRotateMode) {
                MediaEngine.RotateMode.ROT_90 -> "ROTATE 90°"
                MediaEngine.RotateMode.ROT_180 -> "ROTATE 180°"
                MediaEngine.RotateMode.ROT_270 -> "ROTATE 270°"
                MediaEngine.RotateMode.FLIP_H -> "FLIP H"
                MediaEngine.RotateMode.FLIP_V -> "FLIP V"
            }
            val swapped = cfgRotateMode == MediaEngine.RotateMode.ROT_90 || cfgRotateMode == MediaEngine.RotateMode.ROT_270
            val outW = if (swapped) srcH else srcW
            val outH = if (swapped) srcW else srcH
            badge.text = if (srcW > 0 && srcH > 0) {
                "RESOLUTION: ${srcW}x${srcH}  →  ${outW}x${outH}\n[$modeLabel]  [BITRATE PRESERVED]"
            } else "RESOLUTION UNAVAILABLE"
        }
        selectionRow(
            r,
            listOf(
                "90°" to MediaEngine.RotateMode.ROT_90,
                "180°" to MediaEngine.RotateMode.ROT_180,
                "270°" to MediaEngine.RotateMode.ROT_270,
                bi("Flip H", "Flip H") to MediaEngine.RotateMode.FLIP_H,
                bi("Flip V", "Flip V") to MediaEngine.RotateMode.FLIP_V
            ),
            { cfgRotateMode }
        ) { cfgRotateMode = it; refreshBadge() }
        badge = telemetryBadge(r)
        refreshBadge()
    }

    private fun formatUI(r: LinearLayout) {
        headerWithInfo(r, "OUTPUT FORMAT", bi(
            "JPG = Standar hemat\nPNG = Latar transparan & jernih\nWEBP = Modern super ringan",
            "JPG = Efficient standard\nPNG = Transparent background & crisp\nWEBP = Modern & super light"
        ))
        val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
        val sourceFormat = selectedUris.firstOrNull()?.let { fileName(it).substringAfterLast('.', "?").uppercase() } ?: "?"
        lateinit var badge: TextView
        fun refreshBadge() {
            val (targetLabel, ratio, alphaPreserved) = when (cfgImageFormat) {
                MediaEngine.ImageFormatTarget.JPG -> Triple("JPG", 0.30, false)
                MediaEngine.ImageFormatTarget.PNG -> Triple("PNG", 0.95, true)
                MediaEngine.ImageFormatTarget.WEBP -> Triple("WEBP", 0.22, true)
            }
            if (sourceBytes > 0) {
                val est = (sourceBytes * ratio).toLong()
                val pct = ((1 - ratio) * 100).toInt()
                badge.text = "$sourceFormat (${formatSize(sourceBytes)})  →  $targetLabel\n[EST. ~${formatSize(est)} (-$pct%)]  [ALPHA: ${if (alphaPreserved) "PRESERVED" else "FLATTENED"}]"
            } else badge.text = "SOURCE SIZE UNAVAILABLE"
        }
        selectionRow(
            r,
            listOf("JPG" to MediaEngine.ImageFormatTarget.JPG, "PNG" to MediaEngine.ImageFormatTarget.PNG, "WEBP" to MediaEngine.ImageFormatTarget.WEBP),
            { cfgImageFormat }
        ) { cfgImageFormat = it; refreshBadge() }
        badge = telemetryBadge(r)
        refreshBadge()
    }

    /**
     * Elevated frosted telemetry badge card — the "Universal Adaptive
     * Parameter Telemetry" strip shown on feature workspaces that have a
     * live, source-derived estimate (size, duration, bitrate...). Returns
     * the label TextView so the caller can update it as the user adjusts
     * parameters.
     */
    private fun telemetryBadge(r: LinearLayout, initial: String = "…"): TextView {
        val card = glassCard(stroke = mint).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        card.addView(text(bi("PERKIRAAN LIVE", "LIVE ESTIMATE"), 9.5f, mint).apply { letterSpacing = 0.1f })
        val label = mono(initial, 12f, white).apply { setPadding(0, dp(4), 0, 0) }
        card.addView(label)
        r.addView(card.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) } })
        return label
    }

    private fun gainUI(r: LinearLayout) {
        r.addView(text("\nGAIN (ANTI-CLIP LIMITED)", 13f, pinkSoft))
        val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
        lateinit var badge: TextView
        fun refreshBadge() {
            val multiplier = Math.pow(10.0, cfgGainDb / 20.0)
            val src = if (sourceBytes > 0) formatSize(sourceBytes) else "N/A"
            badge.text = String.format(
                Locale.US, "SOURCE: %s  |  GAIN: %+.0f dB (%.1f\u00D7 BOOST)\n[ANTI-CLIPPING LIMITER ACTIVE]",
                src, cfgGainDb, multiplier
            )
        }
        selectionRow(
            r,
            listOf(-6.0, 0.0, 3.0, 6.0, 9.0, 12.0).map { g -> (if (g >= 0) "+${g} dB" else "${g} dB") to g },
            { cfgGainDb }
        ) { cfgGainDb = it; refreshBadge() }
        badge = telemetryBadge(r)
        refreshBadge()
    }

    private fun audioCompressUI(r: LinearLayout) {
        headerWithInfo(r, "AUDIO COMPRESS", bi(
            "Mode Otomatis Pintar: aplikasi menghitung ulang bitrate AAC dari file asli agar ukurannya lebih kecil.\nTrue Size Guard = hasil tidak akan pernah lebih besar dari file asli.",
            "Auto Smart Mode: the app derives a lower AAC bitrate straight from the source so the output shrinks automatically.\nTrue Size Guard = the result will never exceed the original file."
        ))
        val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
        val src = if (sourceBytes > 0) formatSize(sourceBytes) else "N/A"
        val badge = telemetryBadge(r)
        badge.text = "SOURCE: $src  →  AAC VBR (AUTO)\n[TRUE SIZE GUARD ACTIVE]"
    }

    /** Shared selection-row list with a glowing right-aligned checkmark on the active choice. */
    private fun <T> selectionRow(r: LinearLayout, options: List<Pair<String, T>>, current: () -> T, onPick: (T) -> Unit) {
        val rows = mutableListOf<Pair<LinearLayout, T>>()
        fun refresh() {
            rows.forEach { (row, value) ->
                val active = value == current()
                row.background = shape(if (active) activeCardBg else panel, if (active) cyberBlossom else panelStroke, 13f, if (active) 2 else 1)
                row.findViewWithTag<TextView>("check")?.visibility = if (active) View.VISIBLE else View.INVISIBLE
            }
        }
        options.forEach { (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row.addView(TextView(this).apply {
                text = label; textSize = 13.5f; setTextColor(white)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✓"; tag = "check"; textSize = 15f; setTextColor(cyberBlossom); visibility = View.INVISIBLE
            })
            applyTapFeedback(row) { onPick(value); refresh() }
            r.addView(row)
            rows.add(row to value)
        }
        refresh()
    }

    private fun targetSizeUI(r: LinearLayout) {
        r.addView(text("\nTARGET SIZE (KB) — optional", 13f, pinkSoft))
        val input = editText("e.g. 200 (blank = auto quality)", cfgTargetKb?.toString() ?: "")
        r.addView(input)
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) cfgTargetKb = input.text.toString().trim().toIntOrNull() }
    }

    private fun passwordUI(r: LinearLayout) {
        r.addView(text("\nPASSWORD", 13f, pinkSoft))
        val input = editText("Enter password", cfgPassword)
        r.addView(input)
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) cfgPassword = input.text.toString() }
    }

    private fun watermarkUI(r: LinearLayout) {
        r.addView(text("\nWATERMARK TEXT", 13f, pinkSoft))
        val input = editText("Enter watermark text", cfgWatermarkText)
        r.addView(input)
        val badge = telemetryBadge(r)
        fun refreshBadge() {
            val stampText = cfgWatermarkText.ifBlank { "MEDIACOMPRESSOR" }
            badge.text = "STAMP: \"$stampText\"\n[45° DIAGONAL]  [ALL PAGES STAMPED]"
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) { cfgWatermarkText = input.text.toString().ifBlank { "MEDIACOMPRESSOR" }; refreshBadge() }
        }
        refreshBadge()
    }

    private fun pdfCompressUI(r: LinearLayout) {
        headerWithInfo(r, "COMPRESS PDF", bi(
            "Setiap halaman dirender ulang menjadi gambar RGB_565 pada skala 0.72x, lalu disusun ulang menjadi PDF baru yang lebih ringan.",
            "Every page is re-rasterized to an RGB_565 image at 0.72x scale, then reassembled into a lighter PDF."
        ))
        val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
        val badge = telemetryBadge(r)
        badge.text = if (sourceBytes > 0) {
            // Matches MediaEngine.pdfCompress: 0.72x linear scale + RGB_565 (16bpp vs. typical 24bpp source raster).
            val est = (sourceBytes * 0.72 * 0.72 * (16.0 / 24.0)).toLong().coerceAtLeast(1)
            val pct = ((1 - est.toDouble() / sourceBytes) * 100).toInt().coerceIn(0, 99)
            "ORIGINAL: ${formatSize(sourceBytes)}  →  0.72x RGB_565 RASTER\n[EST. ~${formatSize(est)} (-$pct%)]"
        } else "SOURCE SIZE UNAVAILABLE"
    }

    private fun pdfGrayscaleUI(r: LinearLayout) {
        headerWithInfo(r, "PDF TO GRAYSCALE", bi(
            "Setiap halaman dikonversi dari warna RGB 24-bit menjadi grayscale 8-bit — menghemat tinta cetak.",
            "Every page converts from 24-bit RGB color to 8-bit grayscale — saves printer ink."
        ))
        val badge = telemetryBadge(r)
        badge.text = "COLOR DEPTH: RGB 24-BIT  →  GRAYSCALE 8-BIT\n[INK SAVER ACTIVE]"
    }

    private fun silenceTrimUI(r: LinearLayout) {
        headerWithInfo(r, "SILENCE TRIMMER", bi(
            "Ambang batas -40dB: bagian hening di awal dan akhir trek dipotong otomatis.",
            "Threshold -40dB: silent leading/trailing sections of the track are trimmed automatically."
        ))
        val badge = telemetryBadge(r)
        badge.text = "SILENCE THRESHOLD: < -40 dB\n[AUTO-PURGE LEADING/TRAILING SILENCE]"
    }

    private fun fpsUI(r: LinearLayout, tooltip: String? = null, onChange: (() -> Unit)? = null) {
        if (tooltip != null) headerWithInfo(r, "FRAME RATE", tooltip) else r.addView(text("\nFRAME RATE", 13f, pinkSoft))
        selectionRow(r, listOf(8, 12, 15, 24).map { "$it FPS" to it }, { cfgFps }) { cfgFps = it; onChange?.invoke() }
    }

    private fun delayUI(r: LinearLayout, tooltip: String? = null, onChange: (() -> Unit)? = null) {
        if (tooltip != null) headerWithInfo(r, "FRAME DELAY", tooltip) else r.addView(text("\nFRAME DELAY", 13f, pinkSoft))
        selectionRow(r, listOf(10 to "100ms", 20 to "200ms", 50 to "500ms").map { it.second to it.first }, { cfgDelayCentis }) { cfgDelayCentis = it; onChange?.invoke() }
    }

    // =========================================================================
    // Live processing panel: idle → processing → completed, all in one card
    // =========================================================================

    /** Runs MediaEngine.finalize() + MediaStore publish for every raw EngineResult — always, exactly once, in the worker thread's own completion handler, regardless of which screen (if any) is visible when the task finishes. */
    private fun finalizeOutcomes(type: String, op: String, outcomes: List<EngineResult>): List<FinalizedOutcome> {
        return outcomes.map { outcome ->
            when (outcome) {
                is EngineResult.Success -> {
                    val saved = engine.finalize(outcome.outputFile, outcome.outputFile.name.removePrefix(".tmp_"))
                    val mime = guessMime(saved.name)
                    val publishedUri = MediaStoreExporter.publish(this, saved, subfolderFor(op), mime)
                    FinalizedOutcome.Done(saved.name, outcome.inputBytes, outcome.outputBytes, outcome.note, publishedUri)
                }
                is EngineResult.Rejected -> FinalizedOutcome.Rejected(outcome.reason)
                is EngineResult.Failure -> FinalizedOutcome.Failed(outcome.error)
            }
        }
    }

    /** Starts a task's worker thread. The Processing screen must already be showing (via showProcessingScreen) before this is called. Finalize/publish always happens here in the completion handler — never inside a screen renderer — so the result is correct whether or not anyone is watching. */
    private fun launchWorker(type: String, op: String) {
        cancelled.set(false)
        taskRunning = true
        taskType = type
        taskOp = op
        taskPercent = 0
        taskStage = ""
        taskJustFinished = null
        val handler = Handler(mainLooper)
        // Fires the instant "START PROCESSING" is tapped — the Processing
        // screen is already showing by the time this runs (see
        // showProcessingScreen -> enterProcessingMode), and the worker
        // thread itself now starts with zero artificial delay on top of
        // that, instead of waiting out a fixed pause first.
        workerThread = Thread {
            try {
                val localInputs = selectedUris.mapIndexed { i, uri ->
                    val f = engine.newTempFile("input_$i", fileName(uri).substringAfterLast('.', "bin"))
                    engine.copyUriToFile(uri, f)
                    f
                }
                if (cancelled.get()) throw InterruptedException("CANCELLED_BY_USER")

                val progress: ProgressCallback = { p, s ->
                    if (cancelled.get()) throw InterruptedException("CANCELLED_BY_USER")
                    taskPercent = p; taskStage = s
                    handler.post { onTaskTick?.invoke(p, s) }
                }

                val outcome = dispatch(type, op, localInputs, progress)
                val finalized = finalizeOutcomes(type, op, outcome)
                taskRunning = false
                taskJustFinished = finalized
                liveMonitorCompletedUntil = System.currentTimeMillis() + 1600
                handler.post { onTaskDone?.invoke(finalized) }
            } catch (ie: InterruptedException) {
                taskRunning = false
                handler.post { onTaskCancelled?.invoke() }
            } catch (e: Exception) {
                taskRunning = false
                val finalized = listOf(FinalizedOutcome.Failed(e.message ?: "UNKNOWN_ERROR"))
                taskJustFinished = finalized
                liveMonitorCompletedUntil = System.currentTimeMillis() + 1600
                handler.post { onTaskDone?.invoke(finalized) }
            }
        }
        workerThread!!.start()
    }

    /**
     * Builds (or rebuilds) the Processing/Result screen. Called both to start
     * a brand-new task and to "reopen" one already running or finished in the
     * background (from the Home HUD card's [ View ] action) — in the latter
     * case [startNew] is false and no new worker thread is launched; this
     * screen simply wires itself into whatever is already in flight.
     */
    private fun showProcessingScreen(type: String, op: String, startNew: Boolean) {
        val r = base()
        screenState = ScreenState.PROCESSING

        fun backToFeatureConfig() {
            showFeature(type, op, if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else fileName(selectedUris.first()))
        }

        // Back never aborts a running task — it just leaves the Home HUD
        // card to keep tracking it. Once a result (success or cancellation)
        // is showing, Back goes straight to the Home dashboard — never back
        // into the workspace configuration screen the job was launched
        // from — regardless of whether this screen was reached by starting
        // a new job, opening a still-running one, or checking a job that
        // already finished via the LIVE Monitor card.
        var backAction: () -> Unit = { if (taskRunning) showHome() else backToFeatureConfig() }
        topBar("PROCESSING", showBack = true, statusLabel = "Working", statusColor = cyberBlossom, onBack = { backAction() })
        r.addView(heading(displayOp(op), 21f, white).apply { setPadding(0, dp(18), 0, dp(2)) })
        r.addView(text("ROSE NOIR / OFFLINE ENGINE", 10f, pinkSoft))

        val panelCard = glassCard(stroke = panelStroke)
        val panelBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelCard.addView(panelBody)
        r.addView(panelCard)

        panelBody.addView(mono("● SYSTEM VERIFICATION\n✓ Input analysis\n✓ Stream validation\n✓ Output vault ready", 12f, mint))

        val actionsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        r.addView(actionsHolder)

        var wave: WaveProgressView? = null
        var percentLabel: TextView? = null
        var stageLabel: TextView? = null
        var lastPercent = 0

        fun enterProcessingMode() {
            panelBody.removeAllViews()
            panelBody.addView(text("● PROCESSING", 12f, cyberBlossom).apply { letterSpacing = 0.08f })
            val w = WaveProgressView(this, panel, cyberBlossom, neonRose).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(10) }
            }
            panelBody.addView(w)
            wave = w
            activeWaveProgress = w
            val pct = text("${taskPercent}%", 20f, white).apply { setPadding(0, dp(8), 0, 0) }
            panelBody.addView(pct)
            percentLabel = pct
            val stage = text(taskStage.ifBlank { op }, 11.5f, gray).apply {
                // Rock-solid container: every one of the 30+ engines (and
                // every batch-queue tick) reports its own stage string at a
                // different length, so this is locked to one ellipsized
                // line — the panel's height can never jump as it updates.
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            panelBody.addView(stage)
            stageLabel = stage
            lastPercent = taskPercent
            wave?.percent = taskPercent

            val cancelBtn = button(bi("[ ✕ Batal ]", "[ ✕ Cancel ]")) { cancelled.set(true); workerThread?.interrupt() }
                .apply { background = shape(panel, mint, 12f, 2); setTextColor(white) }
            actionsHolder.removeAllViews()
            actionsHolder.addView(cancelBtn)
        }

        fun updateProgress(percent: Int, status: String) {
            if (wave == null) enterProcessingMode()
            val from = lastPercent
            lastPercent = percent
            ValueAnimator.ofInt(from, percent).apply {
                duration = 220
                interpolator = ease
                addUpdateListener { wave?.percent = it.animatedValue as Int }
            }.start()
            percentLabel?.text = "$percent%"
            stageLabel?.text = status
        }

        fun enterCompletedMode(finalized: List<FinalizedOutcome>) {
            panelBody.removeAllViews()
            panelBody.addView(mono("✓ OUTPUT VERIFIED", 11f, mint))
            var anySuccess = false
            finalized.forEach { outcome ->
                when (outcome) {
                    is FinalizedOutcome.Done -> {
                        anySuccess = true
                        val savedBytes = (outcome.inputBytes - outcome.outputBytes).coerceAtLeast(0)
                        val ratio = if (outcome.inputBytes > 0) String.format(Locale.US, "%.1f%%", 100.0 * (1 - outcome.outputBytes.toDouble() / outcome.inputBytes)) else "N/A"
                        panelBody.addView(text(outcome.displayName, 17f, white).apply { setPadding(0, dp(8), 0, dp(2)) })
                        val statusLine = if (outcome.publishedUri != null) bi("✓ Tersimpan di Galeri", "✓ Saved to Gallery") else bi("Output lokal", "LOCAL OUTPUT")
                        panelBody.addView(mono("SOURCE        ${formatSize(outcome.inputBytes)}\nOUTPUT        ${formatSize(outcome.outputBytes)}\nREDUCTION     $ratio\nSAVED         ${formatSize(savedBytes)}\nSTATUS        $statusLine", 12f, mint))
                        if (outcome.note.isNotBlank()) panelBody.addView(text("\n${outcome.note}", 11f, gray))
                    }
                    is FinalizedOutcome.Rejected -> {
                        val reasonText = if (outcome.reason.contains("ORIGINAL PRESERVED"))
                            bi("File asli sudah efisien (Kualitas aman)", "Original file is already efficient (Quality preserved)")
                        else outcome.reason
                        panelBody.addView(text("⚠ ${bi("OUTPUT DITOLAK", "OUTPUT REJECTED")}\n$reasonText", 13f, warn))
                    }
                    is FinalizedOutcome.Failed -> panelBody.addView(text("× PROCESS FAILED\n${outcome.error}", 13f, err))
                }
            }
            if (!anySuccess) panelBody.addView(text("\nNO OUTPUT PRODUCED", 12f, err))
            panelCard.background = shape(panel, if (anySuccess) mint else err, 14f, 2)

            screenState = ScreenState.RESULT
            // Back from a completed result goes straight Home, universally —
            // never a loop back into this op's workspace configuration.
            backAction = { showHome() }
            actionsHolder.removeAllViews()
            actionsHolder.addView(primaryButton("NEW OPERATION   ›") { showHome() })
            actionsHolder.addView(button("‹  BACK TO CONFIGURATION") { backToFeatureConfig() })
        }

        fun enterCancelledMode() {
            panelBody.removeAllViews()
            panelBody.addView(text("✕ CANCELLED BY USER", 13f, warn))
            panelBody.addView(text("STATUS   PROCESS ABORTED, NO OUTPUT WRITTEN", 11.5f, gray))
            panelCard.background = shape(panel, warn, 14f, 2)
            screenState = ScreenState.RESULT
            // Same universal rule: Back from this result goes straight Home.
            backAction = { showHome() }
            actionsHolder.removeAllViews()
            actionsHolder.addView(primaryButton("▶ NEW OPERATION") { showHome() })
            actionsHolder.addView(button("←  BACK TO CONFIGURATION") { backToFeatureConfig() })
        }

        // Wire this screen into whatever's currently in flight.
        onTaskTick = { p, s -> updateProgress(p, s) }
        onTaskDone = { finalized -> enterCompletedMode(finalized) }
        onTaskCancelled = { enterCancelledMode() }

        when {
            startNew -> { enterProcessingMode(); launchWorker(type, op) }
            taskJustFinished != null -> enterCompletedMode(taskJustFinished!!)
            taskRunning -> enterProcessingMode()
            else -> enterCancelledMode()
        }
    }

    private fun runOperation(type: String, op: String) {
        showProcessingScreen(type, op, startNew = true)
    }

    /** Routes a configured op to the corresponding MediaEngine call(s). Returns one result per output file. */
    private fun dispatch(type: String, op: String, inputs: List<File>, progress: ProgressCallback): List<EngineResult> {
        if (inputs.isEmpty()) return listOf(EngineResult.Failure("NO_INPUT"))
        // Batch queue: run the same configured operation across every picked
        // file in sequence, scaling each file's own 0-100 progress into its
        // slice of the overall bar. Merge-style ops (multiInputOps) are left
        // alone below — those already consume the whole `inputs` list at once.
        if (op in batchableOps && inputs.size > 1) {
            val n = inputs.size
            return inputs.flatMapIndexed { idx, file ->
                if (cancelled.get()) return@flatMapIndexed listOf(EngineResult.Failure("CANCELLED_BY_USER"))
                val scoped: ProgressCallback = { p, s ->
                    progress(((idx * 100 + p) / n).coerceIn(0, 100), "$s   •   FILE ${idx + 1}/$n")
                }
                singleOpDispatch(op, file, inputs, scoped)
            }
        }
        return singleOpDispatch(op, inputs.first(), inputs, progress)
    }

    /** The original single-file (or merge, for multiInputOps) routing — called once directly, or once per file when batching. */
    private fun singleOpDispatch(op: String, single: File, inputs: List<File>, progress: ProgressCallback): List<EngineResult> {
        return when (op) {
            "Video Compress & Mute" -> listOf(engine.videoCompress(single, cfgQuality, cfgMute, progress))
            "Video Trim & Reverse" -> listOf(engine.videoTrimOrReverse(single, cfgRangeStart, cfgRangeEnd, progress))
            "Video Speed" -> listOf(engine.videoSpeed(single, cfgSpeed, progress))
            "Video to Audio" -> listOf(engine.videoToAudio(single, progress))
            "Video to GIF" -> listOf(engine.videoToGif(single, cfgRangeStart, cfgRangeEnd, cfgFps, cfgGifMaxWidth, progress))
            "Extract Frame" -> listOf(engine.extractFrame(single, cfgRangeStart, progress))
            "Video Merge" -> listOf(engine.videoMerge(inputs, progress))
            "Video Rotate & Flip" -> listOf(engine.videoRotate(single, cfgRotateMode, progress))

            "GIF to Video" -> listOf(engine.gifToVideo(single, progress))
            "GIF Compress" -> listOf(engine.gifCompress(single, progress))
            "Photo to GIF" -> listOf(engine.photosToGif(inputs, cfgDelayCentis, cfgGifMaxWidth, progress))

            "Photo Compress" -> listOf(engine.photoCompress(single, cfgTargetKb, progress))
            "Batch Photo Compress" -> engine.batchPhotoCompress(inputs, cfgTargetKb, progress).map { it.second }
            "Image Converter" -> listOf(engine.imageConvert(single, cfgImageFormat, progress))
            "Remove EXIF" -> listOf(engine.removeExif(single, progress))

            "Audio Trim & Reverse" -> listOf(engine.audioTrimOrReverse(single, cfgRangeStart, cfgRangeEnd, progress))
            "Audio Merge" -> listOf(engine.audioMerge(inputs, progress))
            "Audio Compress" -> listOf(engine.audioCompress(single, progress))
            "Audio Volume Booster" -> listOf(engine.audioBoostVolume(single, cfgGainDb, progress))
            "Audio Silence Trimmer" -> listOf(engine.audioTrimSilence(single, progress))

            "Photo to PDF" -> listOf(engine.photosToPdf(inputs, progress))
            "PDF to Photo" -> {
                val files = engine.pdfToPhotos(single, progress)
                files.map { EngineResult.Success(it, single.length() / files.size.coerceAtLeast(1), it.length()) }
            }
            "Merge PDF" -> listOf(engine.mergePdf(inputs, progress))
            "Split & Reverse PDF" -> listOf(engine.pdfSplitOrReverse(single, cfgRangeStart.toInt(), cfgRangeEnd.toInt(), progress))
            "Compress PDF" -> listOf(engine.pdfCompress(single, progress))
            "PDF to Grayscale" -> listOf(engine.pdfToGrayscale(single, progress))
            "Watermark PDF" -> listOf(engine.pdfWatermark(single, cfgWatermarkText, progress))
            "Lock PDF" -> listOf(engine.pdfLock(single, cfgPassword, progress))
            "Unlock PDF" -> listOf(engine.pdfUnlock(single, cfgPassword, progress))

            "Create ZIP" -> listOf(engine.createZip(inputs, progress))
            "Extract ZIP" -> listOf(engine.extractZip(single, progress))
            "ZIP Recompress" -> listOf(engine.zipRecompress(single, progress))

            else -> listOf(EngineResult.Failure("UNKNOWN_OPERATION"))
        }
    }

    /**
     * Maps a completed operation to its MediaStore export subfolder per the
     * Downloads/MediaCompressor/ taxonomy: Photos, GIF, Videos, Audio,
     * Documents, Archives, and — specifically for Extract ZIP — a nested
     * Extracted/[Archive_Name]/Extract/ folder. Best-effort: when several
     * archives are batch-extracted in one run, the archive name used is the
     * first file picked for the batch.
     */
    /**
     * Maps a completed operation to its MediaStore export subfolder per the
     * Downloads/MediaCompressor/ taxonomy: Photos, GIF, Videos, Audio,
     * Documents, Archives, and — specifically for Extract ZIP — a nested
     * Extracted/[Archive_Name]/Extract/ folder.
     *
     * Bugfix: this used to switch on the launch [type] (the category
     * workspace the op was opened from), which put every cross-category
     * transformation in the WRONG folder — e.g. "Video to Audio" was filed
     * under Videos/ instead of Audio/, because it's reached from the VIDEO
     * category workspace even though its actual output is an .m4a. The
     * mapping now switches on [op] itself, deterministically, so the
     * destination folder always matches the RESULTING output file type,
     * never the category the op happened to be launched from.
     *
     * Best-effort for Extract ZIP: when several archives are batch-extracted
     * in one run, the archive name used is the first file picked for the
     * batch.
     */
    private fun subfolderFor(op: String): String = when (op) {
        // Result is AUDIO (.m4a) -> Audio/
        "Video to Audio", "Audio Trim & Reverse", "Audio Compress", "Audio Merge",
        "Audio Volume Booster", "Audio Silence Trimmer" -> "Audio"

        // Result is GIF (.gif) -> GIF/
        "Video to GIF", "Photo to GIF", "GIF Compress" -> "GIF"

        // Result is PHOTO/IMAGE (.jpg/.png/.webp) -> Photos/
        "Extract Frame", "PDF to Photo", "Photo Compress", "Batch Photo Compress",
        "Image Converter", "Remove EXIF" -> "Photos"

        // Result is VIDEO (.mp4) -> Videos/
        "Video Compress & Mute", "Video Trim & Reverse", "Video Speed", "Video Merge",
        "Video Rotate & Flip", "GIF to Video" -> "Videos"

        // Result is PDF (.pdf) -> Documents/
        "Photo to PDF", "Merge PDF", "Split & Reverse PDF", "Compress PDF",
        "PDF to Grayscale", "Watermark PDF", "Lock PDF", "Unlock PDF" -> "Documents"

        // Result is a ZIP archive (.zip) -> Archives/
        "Create ZIP", "ZIP Recompress" -> "Archives"

        // Result is an extracted folder -> Extracted/[Archive_Name]/Extract/
        "Extract ZIP" -> {
            val archiveBase = selectedUris.firstOrNull()?.let { fileName(it) }?.substringBeforeLast('.', "archive") ?: "archive"
            "Extracted/$archiveBase/Extract"
        }

        else -> "MediaCompressor"
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "vpdf" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    // =========================================================================
    // Media Vault — everything published to Downloads/MediaCompressor/
    // =========================================================================

    private fun showVault() {
        val r = base()
        screenState = ScreenState.OTHER
        topBar(bi("MEDIA VAULT", "MEDIA VAULT"), showBack = true, onBack = { vaultOrigin() })
        r.addView(heading(bi("MEDIA VAULT", "MEDIA VAULT"), 22f, white).apply { setPadding(0, dp(18), 0, dp(2)) })
        r.addView(text(bi("File tersimpan di Downloads/MediaCompressor", "Files saved to Downloads/MediaCompressor"), 11f, mint))
        r.addView(line())

        val selected = mutableSetOf<Uri>()
        val itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(actionsRow)
        r.addView(itemsContainer)

        lateinit var renderAll: () -> Unit
        lateinit var renderActions: () -> Unit

        renderActions = {
            actionsRow.removeAllViews()
            val items = MediaStoreExporter.list(this)
            val allSelected = items.isNotEmpty() && selected.size == items.size
            actionsRow.addView(button(bi("[ Pilih Semua ]", "[ SELECT ALL ]")) {
                if (allSelected) selected.clear() else { selected.clear(); selected.addAll(items.map { it.uri }) }
                renderAll()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) } })
            actionsRow.addView(button(bi("[ 🗑️ Hapus (${selected.size} File) ]", "[ 🗑️ Delete All (${selected.size}) ]")) {
                if (selected.isEmpty()) return@button
                selected.toList().forEach { MediaStoreExporter.delete(this, it) }
                selected.clear()
                renderAll()
            }.apply {
                background = shape(panel, mint, 12f, 2); setTextColor(white)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        renderAll = {
            itemsContainer.removeAllViews()
            renderActions()
            val items = MediaStoreExporter.list(this)
            if (items.isEmpty()) {
                itemsContainer.addView(text(bi("Vault masih kosong.", "Vault is empty."), 13f, gray).apply { setPadding(0, dp(14), 0, 0) })
            }
            items.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(if (item.uri in selected) activeCardBg else panel, if (item.uri in selected) mint else panelStroke, 14f, if (item.uri in selected) 2 else 1)
                    setPadding(dp(14), dp(12), dp(12), dp(12))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                }
                applyTapFeedback(row) {
                    if (item.uri in selected) selected.remove(item.uri) else selected.add(item.uri)
                    renderAll()
                }
                val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                copy.addView(text(item.displayName, 13.5f, white).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                copy.addView(text("${formatSize(item.sizeBytes)}  •  ${item.relativePath.removePrefix("Download/MediaCompressor/").trimEnd('/')}", 10.5f, metaGray).apply { setPadding(0, dp(2), 0, 0) })
                row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = "🗑️"; textSize = 15f; gravity = Gravity.CENTER
                    background = shape(panel, panelStroke, 10f)
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(10) }
                    applyTapFeedback(this, 0.9f) { MediaStoreExporter.delete(this@MainActivity, item.uri); selected.remove(item.uri); renderAll() }
                })
                itemsContainer.addView(row)
            }
        }
        renderAll()
        r.addView(button(bi("‹  KEMBALI", "‹  BACK")) { vaultOrigin() })
    }

    // =========================================================================
    // =========================================================================
    // Global Quant Algorithmic Market Terminal — [ 📈 QUANT // MARKET ]
    // =========================================================================
    // MARKET-ONLY SECTION. Everything below only renders views using this
    // Activity's own existing styling helpers (topBar/heading/text/mono/dp/bi/
    // colors/card shapes) — exactly like every other screen in this file.
    // All market DATA — providers, routing/fallback, on-device indicators,
    // caching, instrument discovery, FX rates — lives in the isolated
    // `com.vr3th.mediacompressor.market` package and is never duplicated here.
    // No media-processing code is read or modified by this section. See
    // MARKET_MODULE.md at the project root for the full file map.

    /** Reads live connectivity state. Fails OPEN (assumes online) if ACCESS_NETWORK_STATE isn't declared, so a missing manifest permission never crashes or silently locks the feature. */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    /** Tapping [ 📈 QUANT // MARKET ]: goes straight in if online or if any cached quote already exists for the default favorites; otherwise shows the Air-Gap dialog first. */
    private fun openQuantMarket() {
        val hasAnyCache = MarketCategory.values().any { cat ->
            MarketInstrumentIndex.favoritesFor(cat).any { MarketCache.load(this, it, quantTimeframe) != null }
        }
        if (!isNetworkAvailable() && !hasAnyCache) showAirGapDialog() else showQuantMarket()
    }

    /** Frosted-glass Cyber dialog for Smart Offline Air-Gap Mode. */
    private fun showAirGapDialog() {
        closeAirGapDialog()
        val overlay = FrameLayout(this).apply { setBackgroundColor(dimBackdrop); alpha = 0f }
        applyTapFeedback(overlay, 1f) { closeAirGapDialog() }
        val card = glassCard(stroke = mint).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        card.addView(text(bi("[ ⚠ MODE AIR-GAP // TIDAK ADA KONEKSI ]", "[ ⚠ AIR-GAP MODE // NO NETWORK UPLINK ]"), 13f, white).apply {
            gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, 0, 0, dp(4))
        })
        card.addView(text(bi("Terminal Quant Market memerlukan koneksi untuk data live.", "Quant Market Terminal needs a connection for live data."), 11f, gray).apply {
            gravity = Gravity.CENTER
        })
        card.addView(primaryButton("[ ⟳ Coba Lagi / Retry ]") { closeAirGapDialog(); openQuantMarket() })
        card.addView(button(bi("‹  Tutup", "‹  Close")) { closeAirGapDialog() })
        val holder = FrameLayout(this)
        holder.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.addView(card, FrameLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        pageFrame.addView(holder)
        airGapDialogRoot = holder
        overlay.animate().alpha(1f).setDuration(200).start()
        card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(springEase).start()
    }

    private fun closeAirGapDialog() {
        val holder = airGapDialogRoot ?: return
        airGapDialogRoot = null
        pageFrame.removeView(holder)
    }

    private fun quantCategoryLabel(c: MarketCategory): String = when (c) {
        MarketCategory.CRYPTO -> bi("🪙 Kripto Global", "🪙 Global Crypto")
        MarketCategory.US_STOCKS -> bi("📈 Saham AS", "📈 US Tech Stocks")
        MarketCategory.IDX -> bi("🇮🇩 Saham IDX", "🇮🇩 Indonesian IDX")
    }

    // Literal bilingual labels, matching the spec's fixed "[ ID / EN ]" bracket format exactly — always shown together, not locale-toggled.
    private fun quantSignalLabel(s: QuantSignal): String = when (s) {
        QuantSignal.STRONG_BUY -> "AKUMULASI KUAT / STRONG BUY"
        QuantSignal.BUY -> "AKUMULASI / BUY"
        QuantSignal.NEUTRAL -> "NETRAL / NEUTRAL"
        QuantSignal.SELL -> "DISTRIBUSI / SELL"
        QuantSignal.STRONG_SELL -> "DISTRIBUSI KUAT / STRONG SELL"
    }

    private fun quantSignalColor(s: QuantSignal): Int = when (s) {
        QuantSignal.STRONG_BUY, QuantSignal.BUY -> cyberBlossom
        QuantSignal.NEUTRAL -> gray
        QuantSignal.SELL, QuantSignal.STRONG_SELL -> pinkElectric
    }

    private fun aiVerdictLabel(v: AiVerdict): String = when (v) {
        AiVerdict.STRONG_BUY -> "AKUMULASI KUAT / STRONG BUY"
        AiVerdict.BUY -> "AKUMULASI / BUY"
        AiVerdict.NEUTRAL_WAIT -> "NETRAL — TUNGGU / NEUTRAL — WAIT"
        AiVerdict.SELL -> "DISTRIBUSI / SELL"
        AiVerdict.STRONG_SELL -> "DISTRIBUSI KUAT / STRONG SELL"
        AiVerdict.NO_TRADE -> "TIDAK ADA POSISI / NO TRADE"
    }

    private fun aiVerdictColor(v: AiVerdict): Int = when (v) {
        AiVerdict.STRONG_BUY, AiVerdict.BUY -> cyberBlossom
        AiVerdict.NEUTRAL_WAIT, AiVerdict.NO_TRADE -> gray
        AiVerdict.SELL, AiVerdict.STRONG_SELL -> pinkElectric
    }

    private fun dataStateLabel(s: DataState): String = when (s) {
        DataState.LIVE -> bi("● LIVE", "● LIVE")
        DataState.DELAYED -> bi("◷ TERTUNDA", "◷ DELAYED")
        DataState.CACHED -> bi("◌ TERSIMPAN", "◌ CACHED")
        DataState.STALE -> bi("◌ KEDALUWARSA", "◌ STALE")
        DataState.OFFLINE -> bi("× OFFLINE", "× OFFLINE")
        DataState.UNAVAILABLE -> bi("DATA TIDAK TERSEDIA", "DATA UNAVAILABLE")
        DataState.ERROR -> bi("⚠ GALAT PROVIDER", "⚠ PROVIDER ERROR")
    }

    private fun dataStateColor(s: DataState): Int = when (s) {
        DataState.LIVE, DataState.DELAYED -> cyberBlossom
        DataState.CACHED -> mint
        DataState.STALE -> gray
        DataState.OFFLINE, DataState.UNAVAILABLE, DataState.ERROR -> pinkElectric
    }

    private fun showQuantMarket() {
        quantMarketAlive = true
        quantPinned = MarketCache.loadWatchlist(this)
        val r = base()
        screenState = ScreenState.OTHER

        fun leaveMarket() { quantMarketAlive = false; showHome() }

        topBar("QUANT // MARKET", showBack = true, onBack = { leaveMarket() })
        r.addView(heading("QUANT // MARKET", 21f, white).apply { setPadding(0, dp(16), 0, dp(2)) })
        r.addView(text(bi("Konvergensi indikator on-device  •  Data live multi-provider", "On-device indicator convergence  •  Live multi-provider data"), 11f, mint))

        // ---- Section E: global search (instant local filter, debounced remote crypto lookup) ----
        val searchInput = EditText(this).apply {
            hint = bi("🔎 Cari pasar, simbol, perusahaan, bursa...", "🔎 Search markets, symbols, companies, exchanges...")
            setHintTextColor(metaGray); setTextColor(white); textSize = 12.5f
            background = shape(panel, panelStroke, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine(true)
        }
        r.addView(searchInput.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) } })
        val searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        r.addView(searchResults)
        val searchHandler = Handler(mainLooper)
        var pendingSearch: Runnable? = null

        fun renderSearchResults(query: String) {
            searchResults.removeAllViews()
            if (query.isBlank()) return
            val local = MarketInstrumentIndex.searchLocal(query)
            local.take(10).forEach { inst -> searchResults.addView(searchResultRow(inst) { openInstrumentDetail(it) }) }
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                renderSearchResults(q) // instant local filter — no network call per keystroke
                pendingSearch?.let { searchHandler.removeCallbacks(it) }
                if (q.length >= 2 && isNetworkAvailable()) {
                    val r2 = Runnable {
                        com.vr3th.mediacompressor.market.MarketExecutors.io.execute {
                            val remote = MarketInstrumentIndex.searchCoinGeckoRemote(q)
                            runOnUiThread {
                                if (quantMarketAlive && searchInput.text.toString() == q && remote.isNotEmpty()) {
                                    remote.forEach { inst ->
                                        if (searchResults.childCount < 14) searchResults.addView(searchResultRow(inst) { openInstrumentDetail(it) })
                                    }
                                }
                            }
                        }
                    }
                    pendingSearch = r2
                    searchHandler.postDelayed(r2, 450) // debounced remote search, per Section E
                }
            }
        })

        val expanded = mutableSetOf<String>()
        val quotesBySymbol = HashMap<String, MarketQuote>()
        val loadingSymbols = mutableSetOf<String>()
        lateinit var refreshList: () -> Unit

        // Header actions: 1-tap currency toggle, backed by live FX (Frankfurter) with cache fallback.
        val currencyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        currencyRow.addView(button("[ 💱 IDR ⇄ USD ]") {
            val current = quantCurrencyOverride ?: MarketRepository.defaultCurrencyForLocale(isIndonesian)
            quantCurrencyOverride = if (current == DisplayCurrency.IDR) DisplayCurrency.USD else DisplayCurrency.IDR
            refreshList()
        })
        r.addView(currencyRow)

        // Category tabs: Global Crypto / US Tech Stocks / Indonesian IDX.
        val categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        r.addView(categoryRow)

        // Multi-timeframe selector.
        val timeframeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        r.addView(timeframeRow)
        r.addView(line())

        val offlineBanner = text("", 10.5f, pinkElectric).apply { letterSpacing = 0.04f }
        r.addView(offlineBanner)

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        r.addView(listContainer)

        fun renderCategoryTabs() {
            categoryRow.removeAllViews()
            val cats = MarketCategory.values()
            cats.forEach { c ->
                val selected = c == quantCategory
                categoryRow.addView(TextView(this).apply {
                    text = quantCategoryLabel(c)
                    textSize = 10f
                    setTextColor(if (selected) bg else white)
                    background = shape(if (selected) cyberBlossom else panel, if (selected) null else panelStroke, 12f)
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (c != cats.last()) marginEnd = dp(6) }
                    applyTapFeedback(this, 0.95f) { quantCategory = c; renderCategoryTabs(); refreshList() }
                })
            }
        }

        fun renderTimeframeChips() {
            timeframeRow.removeAllViews()
            val tfs = listOf("1M", "5M", "15M", "1H", "4H", "1D")
            tfs.forEach { tf ->
                val selected = tf == quantTimeframe
                timeframeRow.addView(TextView(this).apply {
                    text = tf
                    textSize = 10.5f
                    setTextColor(if (selected) bg else gray)
                    background = shape(if (selected) mint else panel, if (selected) null else panelStroke, 10f)
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (tf != tfs.last()) marginEnd = dp(5) }
                    applyTapFeedback(this, 0.95f) { quantTimeframe = tf; renderTimeframeChips(); refreshList() }
                })
            }
        }

        fun metricRow(label: String, value: String): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
                addView(text(label, 10.5f, metaGray), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(mono(value, 10.5f, white))
            }

        fun priceText(usd: Double, currency: DisplayCurrency): String =
            MarketRepository.formatCurrencyOrNull(usd, currency, quantFxRates)
                ?: bi("KURS TIDAK TERSEDIA", "FX RATE UNAVAILABLE")

        fun renderList() {
            listContainer.removeAllViews()
            val currency = quantCurrencyOverride ?: MarketRepository.defaultCurrencyForLocale(isIndonesian)
            val list = MarketInstrumentIndex.favoritesFor(quantCategory).sortedByDescending { it.symbol in quantPinned }
            list.forEach { asset ->
                val quote = quotesBySymbol[asset.symbol] ?: MarketCache.load(this, asset, quantTimeframe)
                val isLoading = quote == null && asset.symbol in loadingSymbols
                val pinned = asset.symbol in quantPinned
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = shape(if (pinned) activeCardBg else panel, if (pinned) mint else panelStroke, 14f, if (pinned) 2 else 1)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                }

                val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                topRow.addView(TextView(this).apply {
                    text = if (pinned) "★" else "☆"
                    textSize = 17f
                    setTextColor(if (pinned) cyberBlossom else metaGray)
                    setPadding(0, 0, dp(10), 0)
                    applyTapFeedback(this, 0.85f) {
                        if (pinned) quantPinned.remove(asset.symbol) else quantPinned.add(asset.symbol)
                        MarketCache.saveWatchlist(this@MainActivity, quantPinned)
                        renderList()
                    }
                })
                val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                nameCol.addView(TextView(this).apply {
                    text = asset.symbol
                    textSize = 14.5f
                    setTextColor(white)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                nameCol.addView(text(asset.name + "  •  " + asset.exchange, 10f, metaGray))
                topRow.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val priceCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                if (quote != null) {
                    priceCol.addView(mono(priceText(quote.lastPrice, currency), 13f, white).apply { gravity = Gravity.END })
                    priceCol.addView(text(String.format(Locale.US, "%+.2f%%", quote.changePercent), 11f,
                        if (quote.changePercent >= 0) cyberBlossom else pinkElectric).apply { gravity = Gravity.END })
                } else {
                    priceCol.addView(text(if (isLoading) bi("MEMUAT…", "LOADING…") else dataStateLabel(DataState.UNAVAILABLE), 11f, metaGray).apply { gravity = Gravity.END })
                }
                topRow.addView(priceCol)
                card.addView(topRow)

                val stateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
                val effectiveState = quote?.state ?: if (isLoading) DataState.OFFLINE else DataState.UNAVAILABLE
                stateRow.addView(text(if (quote != null) dataStateLabel(quote.state) else if (isLoading) bi("MEMUAT…", "LOADING…") else dataStateLabel(DataState.UNAVAILABLE),
                    9.5f, dataStateColor(effectiveState)))
                if (quote != null) {
                    stateRow.addView(text("  •  " + quote.sourceLabel, 9.5f, metaGray))
                }
                card.addView(stateRow)

                if (quote != null && quote.candles.size >= 2) {
                    card.addView(MarketSparklineView(this, if (quote.changePercent >= 0) cyberBlossom else pinkElectric).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(8) }
                        values = quote.candles.map { it.close }
                    })
                }

                val quoteAnalysis = quote?.analysis
                if (quoteAnalysis != null) {
                    val signalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
                    signalRow.addView(text(aiVerdictLabel(quoteAnalysis.verdict), 10f, aiVerdictColor(quoteAnalysis.verdict)).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.02f
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    signalRow.addView(text(String.format(Locale.US, bi("%.0f%% Konvergensi", "%.0f%% Convergence"), quoteAnalysis.confluence.convergencePercent), 9f, mint))
                    card.addView(signalRow)
                } else {
                    val v = quote?.verdict
                    if (v != null) {
                        val signalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
                        signalRow.addView(text(quantSignalLabel(v.signal), 10f, quantSignalColor(v.signal)).apply {
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.02f
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        signalRow.addView(text(
                            String.format(Locale.US, bi("%.1f%% Konvergensi [%d/%d Indikator Bullish]", "%.1f%% Convergence [%d/%d Bullish]"),
                                v.confidencePercent, v.bullishCount, v.totalIndicators), 9f, mint))
                        card.addView(signalRow)
                    }
                }

                val isExpanded = asset.symbol in expanded
                applyTapFeedback(card, 0.98f) {
                    if (isExpanded) expanded.remove(asset.symbol) else expanded.add(asset.symbol)
                    renderList()
                }

                if (isExpanded && quote != null) {
                    card.addView(line())
                    if (quote.candles.size >= 5) {
                        card.addView(MarketCandlestickView(this).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { topMargin = dp(6) }
                            candles = quote.candles
                        })
                    }
                    val ind = quote.indicators
                    if (ind != null) {
                        if (!ind.sufficientHistory) {
                            card.addView(text(bi("RIWAYAT HISTORIS TIDAK CUKUP untuk semua pembacaan jangka panjang", "INSUFFICIENT HISTORICAL DATA for the longer-period readings"), 9.5f, pinkElectric).apply { setPadding(0, dp(4), 0, dp(2)) })
                        }
                        card.addView(metricRow("RSI (14)", String.format(Locale.US, "%.1f", ind.rsi14)))
                        card.addView(metricRow("EMA 20 / 50 / 200", String.format(Locale.US, "%.2f / %.2f / %.2f", ind.ema20, ind.ema50, ind.ema200)))
                        card.addView(metricRow("MACD", String.format(Locale.US, "%.4f  (Δ %.4f)", ind.macd, ind.macdHist)))
                        card.addView(metricRow("Bollinger  U / M / L", String.format(Locale.US, "%.2f / %.2f / %.2f", ind.bbUpper, ind.bbMid, ind.bbLower)))
                        card.addView(metricRow(bi("Volume (Δ vs sebelumnya)", "Volume (Δ vs prior)"),
                            ind.volumeDeltaPercent?.let { String.format(Locale.US, "%+.1f%%", it) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                        card.addView(metricRow(bi("Pivot / S1 / R1", "Pivot / S1 / R1"), String.format(Locale.US, "%.2f / %.2f / %.2f", ind.pivot, ind.support1, ind.resistance1)))
                    }
                    card.addView(metricRow("Tertinggi / High", priceText(quote.high, currency)))
                    card.addView(metricRow("Terendah / Low", priceText(quote.low, currency)))

                    val analysis = quote.analysis
                    if (analysis != null) {
                        card.addView(line())
                        card.addView(text(bi("KECERDASAN PASAR / MARKET INTELLIGENCE", "MARKET INTELLIGENCE"), 10f, mint).apply {
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.03f; setPadding(0, dp(4), 0, dp(2))
                        })
                        card.addView(metricRow(bi("Rezim Pasar", "Market Regime"), analysis.regime.name.replace('_', ' ')))
                        card.addView(metricRow(bi("Struktur", "Structure"),
                            if (analysis.structure.sufficientData) "${analysis.structure.trend} / ${analysis.structure.lastEvent}" else bi("TIDAK CUKUP", "INSUFFICIENT")))
                        if (analysis.structure.breakout != BreakoutState.NONE) {
                            card.addView(metricRow(bi("Breakout", "Breakout"), analysis.structure.breakout.name.replace('_', ' ')))
                        }
                        val nearSup = analysis.supportResistance.filter { it.type == SRLevelType.SUPPORT }.minByOrNull { kotlin.math.abs(it.distancePercent) }
                        val nearRes = analysis.supportResistance.filter { it.type == SRLevelType.RESISTANCE }.minByOrNull { kotlin.math.abs(it.distancePercent) }
                        card.addView(metricRow(bi("Support Terdekat", "Nearest Support"), nearSup?.let { String.format(Locale.US, "%.4g (%.1f%%)", it.price, it.distancePercent) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                        card.addView(metricRow(bi("Resistance Terdekat", "Nearest Resistance"), nearRes?.let { String.format(Locale.US, "%.4g (+%.1f%%)", it.price, it.distancePercent) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                        card.addView(metricRow(bi("Volatilitas (ATR)", "Volatility (ATR)"),
                            analysis.volatility.atrPercentOfPrice?.let { String.format(Locale.US, "%.1f%% — %s", it, analysis.volatility.regime.name) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                        card.addView(metricRow("VWAP", analysis.volatility.vwap?.let { priceText(it, currency) + if (analysis.volatility.priceAboveVwap == true) bi(" (di atas)", " (above)") else bi(" (di bawah)", " (below)") } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                        card.addView(metricRow(bi("Volume Relatif (RVOL)", "Relative Volume (RVOL)"), analysis.volatility.rvol?.let { String.format(Locale.US, "%.2fx", it) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                        if (analysis.divergence.sufficientData && (analysis.divergence.rsiBearishDivergence || analysis.divergence.rsiBullishDivergence || analysis.divergence.macdBearishDivergence || analysis.divergence.macdBullishDivergence)) {
                            card.addView(metricRow(bi("Divergensi", "Divergence"),
                                if (analysis.divergence.rsiBearishDivergence || analysis.divergence.macdBearishDivergence) bi("BEARISH", "BEARISH") else bi("BULLISH", "BULLISH")))
                        }
                        card.addView(metricRow(bi("Kualitas Sinyal", "Signal Quality"), analysis.signalQuality.name.replace('_', ' ')))
                        val risk = analysis.risk
                        if (risk != null) {
                            card.addView(metricRow(bi("Entry / Stop / Target", "Entry / Stop / Target"), String.format(Locale.US, "%.4g / %.4g / %.4g", risk.entry, risk.stopLoss, risk.takeProfit)))
                            card.addView(metricRow(bi("Risiko / Imbalan", "Risk / Reward"), String.format(Locale.US, "1 : %.2f", risk.riskRewardRatio)))
                        } else {
                            card.addView(metricRow(bi("Risiko / Imbalan", "Risk / Reward"), bi("TIDAK ADA ARAH VALID", "NO VALID DIRECTION")))
                        }
                        card.addView(text(analysis.narrative, 9.5f, gray).apply { setPadding(0, dp(8), 0, dp(2)); setLineSpacing(dp(2).toFloat(), 1f) })
                        val scenarios = analysis.scenarios
                        if (scenarios != null) {
                            card.addView(text(bi("Invalidasi: ", "Invalidation: ") + scenarios.invalidation, 9f, pinkElectric).apply { setPadding(0, dp(4), 0, 0) })
                            if (scenarios.waitCondition != null) {
                                card.addView(text(bi("Menunggu: ", "Waiting for: ") + scenarios.waitCondition, 9f, metaGray).apply { setPadding(0, dp(2), 0, 0) })
                            }
                        }
                    } else if (quote.verdict != null) {
                        card.addView(metricRow(bi("Risk / Reward", "Risk / Reward"), String.format(Locale.US, "1 : %.2f", quote.verdict.riskRewardRatio)))
                    }
                    card.addView(line())
                    card.addView(metricRow(bi("Order Book (Level-2)", "Order Book (Level-2)"), bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    card.addView(metricRow(bi("Derivatif (Funding/OI)", "Derivatives (Funding/OI)"), bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    card.addView(metricRow(bi("Fundamental", "Fundamentals"), bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    card.addView(metricRow(bi("Berita / Sentimen", "News / Sentiment"), bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                }

                listContainer.addView(card)
            }
        }

        refreshList = {
            val online = isNetworkAvailable()
            offlineBanner.text = if (!online) bi("[ DATA TERSIMPAN • OFFLINE ]", "[ CACHED • OFFLINE ]") else ""
            renderList()
            if (online) {
                if (quantFxRates == null) {
                    MarketRepository.fetchFxRatesAsync(this) { rates -> if (quantMarketAlive) { quantFxRates = rates; renderList() } }
                }
                MarketInstrumentIndex.favoritesFor(quantCategory).forEach { asset ->
                    if (asset.symbol !in loadingSymbols) {
                        loadingSymbols.add(asset.symbol)
                        MarketRepository.fetchQuoteAsync(this, asset, quantTimeframe, isIndonesian) { quote ->
                            loadingSymbols.remove(asset.symbol)
                            if (quantMarketAlive) { quotesBySymbol[asset.symbol] = quote; renderList() }
                        }
                    }
                }
            }
        }

        renderCategoryTabs()
        renderTimeframeChips()
        refreshList()
        r.addView(button(bi("‹  KEMBALI", "‹  BACK")) { leaveMarket() })
    }

    /** One row in the global search results list (Section E) — tap opens the full detail sheet. */
    private fun searchResultRow(inst: MarketInstrument, onOpen: (MarketInstrument) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(panel, panelStroke, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(inst.symbol, 12.5f, white).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
            col.addView(text("${inst.name}  •  ${inst.exchange}  •  ${inst.country}", 9.5f, metaGray))
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(inst.assetClass.name, 9f, mint))
            applyTapFeedback(this, 0.97f) { onOpen(inst) }
        }

    /** Full-screen single-instrument detail sheet reached from search — real quote + candlestick + indicators, same data path as the favorites list. */
    private fun openInstrumentDetail(inst: MarketInstrument) {
        var detailAlive = true
        var deepDiveStarted = false
        var loggedThisSession = false
        val isCrypto = inst.assetClass == AssetClass.CRYPTO
        val binanceSymbol = if (isCrypto) com.vr3th.mediacompressor.market.BinanceSymbolMap.symbolFor(inst) else null
        val r = base()
        screenState = ScreenState.OTHER
        fun leaveDetail() {
            detailAlive = false
            if (isCrypto) { MarketRealtimeManager.stop(); MarketLiquidationFeed.stop() }
            showQuantMarket()
        }
        topBar(inst.symbol, showBack = true, onBack = { leaveDetail() })
        r.addView(heading(inst.symbol, 21f, white).apply { setPadding(0, dp(16), 0, dp(2)) })
        r.addView(text("${inst.name}  •  ${inst.exchange}  •  ${inst.country}", 11f, mint))

        val realtimeBadge = text("", 10f, mint)
        r.addView(realtimeBadge)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        r.addView(body)
        val statusText = text(bi("MEMUAT…", "LOADING…"), 11f, metaGray)
        body.addView(statusText)

        val backtestContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun renderRealtimeBadge(state: RealtimeQuoteState) {
            if (!detailAlive) return
            realtimeBadge.text = when (state.connection) {
                StreamConnectionState.CONNECTED -> if (state.isStale) bi("◷ STREAM DIAM (TERTUNDA)", "◷ STREAM QUIET (STALE)") else
                    bi("● LIVE — Bid ${state.bestBid ?: "?"} / Ask ${state.bestAsk ?: "?"}", "● LIVE — Bid ${state.bestBid ?: "?"} / Ask ${state.bestAsk ?: "?"}")
                StreamConnectionState.CONNECTING -> bi("◷ MENGHUBUNGKAN…", "◷ CONNECTING…")
                StreamConnectionState.RECONNECTING -> bi("◷ MENYAMBUNG ULANG…", "◷ RECONNECTING…")
                StreamConnectionState.DISCONNECTED -> ""
                StreamConnectionState.UNSUPPORTED -> ""
            }
            realtimeBadge.setTextColor(if (state.connection == StreamConnectionState.CONNECTED && !state.isStale) cyberBlossom else gray)
        }

        fun renderBacktest(result: BacktestResult) {
            backtestContainer.removeAllViews()
            backtestContainer.addView(line())
            backtestContainer.addView(text(bi("EVALUASI HISTORIS (WALK-FORWARD)", "HISTORICAL EVALUATION (WALK-FORWARD)"), 10f, mint).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(6), 0, dp(2))
            })
            if (result.sampleTooSmall) {
                backtestContainer.addView(text(bi("SAMPEL TERLALU KECIL (n=${result.totalSignals}) — jangan tarik kesimpulan kuat.", "SAMPLE TOO SMALL (n=${result.totalSignals}) — do not draw strong conclusions."), 9.5f, pinkElectric))
            }
            val o = result.overall
            backtestContainer.addView(text(bi("Keseluruhan: n=${o.sampleSize}, menang=${o.wins}, kalah=${o.losses}, tanpa-posisi=${o.noTrades}", "Overall: n=${o.sampleSize}, wins=${o.wins}, losses=${o.losses}, no-trade=${o.noTrades}"), 9.5f, white))
            o.expectancy?.let { backtestContainer.addView(text(bi("Ekspektansi per sinyal: ${"%+.2f".format(it)}%%", "Expectancy per signal: ${"%+.2f".format(it)}%"), 9.5f, if (it >= 0) cyberBlossom else pinkElectric)) }
            backtestContainer.addView(text(result.methodNote, 8.5f, metaGray).apply { setPadding(0, dp(4), 0, 0) })
        }

        fun render(quote: MarketQuote) {
            body.removeAllViews()
            statusText.text = dataStateLabel(quote.state) + "  •  " + quote.sourceLabel
            statusText.setTextColor(dataStateColor(quote.state))
            body.addView(statusText)
            if (quote.state == DataState.UNAVAILABLE || quote.state == DataState.ERROR) {
                body.addView(text(bi("DATA TIDAK TERSEDIA untuk instrumen ini dari provider yang terhubung.", "DATA UNAVAILABLE for this instrument from the connected providers."), 12f, pinkElectric).apply { setPadding(0, dp(8), 0, 0) })
                return
            }
            val currency = quantCurrencyOverride ?: MarketRepository.defaultCurrencyForLocale(isIndonesian)
            body.addView(mono(MarketRepository.formatCurrencyOrNull(quote.lastPrice, currency, quantFxRates) ?: bi("KURS TIDAK TERSEDIA", "FX RATE UNAVAILABLE"), 20f, white).apply { setPadding(0, dp(8), 0, 0) })
            body.addView(text(String.format(Locale.US, "%+.2f%%", quote.changePercent), 12f, if (quote.changePercent >= 0) cyberBlossom else pinkElectric))
            if (quote.candles.size >= 5) {
                body.addView(MarketCandlestickView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(10) }
                    candles = quote.candles
                })
            } else {
                body.addView(text(bi("RIWAYAT HISTORIS TIDAK CUKUP", "INSUFFICIENT HISTORICAL DATA"), 11f, pinkElectric).apply { setPadding(0, dp(8), 0, 0) })
            }
            val ind = quote.indicators
            if (ind != null) {
                body.addView(line())
                fun row(label: String, value: String) = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
                    addView(text(label, 10.5f, metaGray), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(mono(value, 10.5f, white))
                }
                body.addView(row("RSI (14)", String.format(Locale.US, "%.1f", ind.rsi14)))
                body.addView(row("EMA 20/50/200", String.format(Locale.US, "%.2f / %.2f / %.2f", ind.ema20, ind.ema50, ind.ema200)))
                body.addView(row("MACD", String.format(Locale.US, "%.4f (Δ %.4f)", ind.macd, ind.macdHist)))
                body.addView(row("Bollinger U/M/L", String.format(Locale.US, "%.2f / %.2f / %.2f", ind.bbUpper, ind.bbMid, ind.bbLower)))
                body.addView(row(bi("Pivot / S1 / R1", "Pivot / S1 / R1"), String.format(Locale.US, "%.2f / %.2f / %.2f", ind.pivot, ind.support1, ind.resistance1)))
                if (quote.verdict != null) {
                    body.addView(text(quantSignalLabel(quote.verdict.signal), 12f, quantSignalColor(quote.verdict.signal)).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(8), 0, 0)
                    })
                }
                val analysis = quote.analysis
                if (analysis != null) {
                    body.addView(line())
                    body.addView(text(aiVerdictLabel(analysis.verdict), 13f, aiVerdictColor(analysis.verdict)).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    })
                    body.addView(row(bi("Konvergensi", "Convergence"), String.format(Locale.US, "%.0f%%", analysis.confluence.convergencePercent)))
                    body.addView(row(bi("Rezim Pasar", "Market Regime"), analysis.regime.name.replace('_', ' ')))
                    body.addView(row(bi("Struktur", "Structure"), if (analysis.structure.sufficientData) "${analysis.structure.trend} / ${analysis.structure.lastEvent}" else bi("TIDAK CUKUP", "INSUFFICIENT")))
                    body.addView(row(bi("Volatilitas (ATR)", "Volatility (ATR)"), analysis.volatility.atrPercentOfPrice?.let { String.format(Locale.US, "%.1f%% — %s", it, analysis.volatility.regime.name) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    analysis.volumeProfile?.let { vp -> if (vp.sufficientData) body.addView(row(bi("VPOC (candle-derived)", "VPOC (candle-derived)"), vp.pointOfControl?.let { String.format(Locale.US, "%.4g", it) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE"))) }
                    val mtf = analysis.multiTimeframe
                    body.addView(row(bi("Multi-Timeframe", "Multi-Timeframe"), mtf?.let { it.alignment.name.replace('_', ' ') } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    val ob = analysis.orderBook
                    body.addView(row(bi("Order Book", "Order Book"), if (ob != null && ob.state == DataState.LIVE) ob.imbalancePercent?.let { String.format(Locale.US, "%+.1f%% imbalance", it) } ?: bi("TIDAK LENGKAP", "INCOMPLETE") else bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    val deriv = analysis.derivatives
                    body.addView(row(bi("Funding / OI", "Funding / OI"), if (deriv != null && deriv.state == DataState.LIVE) deriv.fundingRatePercent?.let { String.format(Locale.US, "%.3f%%", it) } ?: bi("TIDAK LENGKAP", "INCOMPLETE") else bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    val liq = analysis.liquidations
                    body.addView(row(bi("Likuidasi (5 mnt)", "Liquidations (5-min)"), if (liq != null && liq.state == DataState.LIVE) String.format(Locale.US, "L\$%.0f / S\$%.0f%s", liq.rollingLongNotional, liq.rollingShortNotional, if (liq.spikeDetected) " ⚡" else "") else bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    val opt = analysis.options
                    body.addView(row(bi("Opsi (P/C OI)", "Options (P/C OI)"), if (opt != null && opt.state == DataState.LIVE) opt.putCallOiRatioDerived?.let { String.format(Locale.US, "%.2f (DERIVED)", it) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE") else bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    val sent = analysis.sentiment
                    body.addView(row(bi("Sentimen (F&G)", "Sentiment (F&G)"), if (sent != null && sent.state == DataState.LIVE && sent.fearGreedValue != null) "${sent.fearGreedValue} (${sent.fearGreedLabel})" else bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    val fund = analysis.fundamentals
                    body.addView(row(bi("Fundamental", "Fundamentals"), if (fund != null && fund.state == DataState.LIVE && fund.facts.isNotEmpty()) bi("TERSEDIA (SEC EDGAR) — lihat narasi", "AVAILABLE (SEC EDGAR) — see narrative") else bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    val corr = analysis.correlation
                    body.addView(row(bi("Korelasi", "Correlation"), if (corr != null && corr.sufficientSamples) corr.coefficient?.let { String.format(Locale.US, "r=%.2f vs %s", it, corr.referenceInstrumentLabel) } ?: bi("TIDAK TERSEDIA", "UNAVAILABLE") else bi("SAMPEL KECIL / TIDAK TERSEDIA", "SMALL SAMPLE / UNAVAILABLE")))
                    val macro = analysis.macro
                    body.addView(row(bi("Makro", "Macro"), if (macro != null && macro.points.any { it.state == DataState.LIVE }) bi("TERSEDIA — lihat narasi", "AVAILABLE — see narrative") else bi("TIDAK TERSEDIA", "UNAVAILABLE")))
                    val risk = analysis.risk
                    body.addView(row(bi("Risiko / Imbalan", "Risk / Reward"), risk?.let { String.format(Locale.US, "1 : %.2f", it.riskRewardRatio) } ?: bi("TIDAK ADA ARAH VALID", "NO VALID DIRECTION")))
                    body.addView(text(analysis.narrative, 10f, gray).apply { setPadding(0, dp(10), 0, dp(2)); setLineSpacing(dp(2).toFloat(), 1f) })
                    analysis.scenarios?.let { s ->
                        body.addView(text(bi("Invalidasi: ", "Invalidation: ") + s.invalidation, 9.5f, pinkElectric).apply { setPadding(0, dp(6), 0, 0) })
                        s.waitCondition?.let { body.addView(text(bi("Menunggu: ", "Waiting for: ") + it, 9.5f, metaGray).apply { setPadding(0, dp(2), 0, 0) }) }
                    }
                    if (!loggedThisSession) {
                        loggedThisSession = true
                        try {
                            MarketSignalLog.append(this, LoggedSignal(
                                timestampMillis = System.currentTimeMillis(), symbol = inst.symbol, timeframe = quantTimeframe,
                                verdict = analysis.verdict, convergencePercent = analysis.confluence.convergencePercent, regime = analysis.regime,
                                entry = quote.lastPrice, stop = risk?.stopLoss, target = risk?.takeProfit,
                                outcome = SignalOutcome.PENDING, timeToOutcomeMillis = null, maxFavorableExcursionPercent = null, maxAdverseExcursionPercent = null
                            ))
                        } catch (_: Exception) { }
                    }
                }
            }
            body.addView(backtestContainer)
            body.addView(button(bi("[ ▶ Jalankan Evaluasi Historis ]", "[ ▶ Run Historical Evaluation ]")) {
                val candlesSnapshot = quote.candles
                com.vr3th.mediacompressor.market.MarketExecutors.io.execute {
                    val result = try { MarketBacktestEngine.run(inst.symbol, quantTimeframe, candlesSnapshot) } catch (_: Exception) { null }
                    runOnUiThread { if (detailAlive && result != null) renderBacktest(result) }
                }
            })
        }

        fun maybeStartDeepDive(q: MarketQuote) {
            if (deepDiveStarted || inst.assetClass != AssetClass.CRYPTO || !q.state.isUsable()) return
            deepDiveStarted = true
            MarketRepository.fetchDeepDiveAsync(this, inst, q, isIndonesian) { enriched -> if (detailAlive) render(enriched) }
        }

        if (isCrypto && binanceSymbol != null) {
            MarketRealtimeManager.start(inst) { state -> renderRealtimeBadge(state) }
            MarketLiquidationFeed.start { if (detailAlive) renderRealtimeBadge(MarketRealtimeManager.currentState()) } // liquidation push just refreshes the badge tick; the narrative/rows read a fresh snapshotFor() via the next deep-dive render
        }

        val rawCached = MarketCache.load(this, inst, quantTimeframe)
        val cached = rawCached?.let { c ->
            if (c.state.isUsable() && c.candles.isNotEmpty()) {
                val a = try { MarketAnalysisEngine.analyze(c, isIndonesian) } catch (_: Exception) { null }
                if (a != null) c.copy(analysis = a) else c
            } else c
        }
        if (cached != null) { render(cached); maybeStartDeepDive(cached) }
        if (isNetworkAvailable()) {
            MarketRepository.fetchQuoteAsync(this, inst, quantTimeframe, isIndonesian) { quote -> render(quote); maybeStartDeepDive(quote) }
        } else if (cached == null) {
            render(MarketQuote.unavailable(inst, "OFFLINE_NO_CACHE"))
        }
        r.addView(button(bi("‹  KEMBALI", "‹  BACK")) { leaveDetail() })
    }

    // Menu / Settings / About (reached via the drawer)
    // =========================================================================

    /**
     * "BLACK-HAT ROOT CONTROL PANEL" — Settings reskinned as an
     * intimidating underground root console: a terminal-style header block,
     * every toggle/info row relabeled as a `.SYS`/`.EXE` console entry, and
     * a real "PURGE_TEMP_CACHE.EXE" root action wired to the existing
     * `engine.purgeTempFiles()` (no MediaEngine.kt changes — that method
     * already exists and is safe/idempotent, it's the same one silently run
     * at every boot). All functionality from the previous Settings screen
     * is preserved, just reskinned; social links stay exclusively in About.
     */
    private fun showSettings() {
        val r = base()
        screenState = ScreenState.OTHER
        topBar("ROOT PANEL", showBack = true, onBack = { settingsOrigin() })
        r.addView(heading("SETTINGS", 22f, white).apply { setPadding(0, dp(18), 0, dp(2)) })
        r.addView(text("// UNDERGROUND SYSTEM CONSOLE", 10f, cyberBlossom))
        r.addView(line())

        r.addView(glassCard(stroke = mint).apply {
            addView(mono("// SETTINGS.EXE\nMEDIACOMPRESSOR CORE\n\nACCESS : ROOT\nBUILD  : 6.66.0\nSTATUS : SECURE", 12.5f, cyberBlossom))
        })

        section("// PROCESSING_CORE", "Root-level engine defaults")
        addToolRow("OFFLINE_MODE.SYS", "Local execution enforced • zero network I/O") { Toast.makeText(this, "Offline processing is active.", Toast.LENGTH_SHORT).show() }
        addToolRow("OUTPUT_POLICY.SYS", "Source files immutable — never overwritten") { Toast.makeText(this, "Original files are preserved.", Toast.LENGTH_SHORT).show() }
        addToolRow("RENDER_PIPELINE.SYS", "Lightweight compositing • no heavy visual effects") { Toast.makeText(this, "Lightweight motion profile active.", Toast.LENGTH_SHORT).show() }

        section("// SYSTEM_IDENTITY")
        r.addView(glassCard().apply {
            addView(mono("DEVELOPER    Vr3tH🇵🇸\nENGINE       MEDIA_PROCESSOR_CORE\nNETWORK      AIR-GAPPED / OFFLINE-FIRST\nINTEGRITY    100%\nSTATUS       OPERATIONAL", 12f, gray))
        })

        section("// ROOT_ACTIONS", "Direct system-level control")
        addToolRow("PURGE_TEMP_CACHE.EXE", "Wipe orphaned temp files from this session's work directory") {
            val freedBytes = engine.purgeTempFiles()
            Toast.makeText(this, "Purged ${formatSize(freedBytes)} of temp cache.", Toast.LENGTH_SHORT).show()
        }

        r.addView(button("‹  EXIT_CONSOLE") { settingsOrigin() })
    }

    private fun socialButton(label: String, handle: String, url: String) {
        addToolRow(label, handle) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showAbout() {
        val r = base()
        screenState = ScreenState.OTHER
        topBar("ABOUT", showBack = true, onBack = { aboutOrigin() })
        r.addView(heading("ABOUT", 22f, white).apply { setPadding(0, dp(18), 0, dp(2)) })
        r.addView(text("ROSE NOIR / DEVELOPER PROFILE", 10f, pinkSoft))
        r.addView(line())
        r.addView(heading("Vr3tH🇵🇸", 22f, white))
        r.addView(text("INDEPENDENT DEVELOPER  /  OFFLINE TOOLING", 10f, pinkSoft))
        r.addView(text("A small offline media workspace, built with care for speed, privacy, and a clean experience.", 13f, gray))
        section("CONNECT", "Official social channels")
        socialButton("WHATSAPP", "Direct contact", "https://wa.me/6288229456210")
        socialButton("INSTAGRAM", "@rsx_xt", "https://www.instagram.com/rsx_xt/")
        socialButton("YOUTUBE", "@oficial_tzy", "https://youtube.com/@oficial_tzy")
        section("ENGINE")
        r.addView(glassCard().apply { addView(mono("LOCAL PROCESSING\nNO CLOUD UPLOAD\nNO ACCOUNT REQUIRED\nLIGHTWEIGHT  /  OFFLINE", 12f, gray)) })
        r.addView(text("\nMEDIA COMPRESSOR  •  v1.0\n© 2026 Vr3tH🇵🇸", 10f, metaGray))
        r.addView(button("‹  BACK") { aboutOrigin() })
    }

    private fun formatSize(n: Long): String {
        if (n < 0) return "UNKNOWN"
        if (n < 1024) return "$n B"
        if (n < 1024 * 1024) return String.format(Locale.US, "%.2f KB", n / 1024.0)
        return String.format(Locale.US, "%.2f MB", n / 1024.0 / 1024.0)
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", total / 3600, (total / 60) % 60, total % 60, ms % 1000)
    }

    companion object {
        private const val REQ_PICK_SINGLE = 77
        private const val REQ_PICK_MULTI = 78
        private const val REQ_REPICK_SINGLE = 79
        private const val REQ_REPICK_MULTI = 80
        private const val REQ_ADD_MORE_MULTI = 81
    }
}

/**
 * Lightweight Canvas-drawn progress track with a Cyber Green → Electric Mint
 * gradient wave fill. No third-party drawing libraries — pure View/Canvas.
 */
/**
 * Samples one authentic ECG lead-II-shaped cycle at fraction [u] in [0,1):
 * a long flat isoelectric baseline, a small rounded P wave, a sharp Q-R-S
 * spike (down-up-down), a broader T wave, then back to baseline. Returns a
 * signed offset in [-1, 1] to be scaled by amplitude. Modeled as a small
 * set of Gaussian-ish bumps rather than a literal clinical formula —
 * visually authentic, cheap to evaluate. Shared by every ECG-style
 * waveform view in the app (the LIVE Monitor's [LiveScannerView] and the
 * active-job [WaveProgressView]) so there's one canonical heartbeat shape
 * instead of two copies drifting apart.
 */
private fun ecgSample(u: Float): Float {
    fun bump(center: Float, width: Float, height: Float): Float {
        val d = (u - center) / width
        return height * Math.exp((-d * d).toDouble()).toFloat()
    }
    var y = 0f
    y += bump(0.18f, 0.035f, 0.10f)             // P wave — small, rounded
    y -= bump(0.27f, 0.010f, 0.22f)              // Q — brief dip
    y += bump(0.295f, 0.012f, 1.0f)              // R — sharp high spike
    y -= bump(0.32f, 0.014f, 0.32f)              // S — brief dip
    y += bump(0.46f, 0.05f, 0.16f)               // T wave — broader, rounded
    return y
}

/**
 * The active-job progress indicator, shown directly under the Processing
 * screen's heading across every one of the 30+ engines and every batch
 * queue. Used to be a plain left-to-right percent-fill bar; now it's the
 * same style of Hospital ECG heartbeat sweep as the LIVE Monitor card, in
 * Matrix Cyber Green — the waveform itself IS the progress indicator, with
 * no separate fill drawn under it. [percent] still drives it (set from
 * [MainActivity]'s progress callback exactly as before, so every call site
 * is unchanged), but instead of sizing a fill rectangle, it now speeds the
 * heartbeat up: a calmer ~1 beat/sec near the start, accelerating toward
 * ~2.6 beats/sec as the job nears 100%, so the pulse itself communicates
 * "working" and "almost done" in real time. The view's own height is fixed
 * by its caller (`dp(14)`, unchanged) and nothing here ever changes that,
 * so the panel around it stays exactly as rigid as before.
 */
private class WaveProgressView(
    context: Context,
    private val trackColor: Int,
    private val colorA: Int,
    private val colorB: Int
) : View(context) {

    var percent: Int = 0
        set(value) { field = value.coerceIn(0, 100) }

    private var phase = 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = context.resources.displayMetrics.density * 2.25f
        color = colorA
    }
    private val rect = RectF()
    private val path = Path()

    // A per-frame clock (~60 ticks/sec) rather than one fixed-duration
    // sweep, so the heartbeat's tempo can accelerate live with [percent]
    // instead of running at one fixed speed for the whole job.
    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val beatsPerSecond = 1f + (percent / 100f) * 1.6f
            phase = (phase + beatsPerSecond * 0.016f) % 1f
            invalidate()
        }
    }

    init { clock.start() }
    fun pauseAnim() { if (clock.isRunning) clock.pause() }
    fun resumeAnim() { if (clock.isPaused) clock.resume() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); clock.cancel() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val mid = h / 2f
        val amplitude = h * 0.42f
        path.reset()
        val steps = 160
        val cyclesPerLoop = 3f
        for (i in 0..steps) {
            val x = w * i / steps
            val u = ((phase * cyclesPerLoop) + i.toFloat() / steps) % 1f
            val y = mid - ecgSample(u) * amplitude
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.save(); canvas.clipRect(rect)
        canvas.drawPath(path, wavePaint)
        canvas.restore()
    }
}

/**
 * LIVE Monitor's animated scanner — a hospital-monitor-style waveform that
 * visually communicates the engine is alive even with nothing running.
 * Three modes:
 *  - IDLE: never a dead flat/blinking line — a smooth, gentle, continuously
 *    drifting undulating wave in Matrix Cyber Green, so the engine reads as
 *    quietly "alive" even with nothing running. Near-zero CPU cost, one
 *    lightweight ValueAnimator.
 *  - PROCESSING: an authentic multi-beat ECG heartbeat / hospital-pulse
 *    waveform (flat baseline → small P wave → sharp high-amplitude Q-R-S
 *    spike → T wave → flat baseline), several beats continuously sweeping
 *    left-to-right — this waveform IS the progress indicator; there's no
 *    separate percent-fill bar drawn underneath it.
 *  - COMPLETED: the same ECG heartbeat sweep keeps running — never a
 *    static flash or a frozen bar — while the card's label/badge announce
 *    the result; caller reverts to IDLE after ~1.6s.
 * The animator itself is paused/resumed by the Activity lifecycle (see
 * MainActivity.onPause/onResume), not just view attach. The card container
 * around this view is never rebuilt while sweeping — only mode/percent
 * change and invalidate() — so switching files mid-batch never flickers.
 */
private class LiveScannerView(context: Context, private val trackColor: Int, private val colorA: Int, private val colorB: Int) : View(context) {

    companion object { const val IDLE = 0; const val PROCESSING = 1; const val COMPLETED = 2 }

    var mode: Int = IDLE
        set(value) { field = value; invalidate() }
    var percent: Int = 0
        set(value) { field = value.coerceIn(0, 100) }

    private var phase = 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = context.resources.displayMetrics.density * 2.5f
    }
    private val rect = RectF()
    private val path = Path()

    // One continuous sweeping animator shared by both IDLE (gentle undulating
    // drift) and PROCESSING (ECG heartbeat) — only its interpretation inside
    // onDraw changes per-mode, so there's never a restart/jump when a task
    // starts or ends mid-sweep.
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init { animator.start() }
    fun pauseAnim() { if (animator.isRunning) animator.pause() }
    fun resumeAnim() { if (animator.isPaused) animator.resume() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val mid = h / 2f
        path.reset()

        if (mode == PROCESSING || mode == COMPLETED) {
            // Multi-beat sweeping ECG heartbeat — several full P-QRS-T
            // cycles per animator loop, so it reads as a continuous,
            // rhythmic hospital-monitor pulse train (not one lone beat),
            // dynamically in sync with the live job for as long as it runs.
            // Also kept alive through COMPLETED: a finished job never
            // freezes into a static flash — only the card's label/badge
            // change to announce completion, the wave itself never stops
            // sweeping. This waveform is the only progress visualization
            // here — no separate percent-fill bar is drawn underneath it.
            val amplitude = h * 0.42f
            wavePaint.color = colorA
            wavePaint.strokeWidth = context.resources.displayMetrics.density * 2.75f
            val steps = 200
            val cyclesPerLoop = 3f
            for (i in 0..steps) {
                val x = w * i / steps
                val u = ((phase * cyclesPerLoop) + i.toFloat() / steps) % 1f
                val y = mid - ecgSample(u) * amplitude
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            wavePaint.alpha = 255
        } else {
            // IDLE — never a flat/blinking line: a smooth, gentle,
            // continuously drifting undulating wave, low-amplitude and
            // slow, so the card visibly "breathes" instead of looking dead.
            val amplitude = h * 0.16f
            wavePaint.color = colorA
            wavePaint.strokeWidth = context.resources.displayMetrics.density * 2f
            wavePaint.alpha = 255
            val steps = 96
            val cycles = 2.2f
            for (i in 0..steps) {
                val x = w * i / steps
                val xf = i.toFloat() / steps
                val y = mid + sin((xf * cycles + phase) * 2f * Math.PI.toFloat()) * amplitude
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }

        canvas.save(); canvas.clipRect(rect)
        canvas.drawPath(path, wavePaint)
        canvas.restore()
        wavePaint.alpha = 255
    }
}

/**
 * "AKSES LUMI" header accent — a fast, continuously-scrolling zigzag
 * (/\/\/\/\) neural voice waveform, drawn programmatically on Canvas via a
 * sampled triangle wave (no third-party drawing libraries). Represents
 * Lumi's active listening/speaking frequency in Matrix Cyber Green.
 * Paused/resumed by Activity onPause/onResume, same as [LiveScannerView].
 */
private class ZigzagWaveformView(context: Context, lineColor: Int) : View(context) {
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = lineColor
        strokeWidth = context.resources.displayMetrics.density * 2f
    }
    private val path = Path()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900 // fast, per spec
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { animator.start() }
    fun pauseAnim() { if (animator.isRunning) animator.pause() }
    fun resumeAnim() { if (animator.isPaused) animator.resume() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        path.reset()
        val cycles = 3.5f
        val samples = 40
        for (i in 0..samples) {
            val xFrac = i / samples.toFloat()
            val t = (xFrac * cycles + phase) * (2f * Math.PI.toFloat())
            // Triangle wave via asin(sin(t)) — smooth to sample, sharp to render: exactly /\/\/\/\.
            val triangle = (2f / Math.PI.toFloat()) * asin(sin(t))
            val x = xFrac * w
            val y = h / 2f - triangle * (h * 0.42f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}

// The per-asset sparkline is now MarketSparklineView, in the isolated
// `market` package (com.vr3th.mediacompressor.market.MarketCharts.kt),
// since it's Market-only UI, not shared with the rest of the app.
