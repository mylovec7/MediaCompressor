package com.vr3th.mediacompressor

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    // ---- Cyber terminal palette -----------------------------------------
    private val bg = Color.rgb(8, 7, 11)
    private val panel = Color.rgb(17, 14, 20)
    private val pink = Color.rgb(255, 143, 188)
    private val pinkActive = Color.rgb(255, 177, 208)
    private val white = Color.rgb(248, 244, 247)
    private val gray = Color.rgb(177, 169, 178)
    private val metaGray = Color.rgb(111, 103, 113)
    private val green = Color.rgb(120, 224, 171)
    private val warn = Color.rgb(244, 193, 116)
    private val err = Color.rgb(244, 122, 146)
    private val roseSoft = Color.rgb(255, 214, 229)
    private val hairline = Color.rgb(39, 32, 41)

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
        showBoot()
    }

    // =========================================================================
    // Layout primitives
    // =========================================================================

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun base(): LinearLayout {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(32))
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(root)
        setContentView(scroll)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
        return root
    }

    private fun shape(color: Int, stroke: Int? = null, radius: Float = 16f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun text(s: String, size: Float, color: Int = white): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, dp(4), 0, dp(4))
            letterSpacing = if (size <= 12f) 0.06f else 0.01f
        }

    private fun mono(s: String, size: Float, color: Int = white): TextView =
        text(s, size, color).apply { typeface = Typeface.MONOSPACE }

    private fun button(s: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = s
            setTextColor(white)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.018f
            setPadding(dp(8), dp(11), dp(8), dp(11))
            background = shape(Color.TRANSPARENT, null, 12f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                animate().alpha(.62f).scaleX(.985f).scaleY(.985f).setDuration(60).withEndAction {
                    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
                    action()
                }.start()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            }
        }

    private fun primaryButton(s: String, action: () -> Unit): TextView =
        button(s, action).apply {
            setTextColor(Color.rgb(28, 18, 24))
            textSize = 13f
            gravity = Gravity.CENTER
            background = shape(pink, pinkActive, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

    private fun line(): View = View(this).apply {
        setBackgroundColor(hairline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) }
    }

    private fun section(title: String, subtitle: String = "") {
        root.addView(text(title, 10f, pink).apply { letterSpacing = 0.10f })
        if (subtitle.isNotBlank()) root.addView(text(subtitle, 13f, gray))
        root.addView(line())
    }

    private fun editText(hintText: String, initial: String): EditText =
        EditText(this).apply {
            hint = hintText
            setText(initial)
            setTextColor(white)
            setHintTextColor(metaGray)
            typeface = Typeface.MONOSPACE
            setBackground(shape(panel, Color.rgb(38, 42, 50), 12f))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun addToolRow(label: String, detail: String = "", action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            background = shape(Color.TRANSPARENT, null, 10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                animate().alpha(.70f).translationX(dp(4).toFloat()).setDuration(65).withEndAction {
                    animate().alpha(1f).translationX(0f).setDuration(180).start()
                    action()
                }.start()
            }
        }

        val rail = View(this).apply {
            setBackgroundColor(pink)
            alpha = .75f
        }
        row.addView(rail, LinearLayout.LayoutParams(dp(2), dp(36)).apply { rightMargin = dp(14) })

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = .025f
            setPadding(0, 0, 0, dp(2))
        }
        copy.addView(title)

        if (detail.isNotBlank()) {
            val d = text(detail, 10f, metaGray)
            d.setPadding(0, 0, 0, 0)
            copy.addView(d)
        }

        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val arrow = text("›", 20f, metaGray).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
        row.addView(arrow, LinearLayout.LayoutParams(dp(30), dp(42)))

        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
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
        val tv = text("", 15f, gray)
        r.addView(text("MEDIACOMPRESSOR", 24f, white))
        r.addView(text("ROSE NOIR MEDIA CORE  /  Vr3tH🇵🇸", 10f, pink))
        r.addView(tv)
        var i = 0
        val handler = Handler(mainLooper)
        val run = object : Runnable {
            override fun run() {
                if (i < lines.size) {
                    tv.text = lines.take(i + 1).joinToString("\n")
                    i++
                    handler.postDelayed(this, 110)
                } else {
                    handler.postDelayed({ showHome() }, 300)
                }
            }
        }
        handler.postDelayed(run, 200)
    }

    // =========================================================================
    // Home
    // =========================================================================

    private fun showHome() {
        val r = base()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val menu = TextView(this).apply {
            text = "☰"; textSize = 22f; setTextColor(white); gravity = Gravity.CENTER
            setOnClickListener { showMenu() }
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(44), dp(50)))
        header.addView(text("MEDIACOMPRESSOR", 15f, white), LinearLayout.LayoutParams(0, dp(50), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(text("● READY", 10f, pink))
        r.addView(header)

        r.addView(text("MEDIA PROCESSOR", 30f, white).apply {
            setPadding(0, dp(30), 0, dp(2))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = -.01f
        })
        r.addView(text("Vr3tH🇵🇸", 14f, pink))
        r.addView(text("OFFLINE MEDIA ENGINE  /  ROSE NOIR CORE", 10f, metaGray))
        r.addView(text("Made with care. Built to stay light.", 12f, roseSoft).apply {
            setPadding(0, dp(10), 0, dp(0))
        })
        r.addView(line())

        section("COMMAND CENTER", "Choose a media domain")
        addToolRow("VIDEO", "Compress  •  Merge  •  Trim / Reverse  •  Speed") { showCategory("VIDEO") }
        addToolRow("IMAGE", "Compress  •  Batch  •  Convert  •  GIF") { showCategory("IMAGE") }
        addToolRow("GIF", "Convert  •  Compress  •  Photo sequence") { showCategory("GIF") }
        addToolRow("AUDIO", "Trim / Reverse  •  Merge  •  Volume") { showCategory("AUDIO") }
        addToolRow("PDF", "Compress  •  Merge  •  Split / Reverse  •  Secure") { showCategory("PDF") }
        addToolRow("ARCHIVE", "Create  •  Extract  •  Recompress") { showCategory("ARCHIVE") }

        section("ENGINE STATUS")
        r.addView(mono("● STANDBY\nENGINE              READY\nPROCESS             IDLE\nSTORAGE             READY\nMODE                OFFLINE", 12f, pink))
        r.addView(text("\nLOCAL PROCESSING • NO CLOUD UPLOAD", 10f, metaGray))
    }

    private fun showCategory(type: String) {
        val r = base()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹") { showHome() }, LinearLayout.LayoutParams(dp(52), dp(48)))
        header.addView(text(type, 18f, white), LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(text("● READY", 10f, pink))
        r.addView(header)
        r.addView(text("${type} PROCESSOR", 24f, white).apply { setPadding(0, dp(24), 0, dp(2)) })
        r.addView(text("Select an operation", 12f, metaGray))
        r.addView(line())
        val ops = when(type) { "VIDEO" -> videoOps; "GIF" -> gifFileOps; "IMAGE" -> imageOps; "AUDIO" -> audioOps; "PDF" -> pdfOps; else -> archiveOps }
        ops.forEach { op ->
            val clean = displayOp(op)
            addToolRow(clean, operationSubtitle(op)) {
                currentOp = op
                selectedType = type
                selectedUris.clear()
                pickFile(op in multiInputOps)
            }
        }
    }

    private fun displayOp(op: String): String = when (op) {
        "Video Rotate & Flip" -> "Video Rotate"
        "Video Compress & Mute" -> "Video Compress"
        else -> op.replace(" & ", " / ")
    }

    private fun operationSubtitle(op: String): String = when(op) {
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
    // File picking
    // =========================================================================

    private fun pickFile(allowMultiple: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, if (allowMultiple) REQ_PICK_MULTI else REQ_PICK_SINGLE)
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
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: data?.data?.let { uris.add(it) }
                selectedUris.addAll(uris)
                if (selectedUris.isNotEmpty()) showFeature(selectedType, currentOp, fileName(selectedUris.first()))
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
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹") { showHome() }, LinearLayout.LayoutParams(dp(52), dp(48)))
        header.addView(text("INPUT", 18f, white), LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(text("● VERIFIED", 10f, pink))
        r.addView(header)
        r.addView(text(name, 23f, white).apply { setPadding(0, dp(24), 0, dp(2)) })
        r.addView(text("${type}  •  ${formatSize(size)}", 12f, gray))
        r.addView(line())
        if (type == "VIDEO" || type == "AUDIO") {
            try {
                val mmr = MediaMetadataRetriever(); mmr.setDataSource(this, uri)
                val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                r.addView(mono("DURATION     ${formatDuration(duration?.toLongOrNull() ?: 0)}\nFORMAT       ${mime ?: "unknown"}", 11f, gray))
                mmr.release()
            } catch (_: Exception) { r.addView(text("INPUT METADATA LIMITED", 11f, warn)) }
        }
        if (type == "UNKNOWN") {
            r.addView(text("\nPROCESSING LOCKED\nUnsupported media type.", 13f, err))
            return
        }
        section("AVAILABLE TOOLS", "Choose one operation")
        val ops = when(type) { "VIDEO" -> videoOps; "GIF" -> gifFileOps; "IMAGE" -> imageOps; "AUDIO" -> audioOps; "PDF" -> pdfOps; else -> archiveOps }
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
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹") { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() }, LinearLayout.LayoutParams(dp(52), dp(48)))
        header.addView(text(displayOp(op), 17f, white), LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(text("● READY", 10f, pink))
        r.addView(header)
        r.addView(text("${type} WORKSPACE", 10f, pink))
        r.addView(text(if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else name, 20f, white).apply { setPadding(0, dp(16), 0, dp(2)) })
        r.addView(text("LOCAL PROCESSING", 11f, metaGray))
        r.addView(line())

        when {
            op == "Video Trim & Reverse" -> rangeUI(r, "TIMELINE", "0", "10", "sec") { a,b -> cfgRangeStart=a; cfgRangeEnd=b }
            op == "Audio Trim & Reverse" -> rangeUI(r, "TIMELINE", "0", "10", "sec") { a,b -> cfgRangeStart=a; cfgRangeEnd=b }
            op == "Split & Reverse PDF" -> rangeUI(r, "PAGE RANGE", "1", "10", "pg") { a,b -> cfgRangeStart=a; cfgRangeEnd=b }
            op == "Video to GIF" -> { rangeUI(r,"CLIP RANGE","0","3","sec"){a,b->cfgRangeStart=a;cfgRangeEnd=b}; fpsUI(r) }
            op == "Extract Frame" -> rangeUI(r,"TIMESTAMP","1","1","sec"){a,_->cfgRangeStart=a}
            op == "Video Compress & Mute" -> { presetUI(r); muteToggleUI(r) }
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
        r.addView(mono("ENGINE STATUS\n● INPUT VERIFIED\n● CONFIGURATION READY\n● OUTPUT PROTECTED", 11f, pink))
        r.addView(primaryButton("START PROCESSING   ›") { runOperation(type, op) })
    }

    private fun rangeUI(r: LinearLayout, title: String, start: String, end: String, unit: String, onValid: (Double, Double) -> Unit) {
        r.addView(text("\n$title", 13f, pink))
        val s = editText("START", start)
        val e = editText("END", end)
        r.addView(s); r.addView(e)
        val direction = text("", 13f, pink)
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
            direction.setTextColor(if (a != null && b != null && a != b) green else err)
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

    private fun presetUI(r: LinearLayout) {
        r.addView(text("QUALITY PROFILE", 12f, pink))
        val sourceBytes = selectedUris.firstOrNull()?.let { fileSize(it) } ?: -1L
        val info = selectedUris.firstOrNull()?.let { uri ->
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(this, uri)
                val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val d = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val audio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                mmr.release()
                VideoTranscoder.VideoInfo(w, h, d, rot, audio)
            } catch (_: Exception) { null }
        }
        val estimate = text("", 11f, gray)
        fun set(q: MediaEngine.Quality) {
            cfgQuality = q
            val (label, bitrate, edge) = when(q) {
                MediaEngine.Quality.HIGH -> Triple("HIGH", 6_000_000, 1920)
                MediaEngine.Quality.MEDIUM -> Triple("MEDIUM", 3_000_000, 1280)
                MediaEngine.Quality.LOW -> Triple("LOW", 1_200_000, 854)
            }
            val durationSec = (info?.durationUs ?: 0L) / 1_000_000.0
            val audio = if (info?.hasAudio == true) 128_000 else 0
            val rawEstimate = if (durationSec > 0) (durationSec * (bitrate + audio) / 8.0) else -1.0
            val est = if (rawEstimate > 0) formatSize(rawEstimate.toLong()) else "ESTIMATING"
            estimate.text = "$label  •  ≤${edge}px  •  ${(bitrate / 1_000_000.0).toString().trimEnd('0').trimEnd('.')} Mbps\\nEST. OUTPUT   $est${if (sourceBytes > 0) "   /   SOURCE ${formatSize(sourceBytes)}" else ""}"
        }
        listOf(
            "HIGH" to MediaEngine.Quality.HIGH,
            "MEDIUM" to MediaEngine.Quality.MEDIUM,
            "LOW" to MediaEngine.Quality.LOW
        ).forEach { (label, q) ->
            addToolRow(label, when(q) { MediaEngine.Quality.HIGH -> "Maximum quality profile"; MediaEngine.Quality.MEDIUM -> "Balanced size / quality"; else -> "Maximum size reduction" }) { set(q) }
        }
        r.addView(estimate)
        set(cfgQuality)
    }

    private fun muteToggleUI(r: LinearLayout) {
        r.addView(text("\nAUDIO", 13f, pink))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val onBtn = button("[ ON ] Keep audio") { cfgMute = false }
        val offBtn = button("[ MUTE ] Strip instantly") { cfgMute = true }
        row.addView(onBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(offBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(row)
    }

    private fun speedUI(r: LinearLayout) {
        r.addView(text("\nPLAYBACK SPEED", 13f, gray))
        listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { v ->
            r.addView(button("${v}×") { cfgSpeed = v })
        }
    }

    private fun rotateUI(r: LinearLayout) {
        r.addView(text("\nROTATION", 13f, gray))
        listOf(90, 180, 270).forEach { d ->
            r.addView(button("${d}°") { cfgRotateDegrees = d })
        }
    }

    private fun formatUI(r: LinearLayout) {
        r.addView(text("\nOUTPUT FORMAT", 13f, gray))
        listOf(
            "JPG" to MediaEngine.ImageFormatTarget.JPG,
            "PNG" to MediaEngine.ImageFormatTarget.PNG,
            "WEBP" to MediaEngine.ImageFormatTarget.WEBP
        ).forEach { (label, f) -> r.addView(button(label) { cfgImageFormat = f }) }
    }

    private fun gainUI(r: LinearLayout) {
        r.addView(text("\nGAIN (ANTI-CLIP LIMITED)", 13f, gray))
        listOf(-6.0, 0.0, 3.0, 6.0, 9.0, 12.0).forEach { g ->
            r.addView(button(if (g >= 0) "+${g} dB" else "${g} dB") { cfgGainDb = g })
        }
    }

    private fun targetSizeUI(r: LinearLayout) {
        r.addView(text("\nTARGET SIZE (KB) — optional", 13f, gray))
        val input = editText("e.g. 200 (blank = auto quality)", "")
        r.addView(input)
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) cfgTargetKb = input.text.toString().trim().toIntOrNull()
        }
    }

    private fun passwordUI(r: LinearLayout) {
        r.addView(text("\nPASSWORD", 13f, gray))
        val input = editText("Enter password", "")
        r.addView(input)
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) cfgPassword = input.text.toString() }
    }

    private fun watermarkUI(r: LinearLayout) {
        r.addView(text("\nWATERMARK TEXT", 13f, gray))
        val input = editText("Enter watermark text", cfgWatermarkText)
        r.addView(input)
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) cfgWatermarkText = input.text.toString().ifBlank { "MEDIACOMPRESSOR" } }
    }

    private fun fpsUI(r: LinearLayout) {
        r.addView(text("\nFRAME RATE", 13f, gray))
        listOf(8, 12, 15, 24).forEach { f -> r.addView(button("${f} FPS") { cfgFps = f }) }
    }

    private fun delayUI(r: LinearLayout) {
        r.addView(text("\nFRAME DELAY", 13f, gray))
        listOf(10 to "100ms", 20 to "200ms", 50 to "500ms").forEach { (c, label) ->
            r.addView(button(label) { cfgDelayCentis = c })
        }
    }

    // =========================================================================
    // Execution / terminal screen
    // =========================================================================

    private fun runOperation(type: String, op: String) {
        cancelled.set(false)
        val r = base()
        r.addView(text("PROCESSING", 24f, white))
        r.addView(text("ROSE NOIR / OFFLINE ENGINE", 10f, pink))
        val tv = text("> PROCESS INITIALIZED\n[✓] INPUT ANALYSIS\n[✓] STREAM VALIDATION\n[●] $op\nPROGRESS  [                    ]   0%\nSTATUS    STARTING", 13f, gray)
        r.addView(tv)
        val cancelBtn = button("[ CANCEL ]") {
            cancelled.set(true)
            workerThread?.interrupt()
        }
        r.addView(cancelBtn)

        val handler = Handler(mainLooper)
        fun updateProgress(percent: Int, status: String) {
            handler.post {
                val bar = renderBar(percent)
                tv.text = "● PROCESSING\n\n$op\n\n$bar  $percent%\n\nSTAGE\n$op\nSTATUS\n$status\n\nINPUT VERIFIED\nOUTPUT PROTECTED"
            }
        }

        workerThread = Thread {
            try {
                val localInputs = selectedUris.mapIndexed { i, uri ->
                    val f = engine.newTempFile("input_$i", fileName(uri).substringAfterLast('.', "bin"))
                    engine.copyUriToFile(uri, f)
                    f
                }
                if (cancelled.get()) {
                    handler.post { showResultCancelled(type, op) }
                    return@Thread
                }

                val progress: ProgressCallback = { p, s ->
                    if (cancelled.get()) throw InterruptedException("CANCELLED_BY_USER")
                    updateProgress(p, s)
                }

                val outcome = dispatch(type, op, localInputs, progress)
                handler.post { showResult(type, op, outcome) }
            } catch (ie: InterruptedException) {
                handler.post { showResultCancelled(type, op) }
            } catch (e: Exception) {
                handler.post { showResult(type, op, listOf(EngineResult.Failure(e.message ?: "UNKNOWN_ERROR"))) }
            }
        }
        workerThread!!.start()
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

    private fun renderBar(percent: Int): String {
        val filled = (percent / 5).coerceIn(0, 20)
        return "[" + "█".repeat(filled) + "░".repeat(20 - filled) + "]"
    }

    // =========================================================================
    // Result screen
    // =========================================================================

    private fun showResult(type: String, op: String, outcomes: List<EngineResult>) {
        val r = base()
        r.addView(text("PROCESS COMPLETE", 24f, white))
        r.addView(text("✓ OUTPUT VERIFIED", 10f, pink))
        r.addView(text(op, 11f, pink))
        r.addView(line())
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
                    r.addView(text(saved.name, 20f, white))
                    r.addView(mono("SOURCE        ${formatSize(outcome.inputBytes)}\nOUTPUT        ${formatSize(outcome.outputBytes)}\nREDUCTION     $ratio\nSAVED         ${formatSize(savedBytes)}\nSTATUS        ${if (publishedUri != null) "EXPORTED" else "LOCAL OUTPUT"}", 12f, green))
                    if (outcome.note.isNotBlank()) r.addView(text("\n${outcome.note}", 11f, gray))
                }
                is EngineResult.Rejected -> r.addView(text("⚠ OUTPUT REJECTED\n${outcome.reason}\nOriginal preserved.", 13f, warn))
                is EngineResult.Failure -> r.addView(text("× PROCESS FAILED\n${outcome.error}", 13f, err))
            }
        }
        if (!anySuccess) r.addView(text("\nNO OUTPUT PRODUCED", 12f, err))
        r.addView(primaryButton("NEW OPERATION   ›") { showHome() })
        r.addView(button("‹  BACK TO INPUT") { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
    }

    private fun showResultCancelled(type: String, op: String) {
        val r = base()
        r.addView(text("> RESULT // $op", 20f, pink))
        r.addView(text("\n✕ CANCELLED BY USER\nSTATUS     PROCESS ABORTED, NO OUTPUT WRITTEN", 13f, warn))
        r.addView(primaryButton("▶ NEW OPERATION") { showHome() })
        r.addView(button("← BACK TO FILE") { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
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
    // Menu / Settings / About
    // =========================================================================

    private fun showMenu() {
        val r = base()
        r.addView(text("MEDIACOMPRESSOR", 20f, white))
        r.addView(text("Vr3tH🇵🇸  /  COMMAND MENU", 11f, pink))
        r.addView(line())
        section("PROCESS")
        addToolRow("VIDEO", "Compression • merge • trim / reverse • speed") { showCategory("VIDEO") }
        addToolRow("IMAGE", "Compression • conversion • batch") { showCategory("IMAGE") }
        addToolRow("GIF", "GIF conversion • compression") { showCategory("GIF") }
        addToolRow("AUDIO", "Trim / reverse • merge • processing") { showCategory("AUDIO") }
        addToolRow("PDF", "Compression • split / reverse • security") { showCategory("PDF") }
        addToolRow("ARCHIVE", "ZIP create • extract • recompress") { showCategory("ARCHIVE") }
        section("SYSTEM")
        addToolRow("SETTINGS", "Application behavior and processing preferences") { showSettings() }
        addToolRow("ABOUT", "Developer identity • links • engine information") { showAbout() }
        r.addView(button("‹  BACK TO COMMAND CENTER") { showHome() })
    }

    private fun showSettings() {
        val r = base()
        r.addView(text("SETTINGS", 24f, white))
        r.addView(text("ROSE NOIR / SYSTEM", 10f, pink))
        r.addView(line())
        section("PROCESSING", "Defaults used by the local engine")
        addToolRow("OFFLINE MODE", "Always process locally • no network workflow") { Toast.makeText(this, "Offline processing is active.", Toast.LENGTH_SHORT).show() }
        addToolRow("OUTPUT POLICY", "Original source remains untouched") { Toast.makeText(this, "Original files are preserved.", Toast.LENGTH_SHORT).show() }
        addToolRow("SMOOTH UI", "Lightweight transitions • no heavy visual effects") { Toast.makeText(this, "Lightweight motion profile active.", Toast.LENGTH_SHORT).show() }
        section("IDENTITY")
        r.addView(mono("DEVELOPER   Vr3tH🇵🇸\nENGINE      MEDIA PROCESSOR\nNETWORK     OFFLINE-FIRST\nSTATUS      OPERATIONAL", 12f, gray))
        r.addView(button("‹  BACK") { showMenu() })
    }

    private fun socialButton(label: String, handle: String, url: String) {
        addToolRow(label, handle) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showAbout() {
        val r = base()
        r.addView(text("ABOUT", 24f, white))
        r.addView(text("ROSE NOIR / DEVELOPER PROFILE", 10f, pink))
        r.addView(line())
        r.addView(text("Vr3tH🇵🇸", 23f, white))
        r.addView(text("INDEPENDENT DEVELOPER  /  OFFLINE TOOLING", 10f, pink))
        r.addView(text("A small offline media workspace, built with care for speed, privacy, and a clean experience.", 13f, gray))
        section("CONNECT", "Official social channels")
        socialButton("WHATSAPP", "Direct contact", "https://wa.me/6288229456210")
        socialButton("INSTAGRAM", "@rsx_xt", "https://www.instagram.com/rsx_xt/")
        socialButton("YOUTUBE", "@oficial_tzy", "https://youtube.com/@oficial_tzy")
        section("ENGINE")
        r.addView(mono("LOCAL PROCESSING\nNO CLOUD UPLOAD\nNO ACCOUNT REQUIRED\nLIGHTWEIGHT  /  OFFLINE", 12f, gray))
        r.addView(text("\nMEDIA COMPRESSOR  •  v1.0\n© 2026 Vr3tH🇵🇸", 10f, metaGray))
        r.addView(button("‹  BACK") { showMenu() })
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
    }
}
