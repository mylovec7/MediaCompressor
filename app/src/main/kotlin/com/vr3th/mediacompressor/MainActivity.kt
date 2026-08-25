package com.vr3th.mediacompressor

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
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
    private val bg = Color.rgb(7, 9, 12)
    private val panel = Color.rgb(12, 15, 20)
    private val pink = Color.rgb(255, 45, 166)
    private val pinkActive = Color.rgb(255, 92, 190)
    private val white = Color.rgb(241, 245, 249)
    private val gray = Color.rgb(167, 175, 186)
    private val metaGray = Color.rgb(111, 119, 130)
    private val green = Color.rgb(53, 229, 140)
    private val warn = Color.rgb(255, 184, 77)
    private val err = Color.rgb(255, 69, 103)

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
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(root)
        setContentView(scroll)
        return root
    }

    private fun text(s: String, size: Float, color: Int = white): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun button(s: String, action: () -> Unit): Button =
        Button(this).apply {
            text = s
            setTextColor(white)
            setBackgroundColor(Color.rgb(26, 14, 22))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isAllCaps = false
            typeface = Typeface.MONOSPACE
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }

    private fun primaryButton(s: String, action: () -> Unit): Button =
        button(s, action).apply {
            setTextColor(Color.BLACK)
            setBackgroundColor(pink)
        }

    private fun editText(hintText: String, initial: String): EditText =
        EditText(this).apply {
            hint = hintText
            setText(initial)
            setTextColor(white)
            setHintTextColor(metaGray)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(panel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

    // =========================================================================
    // Boot sequence
    // =========================================================================

    private fun showBoot() {
        val r = base()
        val lines = listOf(
            "> INITIALIZING MEDIA ENGINE...",
            "> LOADING PROCESSORS...",
            "[ VIDEO ]   ........ ONLINE",
            "[ AUDIO ]   ........ ONLINE",
            "[ IMAGE ]   ........ ONLINE",
            "[ PDF ]     ........ ONLINE",
            "[ ARCHIVE ] ........ ONLINE",
            "",
            "> _ SYSTEM READY"
        )
        val tv = text("", 15f, gray)
        r.addView(text("> MEDIACOMPRESSOR // CORE <", 20f, pink))
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
        header.addView(button("☰") { showMenu() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(text("MEDIACOMPRESSOR", 16f, white), LinearLayout.LayoutParams(0, dp(56), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(text("● READY", 12f, green))
        r.addView(header)

        r.addView(text("\n> MEDIA PROCESSOR", 24f, white))
        r.addView(text("Vr3tH", 15f, pink))
        r.addView(text("\nBLACK // MAGENTA // OFFLINE MEDIA ENGINE", 11f, metaGray))

        r.addView(primaryButton("\n+  SELECT MEDIA\n\nVIDEO • AUDIO • IMAGE • GIF • PDF • ZIP\n") {
            selectedUris.clear()
            pickFile(allowMultiple = false)
        })

        r.addView(text("\nENGINE STATUS", 12f, pink))
        r.addView(text(
            "CORE       ● ONLINE\n" +
                "PROCESS    IDLE\n" +
                "STORAGE    READY\n" +
                "MEDIA      WAITING FOR INPUT",
            13f, gray
        ))
        r.addView(text("\n30 ENGINES LOADED // 100% OFFLINE", 11f, metaGray))
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
                    analyze(it)
                }
            }
            REQ_PICK_MULTI -> {
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: data?.data?.let { uris.add(it) }
                selectedUris.addAll(uris)
                showFeature(selectedType, currentOp, fileName(selectedUris.first()))
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
            else -> MimeDetector.sniff(this, uri, name).let {
                when (it) {
                    MimeDetector.Kind.VIDEO -> "VIDEO"
                    MimeDetector.Kind.AUDIO -> "AUDIO"
                    MimeDetector.Kind.IMAGE -> "IMAGE"
                    MimeDetector.Kind.PDF -> "PDF"
                    MimeDetector.Kind.ARCHIVE -> "ARCHIVE"
                    else -> "UNKNOWN"
                }
            }
        }
        selectedType = type

        val r = base()
        r.addView(text("> INPUT // ANALYSIS", 20f, pink))
        r.addView(text("NAME       $name\nTYPE       $type\nSIZE       ${formatSize(size)}", 13f, white))

        if (type == "VIDEO" || type == "AUDIO") {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(this, uri)
                val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                r.addView(text("DURATION   ${formatDuration(duration?.toLongOrNull() ?: 0)}\nMIME       ${mime ?: "unknown"}", 13f, gray))
                mmr.release()
            } catch (_: Exception) {
                r.addView(text("DECODER    UNAVAILABLE\nSTATUS     INPUT REQUIRES VALIDATION", 13f, err))
            }
        }

        if (type == "UNKNOWN") {
            r.addView(text("DECODER    NOT AVAILABLE\nSTATUS     PROCESSING LOCKED", 13f, err))
            r.addView(button("← BACK") { showHome() })
            return
        }

        r.addView(text("\nAVAILABLE OPERATIONS", 12f, pink))
        val ops = when (type) {
            "VIDEO" -> videoOps
            "GIF" -> gifFileOps
            "IMAGE" -> imageOps
            "AUDIO" -> audioOps
            "PDF" -> pdfOps
            "ARCHIVE" -> archiveOps
            else -> emptyList()
        }
        ops.forEach { op ->
            r.addView(button("> $op") {
                currentOp = op
                if (op in multiInputOps) {
                    r.addView(text("\nADD MORE FILES TO CONTINUE (multi-select)", 12f, warn))
                    pickFile(allowMultiple = true)
                } else {
                    showFeature(type, op, name)
                }
            })
        }
        r.addView(button("← NEW MEDIA") { showHome() })
    }

    // =========================================================================
    // Feature configuration screen
    // =========================================================================

    private fun showFeature(type: String, op: String, name: String) {
        val r = base()
        r.addView(text("$type // ${op.uppercase()}", 20f, pink))
        val inputLabel = if (selectedUris.size > 1) "${selectedUris.size} FILES SELECTED" else name
        r.addView(text("\nINPUT\n$inputLabel", 14f, white))

        when {
            op == "Video Trim & Reverse" -> rangeUI(r, "TIME RANGE (SECONDS)", "0", "10", "sec") { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            op == "Audio Trim & Reverse" -> rangeUI(r, "TIME RANGE (SECONDS)", "0", "10", "sec") { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            op == "Split & Reverse PDF" -> rangeUI(r, "PAGE RANGE", "1", "10", "pg") { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
            op == "Video to GIF" -> {
                rangeUI(r, "CLIP RANGE (SECONDS)", "0", "3", "sec") { a, b -> cfgRangeStart = a; cfgRangeEnd = b }
                fpsUI(r)
            }
            op == "Extract Frame" -> rangeUI(r, "TIMESTAMP (SECONDS)", "1", "1", "sec") { a, _ -> cfgRangeStart = a }
            op == "Video Compress & Mute" -> { presetUI(r); muteToggleUI(r) }
            op == "Video Speed" -> speedUI(r)
            op == "Video Rotate & Flip" -> rotateUI(r)
            op == "Image Converter" -> formatUI(r)
            op == "Audio Volume Booster" -> gainUI(r)
            op == "Photo Compress" || op == "Batch Photo Compress" -> targetSizeUI(r)
            op == "Lock PDF" || op == "Unlock PDF" -> passwordUI(r)
            op == "Watermark PDF" -> watermarkUI(r)
            op == "Photo to GIF" -> { fpsUI(r); delayUI(r) }
            else -> r.addView(text("\nCONFIGURATION\nNo additional parameters required.", 13f, gray))
        }

        r.addView(text("\nSTATUS\n● INPUT VERIFIED\n● READY FOR PROCESSING", 13f, green))
        r.addView(primaryButton("▶ START") { runOperation(type, op) })
        r.addView(button("← BACK") { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
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
        r.addView(text("\nCOMPRESSION PROFILE", 13f, pink))
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val opts = listOf(
            "HIGH — Maximum quality" to MediaEngine.Quality.HIGH,
            "MEDIUM — Balanced" to MediaEngine.Quality.MEDIUM,
            "LOW — Maximum reduction" to MediaEngine.Quality.LOW
        )
        val buttons = mutableListOf<Button>()
        opts.forEach { (label, q) ->
            val b = button(label) {
                cfgQuality = q
                buttons.forEach { it.setBackgroundColor(Color.rgb(26, 14, 22)) }
            }
            buttons.add(b)
            group.addView(b)
        }
        r.addView(group)
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
        r.addView(text("\nROTATE / FLIP", 13f, gray))
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
        r.addView(text("> MEDIACOMPRESSOR // CORE <", 19f, pink))
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
                tv.text = "> PROCESS RUNNING\n[✓] INPUT ANALYSIS\n[✓] STREAM VALIDATION\n[●] $op\nPROGRESS  $bar  $percent%\nSTATUS    $status"
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
        r.addView(text("> RESULT // $op", 20f, pink))

        var anySuccess = false
        outcomes.forEach { outcome ->
            when (outcome) {
                is EngineResult.Success -> {
                    anySuccess = true
                    val saved = engine.finalize(outcome.outputFile, outcome.outputFile.name.removePrefix(".tmp_"))
                    val mime = guessMime(saved.name)
                    val publishedUri = MediaStoreExporter.publish(this, saved, type, mime)
                    val ratio = if (outcome.inputBytes > 0)
                        String.format(Locale.US, "%.1f%%", 100.0 * (1 - outcome.outputBytes.toDouble() / outcome.inputBytes))
                    else "N/A"
                    r.addView(text(
                        "\n● SUCCESS\n" +
                            "FILE       ${saved.name}\n" +
                            "IN SIZE    ${formatSize(outcome.inputBytes)}\n" +
                            "OUT SIZE   ${formatSize(outcome.outputBytes)}\n" +
                            "SAVED      ${if (outcome.inputBytes > 0) ratio else "N/A"}\n" +
                            "NOTE       ${outcome.note}\n" +
                            "EXPORTED   ${if (publishedUri != null) "Download/MediaCompressor/$type" else "LOCAL ONLY (export failed)"}",
                        13f, green
                    ))
                }
                is EngineResult.Rejected -> {
                    r.addView(text("\n✕ ${outcome.reason}\nOutput was not smaller than the original, so the original file was preserved untouched.", 13f, warn))
                }
                is EngineResult.Failure -> {
                    r.addView(text("\n✕ FAILED\nERROR      ${outcome.error}", 13f, err))
                }
            }
        }

        if (!anySuccess) {
            r.addView(text("\nSTATUS     NO OUTPUT PRODUCED", 13f, err))
        }

        r.addView(primaryButton("▶ NEW OPERATION") { showHome() })
        r.addView(button("← BACK TO FILE") { if (selectedUris.isNotEmpty()) analyze(selectedUris.first()) else showHome() })
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
    // Menu / About
    // =========================================================================

    private fun showMenu() {
        val r = base()
        r.addView(text("MEDIACOMPRESSOR", 20f, pink))
        r.addView(text("Vr3tH", 13f, gray))
        listOf("VIDEO", "IMAGE", "AUDIO", "PDF", "ARCHIVE", "SETTINGS", "ABOUT").forEach {
            r.addView(button("> $it") { if (it == "ABOUT") showAbout() else showHome() })
        }
        r.addView(button("← HOME") { showHome() })
    }

    private fun showAbout() {
        val r = base()
        r.addView(text("MEDIACOMPRESSOR // ABOUT", 19f, pink))
        r.addView(text(
            "\nMEDIA PROCESSING ENGINE\n\n" +
                "Developer\nVr3tH\n\n" +
                "BLACK // MAGENTA // OFFLINE-FIRST\n\n" +
                "30 engines · pure Android SDK · zero heavy deps",
            14f, white
        ))
        r.addView(button("← BACK") { showMenu() })
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
