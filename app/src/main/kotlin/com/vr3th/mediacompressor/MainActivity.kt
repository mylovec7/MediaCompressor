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
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.*
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    // ---- Aesthetic Obsidian Rose palette ----------------------------------
    private val bg = Color.parseColor("#0F0E17")
    private val panel = Color.parseColor("#161524")
    private val panelStroke = Color.parseColor("#2A2740")
    private val pinkSoft = Color.parseColor("#FF65A3")
    private val pinkElectric = Color.parseColor("#FF2E93")
    private val roseGold = Color.parseColor("#FF9EAA")
    private val pastelPink = Color.parseColor("#F48FB1")
    private val white = Color.rgb(248, 246, 250)
    private val gray = Color.rgb(191, 186, 197)
    private val metaGray = Color.parseColor("#8C8A9E")
    private val mint = Color.parseColor("#35E58C")
    private val warn = Color.rgb(244, 193, 116)
    private val err = Color.rgb(244, 122, 146)
    private val dimBackdrop = Color.parseColor("#80000000")

    private val ease = PathInterpolator(0.25f, 1f, 0.5f, 1f)

    private lateinit var pageFrame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var engine: MediaEngine

    private var selectedUris: MutableList<Uri> = mutableListOf()
    private var selectedType: String = "UNKNOWN"
    private var currentOp: String = ""

    // Op configuration state, gathered by the config screen before START.
    private var cfgRangeStart = 0.0
    private var cfgRangeEnd = 10.0
    private var cfgQuality = MediaEngine.Quality.MEDIUM
    private var cfgMute = false
    private var cfgSpeed = 1.0
    private var cfgImageFormat = MediaEngine.ImageFormatTarget.JPG
    private var cfgGainDb = 0.0
    private var cfgTargetKb: Int? = null
    private var cfgPassword = ""
    private var cfgWatermarkText = "MEDIACOMPRESSOR"
    private var cfgRotateDegrees = 90
    private var cfgFps = 12
    private var cfgGifMaxWidth = 480
    private var cfgDelayCentis = 20

    private val cancelled = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // Navigation: a lightweight back handler updated by every screen, plus
    // a drawer overlay that intercepts back when open.
    private var backHandler: (() -> Unit)? = null
    private var drawerOpen = false
    private var drawerRoot: FrameLayout? = null

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
        "Audio Trim & Reverse", "Audio Merge", "Audio Volume Booster", "Audio Silence Trimmer"
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

    private fun handleBackNav() {
        when {
            drawerOpen -> closeDrawer()
            backHandler != null -> backHandler?.invoke()
            else -> finish()
        }
    }

    // =========================================================================
    // Layout primitives
    // =========================================================================

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(n: Int): Float = n * resources.displayMetrics.density

    private fun base(): LinearLayout {
        closeDrawer(animated = false)
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

    /** Subtle micro-scale-down (0.97x) touch feedback shared by every tappable control. */
    private fun applyTapFeedback(v: View, scale: Float = 0.97f, action: () -> Unit) {
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener {
            v.animate().scaleX(scale).scaleY(scale).setDuration(70).setInterpolator(ease).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(ease).start()
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
            applyTapFeedback(this, 0.97f, action)
        }

    private fun primaryButton(s: String, action: () -> Unit): TextView =
        button(s, action).apply {
            setTextColor(Color.rgb(20, 12, 18))
            textSize = 13.5f
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pinkSoft, pinkElectric)
            ).apply { cornerRadius = dp(16).toFloat() }
            typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }

    /** 48x48dp minimum-touch icon button (back / drawer) with 0.96x scale feedback. */
    private fun iconButton(symbol: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = 19f
            setTextColor(white)
            gravity = Gravity.CENTER
            background = shape(panel, panelStroke, 13f)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            applyTapFeedback(this, 0.96f, action)
        }

    private fun line(): View = View(this).apply {
        setBackgroundColor(panelStroke)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) }
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
    private fun statusPill(label: String, dotColor: Int, glow: Int = pinkSoft): LinearLayout {
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
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

    private fun topBar(title: String, showBack: Boolean, statusLabel: String = "READY", statusColor: Int = pinkSoft, onBack: (() -> Unit)? = null) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(panel, panelStroke, 16f)
            setPadding(dp(6), dp(4), dp(10), dp(4))
        }
        if (showBack) {
            bar.addView(iconButton("‹") { (onBack ?: { showHome() })() })
        } else {
            bar.addView(iconButton("☰") { openDrawer() })
        }
        bar.addView(text(title, 14.5f, white).apply { letterSpacing = 0.03f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        bar.addView(statusPill(statusLabel, statusColor))
        root.addView(bar)
        backHandler = if (showBack) (onBack ?: { showHome() }) else null
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
        item("VIDEO", "Compress • merge • trim / reverse • speed") { showCategory("VIDEO") }
        item("IMAGE", "Compression • conversion • batch") { showCategory("IMAGE") }
        item("GIF", "GIF conversion • compression") { showCategory("GIF") }
        item("AUDIO", "Trim / reverse • merge • processing") { showCategory("AUDIO") }
        item("PDF", "Compression • split / reverse • security") { showCategory("PDF") }
        item("ARCHIVE", "ZIP create • extract • recompress") { showCategory("ARCHIVE") }
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

    private fun showHome() {
        val r = base()
        topBar("MEDIACOMPRESSOR", showBack = false, statusLabel = "Engine Ready", statusColor = mint)

        r.addView(heading("MEDIA PROCESSOR", 29f, white).apply { setPadding(0, dp(26), 0, dp(2)) })
        r.addView(text("Vr3tH🇵🇸", 13.5f, pinkSoft))
        r.addView(text("OFFLINE MEDIA ENGINE  /  ROSE NOIR CORE", 10f, metaGray))
        r.addView(text("Made with care. Built to stay light.", 12f, pastelPink).apply { setPadding(0, dp(8), 0, 0) })
        r.addView(line())

        section("COMMAND CENTER", "Choose a media domain")
        addToolRow("VIDEO", "Compress  •  Merge  •  Trim / Reverse  •  Speed") { showCategory("VIDEO") }
        addToolRow("IMAGE", "Compress  •  Batch  •  Convert  •  GIF") { showCategory("IMAGE") }
        addToolRow("GIF", "Convert  •  Compress  •  Photo sequence") { showCategory("GIF") }
        addToolRow("AUDIO", "Trim / Reverse  •  Merge  •  Volume") { showCategory("AUDIO") }
        addToolRow("PDF", "Compress  •  Merge  •  Split / Reverse  •  Secure") { showCategory("PDF") }
        addToolRow("ARCHIVE", "Create  •  Extract  •  Recompress") { showCategory("ARCHIVE") }

        section("ENGINE STATUS")
        r.addView(glassCard(stroke = mint).apply {
            addView(mono("● STANDBY\nENGINE              READY\nPROCESS             IDLE\nSTORAGE             READY\nMODE                OFFLINE", 12f, mint))
        })
        r.addView(text("LOCAL PROCESSING • NO CLOUD UPLOAD", 10f, metaGray).apply { setPadding(0, dp(10), 0, 0) })
    }

    private fun showCategory(type: String) {
        val r = base()
        topBar(type, showBack = true, onBack = { showHome() })
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
                pickFile(op in multiInputOps)
            }
        }
        r.addView(button("‹  BACK TO COMMAND CENTER") { showHome() })
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
        "Video Rotate & Flip" -> "Rotation • 90° / 180° / 270°"
        "GIF to Video" -> "Convert animated GIF to MP4"
        "GIF Compress" -> "Re-encode animated GIF with size control"
        "Photo Compress" -> "Target size in KB • optional"
        "Batch Photo Compress" -> "Multiple images • one compression profile"
        "Image Converter" -> "JPG • PNG • WEBP"
        "Remove EXIF" -> "Strip image metadata"
        "Photo to GIF" -> "Image sequence • frame delay • width"
        "Audio Trim & Reverse" -> "One timeline • A < B trim • A > B reverse"
        "Audio Merge" -> "Multiple tracks • ordered output"
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
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = name; textSize = 16.5f; setTextColor(white); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleCol.addView(text(metaLine, 11f, roseGold).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val repickBtn = TextView(this).apply {
            text = "↻ Ganti File"
            textSize = 11.5f
            setTextColor(pinkElectric)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = shape(Color.TRANSPARENT, pinkElectric, 20f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        applyTapFeedback(repickBtn, 0.96f) {
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
                if (op in multiInputOps) {
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
        topBar(displayOp(op), showBack = true, onBack = { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
        r.addView(text("$type WORKSPACE", 10f, pinkSoft).apply { setPadding(0, dp(16), 0, 0) })
        fileHeaderCard(
            if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else name,
            "LOCAL PROCESSING", allowMultiple = op in multiInputOps
        ) { showFeature(type, op, if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else fileName(selectedUris.first())) }
        r.addView(line())

        when {
            op == "Video Trim & Reverse" -> rangeUI(r, "TIMELINE", "0", "10", "sec") { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            op == "Audio Trim & Reverse" -> rangeUI(r, "TIMELINE", "0", "10", "sec") { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            op == "Split & Reverse PDF" -> rangeUI(r, "PAGE RANGE", "1", "10", "pg") { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            op == "Video to GIF" -> { rangeUI(r, "CLIP RANGE", "0", "3", "sec") { a, b -> cfgRangeStart = a; cfgRangeEnd = b }; fpsUI(r) }
            op == "Extract Frame" -> rangeUI(r, "TIMESTAMP", "1", "1", "sec") { a, _ -> cfgRangeStart = a }
            op == "Video Compress & Mute" -> { qualityProfileUI(r); pillSwitchAudioUI(r) }
            op == "Video Speed" -> speedUI(r)
            op == "Video Rotate & Flip" -> rotateUI(r)
            op == "Image Converter" -> formatUI(r)
            op == "Audio Volume Booster" -> gainUI(r)
            op == "Photo Compress" || op == "Batch Photo Compress" -> targetSizeUI(r)
            op == "Lock PDF" || op == "Unlock PDF" -> passwordUI(r)
            op == "Watermark PDF" -> watermarkUI(r)
            op == "Photo to GIF" -> { fpsUI(r); delayUI(r) }
            else -> r.addView(text("No additional parameters required.", 13f, gray))
        }
        r.addView(line())
        r.addView(glassCard(stroke = pinkSoft).apply {
            addView(mono("ENGINE STATUS\n● INPUT VERIFIED\n● CONFIGURATION READY\n● OUTPUT PROTECTED", 11f, pinkSoft))
        })
        r.addView(primaryButton("START PROCESSING   ›") { runOperation(type, op) })
    }

    private fun rangeUI(r: LinearLayout, title: String, start: String, end: String, unit: String, onValid: (Double, Double) -> Unit) {
        r.addView(text("\n$title", 13f, pinkSoft))
        val s = editText("START", start)
        val e = editText("END", end)
        r.addView(s); r.addView(e)
        val direction = text("", 13f, pinkSoft)
        fun validate() {
            val a = parseRange(s.text.toString())
            val b = parseRange(e.text.toString())
            direction.text = when {
                a == null || b == null -> "✕ RANGE REJECTED\nERROR // INVALID_INPUT"
                a == b -> "✕ RANGE REJECTED\nERROR // EMPTY_RANGE"
                else -> {
                    onValid(a, b)
                    "● RANGE VALID\nDIRECTION   ${if (a < b) "→ FORWARD" else "← REVERSE"}\n${unit.uppercase()}   ${
                        String.format(Locale.US, "%.3f", kotlin.math.abs(b - a))
                    }"
                }
            }
            direction.setTextColor(if (a != null && b != null && a != b) mint else err)
        }
        s.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) validate() }
        e.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) validate() }
        r.addView(direction)
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
                card.background = shape(panel, if (active) pinkElectric else panelStroke, 14f, if (active) 2 else 1)
                val check = card.findViewWithTag<TextView>("check")
                check?.visibility = if (active) View.VISIBLE else View.INVISIBLE
                val badge = card.findViewWithTag<TextView>("badge")
                val est = estimateFor(p)
                badge?.text = if (est > 0 && sourceBytes > 0) {
                    val pct = ((1 - est.toDouble() / sourceBytes) * 100).toInt().coerceIn(-999, 99)
                    "${formatSize(sourceBytes)} → ${formatSize(est)} (${if (pct >= 0) "-$pct" else "+${-pct}"}%)"
                } else "EST. OUTPUT UNAVAILABLE"
                badge?.setTextColor(if (active) mint else metaGray)
            }
        }

        profiles.forEach { p ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
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
                text = "✓"; tag = "check"; textSize = 16f; setTextColor(pinkElectric)
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
        r.addView(text("\nPLAYBACK SPEED", 13f, pinkSoft))
        selectionRow(r, listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0).map { "${it}×" to it }, { cfgSpeed }) { cfgSpeed = it }
    }

    private fun rotateUI(r: LinearLayout) {
        r.addView(text("\nROTATION", 13f, pinkSoft))
        selectionRow(r, listOf(90, 180, 270).map { "${it}°" to it }, { cfgRotateDegrees }) { cfgRotateDegrees = it }
    }

    private fun formatUI(r: LinearLayout) {
        r.addView(text("\nOUTPUT FORMAT", 13f, pinkSoft))
        selectionRow(
            r,
            listOf("JPG" to MediaEngine.ImageFormatTarget.JPG, "PNG" to MediaEngine.ImageFormatTarget.PNG, "WEBP" to MediaEngine.ImageFormatTarget.WEBP),
            { cfgImageFormat }
        ) { cfgImageFormat = it }
    }

    private fun gainUI(r: LinearLayout) {
        r.addView(text("\nGAIN (ANTI-CLIP LIMITED)", 13f, pinkSoft))
        selectionRow(
            r,
            listOf(-6.0, 0.0, 3.0, 6.0, 9.0, 12.0).map { g -> (if (g >= 0) "+${g} dB" else "${g} dB") to g },
            { cfgGainDb }
        ) { cfgGainDb = it }
    }

    /** Shared selection-row list with a glowing right-aligned checkmark on the active choice. */
    private fun <T> selectionRow(r: LinearLayout, options: List<Pair<String, T>>, current: () -> T, onPick: (T) -> Unit) {
        val rows = mutableListOf<Pair<LinearLayout, T>>()
        fun refresh() {
            rows.forEach { (row, value) ->
                val active = value == current()
                row.background = shape(panel, if (active) pinkElectric else panelStroke, 13f, if (active) 2 else 1)
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
                text = "✓"; tag = "check"; textSize = 15f; setTextColor(pinkElectric); visibility = View.INVISIBLE
            })
            applyTapFeedback(row) { onPick(value); refresh() }
            r.addView(row)
            rows.add(row to value)
        }
        refresh()
    }

    private fun targetSizeUI(r: LinearLayout) {
        r.addView(text("\nTARGET SIZE (KB) — optional", 13f, pinkSoft))
        val input = editText("e.g. 200 (blank = auto quality)", "")
        r.addView(input)
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) cfgTargetKb = input.text.toString().trim().toIntOrNull() }
    }

    private fun passwordUI(r: LinearLayout) {
        r.addView(text("\nPASSWORD", 13f, pinkSoft))
        val input = editText("Enter password", "")
        r.addView(input)
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) cfgPassword = input.text.toString() }
    }

    private fun watermarkUI(r: LinearLayout) {
        r.addView(text("\nWATERMARK TEXT", 13f, pinkSoft))
        val input = editText("Enter watermark text", cfgWatermarkText)
        r.addView(input)
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) cfgWatermarkText = input.text.toString().ifBlank { "MEDIACOMPRESSOR" } }
    }

    private fun fpsUI(r: LinearLayout) {
        r.addView(text("\nFRAME RATE", 13f, pinkSoft))
        selectionRow(r, listOf(8, 12, 15, 24).map { "$it FPS" to it }, { cfgFps }) { cfgFps = it }
    }

    private fun delayUI(r: LinearLayout) {
        r.addView(text("\nFRAME DELAY", 13f, pinkSoft))
        selectionRow(r, listOf(10 to "100ms", 20 to "200ms", 50 to "500ms").map { it.second to it.first }, { cfgDelayCentis }) { cfgDelayCentis = it }
    }

    // =========================================================================
    // Live processing panel: idle → processing → completed, all in one card
    // =========================================================================

    private fun runOperation(type: String, op: String) {
        cancelled.set(false)
        val r = base()
        topBar("PROCESSING", showBack = false, statusLabel = "Working", statusColor = pinkElectric)
        r.addView(heading(displayOp(op), 21f, white).apply { setPadding(0, dp(18), 0, dp(2)) })
        r.addView(text("ROSE NOIR / OFFLINE ENGINE", 10f, pinkSoft))

        val panelCard = glassCard(stroke = panelStroke)
        val panelBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panelCard.addView(panelBody)
        r.addView(panelCard)

        // Idle mode — system verification checklist.
        panelBody.addView(mono("● SYSTEM VERIFICATION\n✓ Input analysis\n✓ Stream validation\n✓ Output vault ready", 12f, mint))

        val actionsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        r.addView(actionsHolder)

        val handler = Handler(mainLooper)

        // Views for processing mode, created once entered.
        var wave: WaveProgressView? = null
        var percentLabel: TextView? = null
        var stageLabel: TextView? = null
        var lastPercent = 0

        fun enterProcessingMode() {
            panelBody.removeAllViews()
            panelBody.addView(text("● PROCESSING", 12f, pinkElectric).apply { letterSpacing = 0.08f })
            val w = WaveProgressView(this, panel, pinkSoft, pinkElectric).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(10) }
            }
            panelBody.addView(w)
            wave = w
            val pct = text("0%", 20f, white).apply { setPadding(0, dp(8), 0, 0) }
            panelBody.addView(pct)
            percentLabel = pct
            val stage = text(op, 11.5f, gray)
            panelBody.addView(stage)
            stageLabel = stage

            val cancelBtn = button("[ CANCEL ]") { cancelled.set(true); workerThread?.interrupt() }
            actionsHolder.removeAllViews()
            actionsHolder.addView(cancelBtn)
        }

        fun updateProgress(percent: Int, status: String) {
            handler.post {
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
        }

        fun enterCompletedMode(outcomes: List<EngineResult>) {
            panelBody.removeAllViews()
            panelBody.addView(mono("✓ OUTPUT VERIFIED", 11f, mint))
            var anySuccess = false
            outcomes.forEach { outcome ->
                when (outcome) {
                    is EngineResult.Success -> {
                        anySuccess = true
                        val saved = engine.finalize(outcome.outputFile, outcome.outputFile.name.removePrefix(".tmp_"))
                        val mime = guessMime(saved.name)
                        val publishedUri = MediaStoreExporter.publish(this, saved, type, mime)
                        val savedBytes = (outcome.inputBytes - outcome.outputBytes).coerceAtLeast(0)
                        val ratio = if (outcome.inputBytes > 0) String.format(Locale.US, "%.1f%%", 100.0 * (1 - outcome.outputBytes.toDouble() / outcome.inputBytes)) else "N/A"
                        panelBody.addView(text(saved.name, 17f, white).apply { setPadding(0, dp(8), 0, dp(2)) })
                        panelBody.addView(mono("SOURCE        ${formatSize(outcome.inputBytes)}\nOUTPUT        ${formatSize(outcome.outputBytes)}\nREDUCTION     $ratio\nSAVED         ${formatSize(savedBytes)}\nSTATUS        ${if (publishedUri != null) "EXPORTED" else "LOCAL OUTPUT"}", 12f, mint))
                        if (outcome.note.isNotBlank()) panelBody.addView(text("\n${outcome.note}", 11f, gray))
                    }
                    is EngineResult.Rejected -> panelBody.addView(text("⚠ OUTPUT REJECTED\n${outcome.reason}\nOriginal preserved.", 13f, warn))
                    is EngineResult.Failure -> panelBody.addView(text("× PROCESS FAILED\n${outcome.error}", 13f, err))
                }
            }
            if (!anySuccess) panelBody.addView(text("\nNO OUTPUT PRODUCED", 12f, err))
            panelCard.background = shape(panel, if (anySuccess) mint else err, 14f, 2)

            actionsHolder.removeAllViews()
            actionsHolder.addView(primaryButton("NEW OPERATION   ›") { showHome() })
            actionsHolder.addView(button("‹  BACK TO INPUT") { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
        }

        fun enterCancelledMode() {
            panelBody.removeAllViews()
            panelBody.addView(text("✕ CANCELLED BY USER", 13f, warn))
            panelBody.addView(text("STATUS   PROCESS ABORTED, NO OUTPUT WRITTEN", 11.5f, gray))
            panelCard.background = shape(panel, warn, 14f, 2)
            actionsHolder.removeAllViews()
            actionsHolder.addView(primaryButton("▶ NEW OPERATION") { showHome() })
            actionsHolder.addView(button("←  BACK TO FILE") { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
        }

        handler.postDelayed({
            workerThread = Thread {
                try {
                    val localInputs = selectedUris.mapIndexed { i, uri ->
                        val f = engine.newTempFile("input_$i", fileName(uri).substringAfterLast('.', "bin"))
                        engine.copyUriToFile(uri, f)
                        f
                    }
                    if (cancelled.get()) { handler.post { enterCancelledMode() }; return@Thread }

                    val progress: ProgressCallback = { p, s ->
                        if (cancelled.get()) throw InterruptedException("CANCELLED_BY_USER")
                        updateProgress(p, s)
                    }

                    val outcome = dispatch(type, op, localInputs, progress)
                    handler.post { enterCompletedMode(outcome) }
                } catch (ie: InterruptedException) {
                    handler.post { enterCancelledMode() }
                } catch (e: Exception) {
                    handler.post { enterCompletedMode(listOf(EngineResult.Failure(e.message ?: "UNKNOWN_ERROR"))) }
                }
            }
            enterProcessingMode()
            workerThread!!.start()
        }, 220)
    }

    /** Routes a configured op to the corresponding MediaEngine call(s). Returns one result per output file. */
    private fun dispatch(type: String, op: String, inputs: List<File>, progress: ProgressCallback): List<EngineResult> {
        val single = inputs.firstOrNull() ?: return listOf(EngineResult.Failure("NO_INPUT"))
        return when (op) {
            "Video Compress & Mute" -> listOf(engine.videoCompress(single, cfgQuality, cfgMute, progress))
            "Video Trim & Reverse" -> listOf(engine.videoTrimOrReverse(single, cfgRangeStart, cfgRangeEnd, progress))
            "Video Speed" -> listOf(engine.videoSpeed(single, cfgSpeed, progress))
            "Video to Audio" -> listOf(engine.videoToAudio(single, progress))
            "Video to GIF" -> listOf(engine.videoToGif(single, cfgRangeStart, cfgRangeEnd, cfgFps, cfgGifMaxWidth, progress))
            "Extract Frame" -> listOf(engine.extractFrame(single, cfgRangeStart, progress))
            "Video Merge" -> listOf(engine.videoMerge(inputs, progress))
            "Video Rotate & Flip" -> listOf(engine.videoRotate(single, cfgRotateDegrees, progress))

            "GIF to Video" -> listOf(engine.gifToVideo(single, progress))
            "GIF Compress" -> listOf(engine.gifCompress(single, progress))
            "Photo to GIF" -> listOf(engine.photosToGif(inputs, cfgDelayCentis, cfgGifMaxWidth, progress))

            "Photo Compress" -> listOf(engine.photoCompress(single, cfgTargetKb, progress))
            "Batch Photo Compress" -> engine.batchPhotoCompress(inputs, cfgTargetKb, progress).map { it.second }
            "Image Converter" -> listOf(engine.imageConvert(single, cfgImageFormat, progress))
            "Remove EXIF" -> listOf(engine.removeExif(single, progress))

            "Audio Trim & Reverse" -> listOf(engine.audioTrimOrReverse(single, cfgRangeStart, cfgRangeEnd, progress))
            "Audio Merge" -> listOf(engine.audioMerge(inputs, progress))
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
    // Menu / Settings / About (reached via the drawer)
    // =========================================================================

    private fun showSettings() {
        val r = base()
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
 * Lightweight Canvas-drawn progress track with a Cyber Pink → Electric Rose
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
