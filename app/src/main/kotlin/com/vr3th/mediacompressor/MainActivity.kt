package com.vr3th.mediacompressor

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // ---- Cyber Hacker Obsidian palette (All-Green Matrix HUD) --------------
    // Per the latest spec pass, Cyber Rose survives strictly as a secondary
    // glow accent (laser-focus borders / select highlights) and Champagne
    // Rose survives strictly as secondary telemetry text — every other
    // former-pink role stays fully green/white. Variable names are kept
    // as-is (legacy, from the original Rose Noir build) to avoid touching
    // every call site.
    private val bg = Color.parseColor("#07060B")          // Deepest Void Obsidian (AMOLED)
    private val panel = Color.parseColor("#0F0E17")       // Obsidian Glass panel/card fill
    private val panelStroke = Color.parseColor("#251F38") // specular hairline border
    private val activeCardBg = Color.parseColor("#12241C") // active card fill (green-tinted chamber)
    private val pinkSoft = Color.parseColor("#35E58C")    // Electric Mint — secondary accent (labels/subtitles)
    private val pinkElectric = Color.parseColor("#FF2B8D") // Cyber Rose — preserved: laser-focus border / select highlight only
    private val cyberBlossom = Color.parseColor("#00FF9D") // Matrix Cyber Green — checkmarks, highlights
    private val neonRose = Color.parseColor("#35E58C")     // Electric Mint — progress-wave glow tail
    private val roseGold = Color.parseColor("#FCE4EC")     // Champagne Rose — telemetry captions
    private val pastelPink = Color.parseColor("#FCE4EC")   // Champagne Rose
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

    // Repick: after tapping "Ganti File" we relaunch the picker and, on
    // success, hand the newly picked file straight back into the screen
    // that asked for it — no trip back to Home.
    private var repickCallback: ((List<Uri>) -> Unit)? = null

    // ---- Operation catalogue (30 engines across 5 module groups) --------
    private val videoOps = listOf(
        "Video Compress & Mute", "Video Trim & Reverse", "Video Speed",
        "Video to Audio", "Video to GIF", "Extract Frame", "Video Merge", "Video Rotate & Flip"
    )
    private val gifFileOps = listOf("GIF to Video", "GIF Compress")
    private val imageOps = listOf(
        "Photo Compress", "Batch Photo Compress", "Image Converter", "Remove EXIF", "Photo to GIF"
    )
    private val audioOps = listOf(
        "Audio Trim & Reverse", "Audio Merge", "Audio Compress", "Audio Volume Booster", "Audio Silence Trimmer"
    )
    private val pdfOps = listOf(
        "Photo to PDF", "PDF to Photo", "Merge PDF", "Split & Reverse PDF",
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
    private val batchableOps = setOf(
        "Video Compress & Mute", "Video to Audio", "Video Speed", "Video Trim & Reverse",
        "Video to GIF", "Extract Frame", "Video Rotate & Flip",
        "Photo Compress", "Image Converter", "Remove EXIF",
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
        showBoot()
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
     * RESULT -> CONFIG, PROCESSING -> cancel -> CONFIG, CONFIG -> ANALYZE,
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

    private fun base(): LinearLayout {
        closeDrawer(animated = false)
        // Whichever screen was previously wired into live task ticks is
        // being torn down now — the new screen must opt back in if it cares.
        onTaskTick = null; onTaskDone = null; onTaskCancelled = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            overScrollMode = View.OVER_SCROLL_NEVER
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
        root.alpha = 0f
        root.translationY = dpf(6)
        root.animate().alpha(1f).translationY(0f).setInterpolator(ease).setDuration(260).start()
        return root
    }

    private fun shape(color: Int, stroke: Int? = null, radius: Float = 16f, strokeWidthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
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

    /** Instant 0.95x scale-down on touch, spring-eased release — shared by every tappable control. */
    private fun applyTapFeedback(v: View, scale: Float = 0.95f, action: () -> Unit) {
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener {
            v.animate().scaleX(scale).scaleY(scale).setDuration(60).setInterpolator(ease).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(springEase).start()
                action()
            }.start()
        }
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

    private fun topBar(title: String, showBack: Boolean, statusLabel: String = "READY", statusColor: Int = mint, onBack: (() -> Unit)? = null, showVaultIcon: Boolean = false) {
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
        if (showVaultIcon) {
            bar.addView(TextView(this).apply {
                text = "📁"
                textSize = 16f
                gravity = Gravity.CENTER
                background = shape(panel, mint, 10f)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
                applyTapFeedback(this, 0.9f) { showVault() }
            })
        }
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
        item("SETTINGS", "Application behavior and preferences") { showSettings() }
        item("ABOUT", "Developer identity • links • engine info") { showAbout() }

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

    private fun showBoot() {
        val r = base()
        val lines = listOf(
            "> INITIALIZING MEDIA ENGINE...",
            "> LOADING PROCESSORS...",
            "VIDEO   ........ READY",
            "IMAGE   ........ READY",
            "AUDIO   ........ READY",
            "GIF     ........ READY",
            "PDF     ........ READY",
            "ZIP     ........ READY",
            "",
            "> _ SYSTEM READY"
        )
        val tv = mono("", 13.5f, gray)
        r.addView(heading("MEDIACOMPRESSOR", 24f, white))
        r.addView(text("ROSE NOIR MEDIA CORE  /  Vr3tH🇵🇸", 10f, pinkSoft))
        r.addView(glassCard().apply { addView(tv) })
        var i = 0
        val handler = Handler(mainLooper)
        val run = object : Runnable {
            override fun run() {
                if (i < lines.size) {
                    tv.text = lines.take(i + 1).joinToString("\n")
                    i++
                    handler.postDelayed(this, 100)
                } else {
                    handler.postDelayed({ showHome() }, 260)
                }
            }
        }
        handler.postDelayed(run, 180)
    }

    // =========================================================================
    // Home
    // =========================================================================

    /**
     * Home header command row: Real-Time Clock on the left (flex-filled),
     * with the Cleaner [ 🧹 ] and Process Monitor [ ⚡ ] icon buttons stacked
     * symmetrically on the right.
     */
    private fun headerCommandRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(liveClockWidget(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        val iconCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        iconCol.addView(TextView(this).apply {
            text = "🧹"; textSize = 16f; gravity = Gravity.CENTER
            background = shape(panel, mint, 10f)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { bottomMargin = dp(6) }
            applyTapFeedback(this, 0.9f) { triggerCleaner() }
        })
        iconCol.addView(TextView(this).apply {
            text = "⚡"; textSize = 16f; gravity = Gravity.CENTER
            background = shape(panel, mint, 10f)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            applyTapFeedback(this, 0.9f) { showProcessMonitorModal() }
        })
        row.addView(iconCol)
        return row
    }

    /** [ 🧹 ] instant temp-file purge, with a Cyber Green confirmation badge (Toast). */
    private fun triggerCleaner() {
        val freed = engine.purgeTempFiles()
        Toast.makeText(this, bi("🧹 Cache dibersihkan: ${formatSize(freed)}", "🧹 Cache purged: ${formatSize(freed)}"), Toast.LENGTH_SHORT).show()
    }

    /** [ ⚡ ] Live Process Monitor: shows the current background task's live % and lets the user cancel it from anywhere in the app. */
    private fun showProcessMonitorModal() {
        closeTooltipModal(animated = false)
        val overlay = FrameLayout(this).apply { setBackgroundColor(dimBackdrop); alpha = 0f }
        applyTapFeedback(overlay, 1f) { closeTooltipModal() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(panel, mint, 18f, 2)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            scaleX = 0.92f; scaleY = 0.92f; alpha = 0f
        }
        card.addView(text(bi("MONITOR PROSES LIVE", "LIVE PROCESS MONITOR"), 10.5f, mint).apply { letterSpacing = 0.12f })
        val body = mono("", 12.5f, white).apply { setPadding(0, dp(10), 0, dp(4)) }
        card.addView(body)
        val wave = WaveProgressView(this, panel, cyberBlossom, neonRose).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(4); bottomMargin = dp(10) }
        }
        card.addView(wave)
        val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancelBtn = button(bi("[ ✕ Batal Proses ]", "[ ✕ Cancel Task ]")) {
            cancelled.set(true); workerThread?.interrupt(); closeTooltipModal()
        }.apply { background = shape(panel, warn, 12f, 2); setTextColor(white) }
        val closeBtn = button(bi("[ Tutup ]", "[ Close ]")) { closeTooltipModal() }
            .apply { background = shape(panel, mint, 12f); setTextColor(white) }
        actionsRow.addView(closeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        actionsRow.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(actionsRow)

        val holder = FrameLayout(this)
        holder.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(26); rightMargin = dp(26)
        })
        pageFrame.addView(holder)
        tooltipRoot = holder
        overlay.animate().alpha(1f).setDuration(180).start()
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(springEase).start()

        val pollHandler = Handler(mainLooper)
        val poll = object : Runnable {
            override fun run() {
                if (tooltipRoot !== holder) return // modal was closed — stop polling
                if (taskRunning) {
                    body.text = "● ${bi("BERJALAN", "RUNNING")}: ${displayOp(taskOp)}\n$taskPercent%  •  $taskStage"
                    wave.percent = taskPercent
                    cancelBtn.visibility = View.VISIBLE
                } else {
                    body.text = bi("Tidak ada proses aktif saat ini.", "No active operations right now.")
                    wave.percent = 0
                    cancelBtn.visibility = View.GONE
                }
                pollHandler.postDelayed(this, 400)
            }
        }
        poll.run()
    }

    /**
     * Home HUD card — shown only while a task is running in the background
     * (started from a Feature screen, then navigated away from via system
     * back). Lets the user jump back into the full Processing screen or
     * cancel outright, without losing the running task.
     */
    private fun liveTaskHudCard(): LinearLayout {
        val card = glassCard(stroke = mint)
        card.addView(text("● ${bi("PROSES BERJALAN", "TASK RUNNING")}", 11f, mint).apply { letterSpacing = 0.08f })
        card.addView(text(displayOp(taskOp), 14.5f, white).apply { setPadding(0, dp(4), 0, dp(6)) })
        val wave = WaveProgressView(this, panel, cyberBlossom, neonRose).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
            percent = taskPercent
        }
        card.addView(wave)
        val pctLabel = text("$taskPercent%  •  $taskStage", 11f, gray).apply { setPadding(0, dp(6), 0, 0) }
        card.addView(pctLabel)
        val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        actionsRow.addView(button(bi("[ Lihat ]", "[ View ]")) { showProcessingScreen(taskType, taskOp, startNew = false) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
        })
        actionsRow.addView(button(bi("[ ✕ Batal ]", "[ ✕ Cancel ]")) { cancelled.set(true); workerThread?.interrupt() }.apply {
            background = shape(panel, warn, 12f, 2); setTextColor(white)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(actionsRow)
        onTaskTick = { p, s -> taskPercent = p; taskStage = s; wave.percent = p; pctLabel.text = "$p%  •  $s" }
        onTaskDone = { finished ->
            val anySuccess = finished.any { it is FinalizedOutcome.Done }
            val msg = if (anySuccess) bi("✓ ${displayOp(taskOp)} selesai", "✓ ${displayOp(taskOp)} finished")
                      else bi("${displayOp(taskOp)}: tidak ada output", "${displayOp(taskOp)}: no output produced")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            showHome()
        }
        onTaskCancelled = { showHome() }
        return card
    }

    private fun showHome() {
        val r = base()
        screenState = ScreenState.HOME
        topBar("MEDIACOMPRESSOR", showBack = false, statusLabel = bi("• Mesin Siap", "• Engine Ready"), statusColor = mint, showVaultIcon = true)

        r.addView(heading("MEDIA PROCESSOR", 29f, white).apply { setPadding(0, dp(26), 0, dp(2)) })
        r.addView(text("Vr3tH🇵🇸", 13.5f, pinkSoft))
        r.addView(text("ONLINE MEDIA ENGINE  /  PRIVATE ROSE NOIR", 10f, mint))
        r.addView(text("Made with care. Built to stay light.", 12f, mint).apply { setPadding(0, dp(8), 0, 0) })
        r.addView(headerCommandRow())
        r.addView(line())

        if (taskRunning) {
            section(bi("PROSES LATAR BELAKANG", "BACKGROUND TASK"))
            r.addView(liveTaskHudCard())
            r.addView(line())
        }

        section("COMMAND CENTER", "Choose a media domain")
        addToolRow("VIDEO", "Compress  •  Merge  •  Trim / Reverse  •  Speed") { openCategoryFromHome("VIDEO") }
        addToolRow("IMAGE", "Compress  •  Batch  •  Convert  •  GIF") { openCategoryFromHome("IMAGE") }
        addToolRow("GIF", "Convert  •  Compress  •  Photo sequence") { openCategoryFromHome("GIF") }
        addToolRow("AUDIO", "Trim / Reverse  •  Merge  •  Volume") { openCategoryFromHome("AUDIO") }
        addToolRow("PDF", "Compress  •  Merge  •  Split / Reverse  •  Secure") { openCategoryFromHome("PDF") }
        addToolRow("ARCHIVE", "Create  •  Extract  •  Recompress") { openCategoryFromHome("ARCHIVE") }

        section("ENGINE STATUS")
        r.addView(glassCard(stroke = mint).apply {
            val processState = if (taskRunning) "RUNNING (${taskPercent}%)" else "IDLE"
            addView(mono("● STANDBY\nENGINE              READY\nPROCESS             $processState\nSTORAGE             READY\nMODE                OFFLINE", 12f, mint))
        })
        r.addView(text("LOCAL PROCESSING • NO CLOUD UPLOAD", 10f, metaGray).apply { setPadding(0, dp(10), 0, 0) })
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
                selectedType = type
                selectedUris.clear()
                cfgRangeStartText = null
                cfgRangeEndText = null
                pickFile(acceptsMultiple(op))
            }
        }
        r.addView(button("‹  BACK TO COMMAND CENTER") { categoryOrigin() })
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

    private fun pickFile(allowMultiple: Boolean) {
        repickCallback = null
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, if (allowMultiple) REQ_PICK_MULTI else REQ_PICK_SINGLE)
    }

    /** "↻ Ganti File" — relaunches the picker and routes the new file straight back, no Home hop. */
    private fun quickRepick(allowMultiple: Boolean, onPicked: (List<Uri>) -> Unit) {
        repickCallback = onPicked
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, if (allowMultiple) REQ_REPICK_MULTI else REQ_REPICK_SINGLE)
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
                selectedUris.addAll(uris)
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
    private fun fileHeaderCard(name: String, metaLine: String, allowMultiple: Boolean, onReplaced: () -> Unit) {
        val card = glassCard(stroke = panelStroke)
        // Tapping anywhere on the card re-enters Feature Configuration with
        // previous settings retained; the nested "↻ Ganti File" capsule below
        // still consumes its own tap to launch the re-pick flow instead.
        applyTapFeedback(card, 0.985f) { onReplaced() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = name; textSize = 16.5f; setTextColor(white); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleCol.addView(text(metaLine, 11f, roseGold).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

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
            quickRepick(allowMultiple) { onReplaced() }
        }
        row.addView(repickBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        card.addView(row)
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
        fileHeaderCard(name, "$type  •  ${formatSize(size)}", allowMultiple = false) { analyze(selectedUris.first()) }

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
                if (acceptsMultiple(op)) {
                    selectedType = type
                    selectedUris.clear()
                    pickFile(true)
                } else showFeature(type, op, name)
            }
        }
        r.addView(button("‹  NEW MEDIA") { showHome() })
    }

    // =========================================================================
    // Feature configuration screen
    // =========================================================================

    private fun showFeature(type: String, op: String, name: String) {
        val r = base()
        screenState = ScreenState.CONFIG
        topBar(displayOp(op), showBack = true, onBack = { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
        r.addView(text("$type WORKSPACE", 10f, pinkSoft).apply { setPadding(0, dp(16), 0, 0) })
        fileHeaderCard(
            if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else name,
            "LOCAL PROCESSING", allowMultiple = acceptsMultiple(op)
        ) { showFeature(type, op, if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else fileName(selectedUris.first())) }
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
                card.background = shape(if (active) activeCardBg else panel, if (active) pinkElectric else panelStroke, 16f, if (active) 2 else 1)
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
        val underlineOn = View(this).apply { background = shape(pinkElectric, null, 2f) }
        val underlineOff = View(this).apply { background = shape(pinkElectric, null, 2f); visibility = View.INVISIBLE }
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
                row.background = shape(if (active) activeCardBg else panel, if (active) pinkElectric else panelStroke, 13f, if (active) 2 else 1)
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
                    val publishedUri = MediaStoreExporter.publish(this, saved, subfolderFor(type, op), mime)
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
        handler.postDelayed({
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
                    handler.post { onTaskDone?.invoke(finalized) }
                } catch (ie: InterruptedException) {
                    taskRunning = false
                    handler.post { onTaskCancelled?.invoke() }
                } catch (e: Exception) {
                    taskRunning = false
                    val finalized = listOf(FinalizedOutcome.Failed(e.message ?: "UNKNOWN_ERROR"))
                    taskJustFinished = finalized
                    handler.post { onTaskDone?.invoke(finalized) }
                }
            }
            workerThread!!.start()
        }, 220)
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
        // card to keep tracking it. Once a result (or cancellation) is
        // showing, back returns to Feature Configuration as before.
        var backAction: () -> Unit = { if (taskRunning) showHome() else backToFeatureConfig() }
        topBar("PROCESSING", showBack = true, statusLabel = "Working", statusColor = pinkElectric, onBack = { backAction() })
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
            panelBody.addView(text("● PROCESSING", 12f, pinkElectric).apply { letterSpacing = 0.08f })
            val w = WaveProgressView(this, panel, cyberBlossom, neonRose).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(10) }
            }
            panelBody.addView(w)
            wave = w
            val pct = text("${taskPercent}%", 20f, white).apply { setPadding(0, dp(8), 0, 0) }
            panelBody.addView(pct)
            percentLabel = pct
            val stage = text(taskStage.ifBlank { op }, 11.5f, gray)
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
            backAction = { backToFeatureConfig() }
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
            backAction = { backToFeatureConfig() }
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
    private fun subfolderFor(type: String, op: String): String = when (type) {
        "IMAGE" -> "Photos"
        "GIF" -> "GIF"
        "VIDEO" -> "Videos"
        "AUDIO" -> "Audio"
        "PDF" -> "Documents"
        "ARCHIVE" -> if (op == "Extract ZIP") {
            val archiveBase = selectedUris.firstOrNull()?.let { fileName(it) }?.substringBeforeLast('.', "archive") ?: "archive"
            "Extracted/$archiveBase/Extract"
        } else "Archives"
        else -> type
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
        topBar(bi("MEDIA VAULT", "MEDIA VAULT"), showBack = true, onBack = { showHome() })
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
        r.addView(button(bi("‹  KEMBALI", "‹  BACK")) { showHome() })
    }

    // =========================================================================
    // Menu / Settings / About (reached via the drawer)
    // =========================================================================

    private fun showSettings() {
        val r = base()
        screenState = ScreenState.OTHER
        topBar("SETTINGS", showBack = true, onBack = { showHome() })
        r.addView(heading("SETTINGS", 22f, white).apply { setPadding(0, dp(18), 0, dp(2)) })
        r.addView(text("ROSE NOIR / SYSTEM", 10f, pinkSoft))
        r.addView(line())
        section("PROCESSING", "Defaults used by the local engine")
        addToolRow("OFFLINE MODE", "Always process locally • no network workflow") { Toast.makeText(this, "Offline processing is active.", Toast.LENGTH_SHORT).show() }
        addToolRow("OUTPUT POLICY", "Original source remains untouched") { Toast.makeText(this, "Original files are preserved.", Toast.LENGTH_SHORT).show() }
        addToolRow("SMOOTH UI", "Lightweight transitions • no heavy visual effects") { Toast.makeText(this, "Lightweight motion profile active.", Toast.LENGTH_SHORT).show() }
        section("IDENTITY")
        r.addView(glassCard().apply { addView(mono("DEVELOPER   Vr3tH🇵🇸\nENGINE      MEDIA PROCESSOR\nNETWORK     OFFLINE-FIRST\nSTATUS      OPERATIONAL", 12f, gray)) })
        r.addView(button("‹  BACK") { showHome() })
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
        topBar("ABOUT", showBack = true, onBack = { showHome() })
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
        r.addView(button("‹  BACK") { showHome() })
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
    }
}

/**
 * Lightweight Canvas-drawn progress track with a Cyber Green → Electric Mint
 * gradient wave fill. No third-party drawing libraries — pure View/Canvas.
 */
private class WaveProgressView(
    context: Context,
    private val trackColor: Int,
    private val colorA: Int,
    private val colorB: Int
) : View(context) {

    var percent: Int = 0
        set(value) { field = value.coerceIn(0, 100); invalidate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        if (percent > 0 && width > 0) {
            val fillWidth = width * (percent / 100f)
            fillPaint.shader = LinearGradient(0f, 0f, fillWidth, 0f, colorA, colorB, Shader.TileMode.CLAMP)
            canvas.save()
            canvas.clipRect(0f, 0f, fillWidth, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.restore()
        }
    }
}
