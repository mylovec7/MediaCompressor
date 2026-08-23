package com.vr3th.mediacompressor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.vr3th.mediacompressor.data.*
import com.vr3th.mediacompressor.engine.*
import com.vr3th.mediacompressor.ui.screens.*
import com.vr3th.mediacompressor.ui.theme.MediaCompressorTheme
import com.vr3th.mediacompressor.utils.*
import kotlinx.coroutines.*
import java.io.File

enum class ScreenState { MAIN, PROCESS, RESULT, EXTRACT_LAB, TOOLS, HISTORY }

class MainActivity : ComponentActivity() {
    private lateinit var historyStore: HistoryStore
    private lateinit var smartEngine: SmartEngine
    private lateinit var videoEngine: VideoEngine
    private lateinit var audioEngine: AudioEngine
    private lateinit var imageEngine: ImageEngine
    private lateinit var gifEngine: GifEngine
    private lateinit var archiveEngine: ArchiveEngine
    private lateinit var documentEngine: DocumentEngine
    private lateinit var mediaToolEngine: MediaToolEngine
    private var activeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyStore = HistoryStore(this)
        smartEngine = SmartEngine(this)
        videoEngine = VideoEngine(this)
        audioEngine = AudioEngine(this)
        imageEngine = ImageEngine(this)
        gifEngine = GifEngine(this)
        archiveEngine = ArchiveEngine(this)
        documentEngine = DocumentEngine(this)
        mediaToolEngine = MediaToolEngine(this)
        StorageUtils.clearCache(this)

        setContent {
            MediaCompressorTheme {
                var screen by remember { mutableStateOf(ScreenState.MAIN) }
                var status by remember { mutableStateOf(ProcessStatus()) }
                var result by remember { mutableStateOf<ProcessResult?>(null) }
                var history by remember { mutableStateOf(historyStore.getHistory()) }

                when (screen) {
                    ScreenState.MAIN -> MainScreen(
                        onMediaSelected = { item ->
                            screen = ScreenState.PROCESS
                            processMediaItem(item, { status = it }) {
                                result = it
                                history = historyStore.getHistory()
                                screen = ScreenState.RESULT
                            }
                        },
                        onNavigateExtract = { screen = ScreenState.EXTRACT_LAB },
                        onNavigateTools = { screen = ScreenState.TOOLS },
                        onNavigateHistory = { history = historyStore.getHistory(); screen = ScreenState.HISTORY }
                    )

                    ScreenState.PROCESS -> ProcessScreen(status) {
                        activeJob?.cancel()
                        StorageUtils.clearCache(this)
                        screen = ScreenState.MAIN
                    }

                    ScreenState.RESULT -> result?.let { r ->
                        ResultScreen(r, { r.outputUri?.let(::shareUri) }) { screen = ScreenState.MAIN }
                    }

                    ScreenState.EXTRACT_LAB -> ExtractScreen(
                        onExtractAudio = { u ->
                            screen = ScreenState.PROCESS
                            runJob {
                                status = ProcessStatus("Extracting audio stream...", .5f)
                                finalizeResult(audioEngine.extractAudioFromVideo(u, u.lastPathSegment ?: "audio_extracted"), MediaType.AUDIO, "audio/mp4", false) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onExtractGif = { u ->
                            screen = ScreenState.PROCESS
                            runJob {
                                status = ProcessStatus("Generating GIF animation...", .5f)
                                finalizeResult(gifEngine.extractFramesAsGif(u, u.lastPathSegment ?: "anim"), MediaType.GIF, "image/gif", false) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onExtractZip = { u ->
                            screen = ScreenState.PROCESS
                            runJob {
                                status = ProcessStatus("Extracting ZIP contents...", .5f)
                                result = archiveEngine.extractZip(u, u.lastPathSegment ?: "archive")
                                screen = ScreenState.RESULT
                            }
                        },
                        onBack = { screen = ScreenState.MAIN }
                    )

                    ScreenState.TOOLS -> ToolsScreen(
                        onTrimVideo = { u, s, e ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Trimming video...", .5f)
                                finishTool(mediaToolEngine.trimMedia(u, u.lastPathSegment ?: "video_trim", s, e, true), MediaType.VIDEO, "video/mp4", true) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onTrimAudio = { u, s, e ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Trimming audio...", .5f)
                                finishTool(mediaToolEngine.trimMedia(u, u.lastPathSegment ?: "audio_trim", s, e, false), MediaType.AUDIO, "audio/mp4", true) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onReverseVideo = { u ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Reversing video frames...", .2f)
                                finishTool(mediaToolEngine.reverseVideo(u, u.lastPathSegment ?: "video_reverse"), MediaType.VIDEO, "video/mp4", false) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onMuteVideo = { u ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Removing audio track...", .5f)
                                finishTool(mediaToolEngine.muteVideo(u, u.lastPathSegment ?: "video_mute"), MediaType.VIDEO, "video/mp4", true) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onMergeVideos = { us ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Merging compatible video streams...", .5f)
                                finishTool(mediaToolEngine.mergeVideos(us, "merged_video"), MediaType.VIDEO, "video/mp4", false) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onMergePdfs = { us ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Merging PDF pages...", .5f)
                                finishTool(mediaToolEngine.mergePdfs(us, "merged_pdf"), MediaType.DOCUMENT, "application/pdf", false) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onSplitPdf = { u, s, e ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Splitting PDF pages...", .5f)
                                finishTool(mediaToolEngine.splitPdf(u, u.lastPathSegment ?: "split", s, e), MediaType.DOCUMENT, "application/pdf", false) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onSplitPdfReverse = { u, s, e ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Splitting PDF in reverse order...", .5f)
                                finishTool(mediaToolEngine.splitPdfReverse(u, u.lastPathSegment ?: "split_reverse", s, e), MediaType.DOCUMENT, "application/pdf", false) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onBatchImages = { us ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Batch-compressing photos...", .4f)
                                finishTool(mediaToolEngine.batchCompressImages(us, "batch_photos"), MediaType.ARCHIVE, "application/zip", true) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onRemoveExif = { u ->
                            screen = ScreenState.PROCESS; runJob {
                                status = ProcessStatus("Removing image metadata...", .5f)
                                finishTool(mediaToolEngine.removeExif(u, u.lastPathSegment ?: "no_exif"), MediaType.IMAGE, "image/jpeg", false) { result = it; screen = ScreenState.RESULT }
                            }
                        },
                        onBack = { screen = ScreenState.MAIN }
                    )

                    ScreenState.HISTORY -> HistoryScreen(history, { historyStore.clear(); history = emptyList() }) { screen = ScreenState.MAIN }
                }
            }
        }
    }

    private fun processMediaItem(item: MediaItem, onStatus: (ProcessStatus) -> Unit, onFinish: (ProcessResult) -> Unit) {
        val mime = contentResolver.getType(item.uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.name.substringAfterLast('.', "").lowercase())
            ?: item.mimeType
        runJob {
            when {
                mime.startsWith("image/") -> {
                    onStatus(ProcessStatus("Analyzing and compressing image...", .3f))
                    finalizeResult(imageEngine.compressImage(item.uri, item.name), MediaType.IMAGE, "image/webp", true, onFinish)
                }
                mime.startsWith("audio/") -> {
                    onStatus(ProcessStatus("Processing audio...", .5f))
                    finalizeResult(audioEngine.extractAudioFromVideo(item.uri, item.name), MediaType.AUDIO, "audio/mp4", true, onFinish)
                }
                mime.startsWith("application/pdf") -> {
                    onStatus(ProcessStatus("Compacting PDF pages...", .4f))
                    finalizeResult(documentEngine.compressPdf(item.uri, item.name), MediaType.DOCUMENT, "application/pdf", true, onFinish)
                }
                mime.contains("zip") || item.name.endsWith(".zip", true) -> {
                    onStatus(ProcessStatus("Recompressing ZIP archive...", .5f))
                    finalizeResult(archiveEngine.compressToZip(listOf(item.uri), item.name), MediaType.ARCHIVE, "application/zip", true, onFinish)
                }
                else -> {
                    onStatus(ProcessStatus("Analyzing video structure...", .2f))
                    val meta = smartEngine.analyzeVideo(item.uri)
                    val plan = smartEngine.makeVideoPlan(meta)
                    onStatus(ProcessStatus(plan.reason, .5f, plan.targetCodec, plan.isHardwareAccelerated))
                    finalizeResult(videoEngine.processVideo(item.uri, meta, plan, item.name, onStatus), MediaType.VIDEO, "video/mp4", false, onFinish)
                }
            }
        }
    }

    private fun runJob(block: suspend () -> Unit) {
        activeJob?.cancel()
        activeJob = lifecycleScope.launch(Dispatchers.Default) { block() }
    }

    private suspend fun finishTool(res: ProcessResult, type: MediaType, mime: String, rejectIfLarger: Boolean, done: (ProcessResult) -> Unit) {
        finalizeResult(res, type, mime, rejectIfLarger, done)
    }

    private suspend fun finalizeResult(res: ProcessResult, type: MediaType, mimeType: String, rejectIfLarger: Boolean, onFinish: (ProcessResult) -> Unit) {
        if (!res.isSuccess || res.outputPath.isEmpty()) {
            withContext(Dispatchers.Main) { onFinish(res) }
            return
        }
        if (rejectIfLarger && res.originalSize > 0L && res.compressedSize >= res.originalSize) {
            try { File(res.outputPath).delete() } catch (_: Exception) {}
            withContext(Dispatchers.Main) { onFinish(res.copy(isSuccess = false, errorMessage = "Compression rejected: output is not smaller than the original file.")) }
            return
        }
        val saved = StorageUtils.saveToMediaCompressorFolder(this@MainActivity, File(res.outputPath), "MC_${System.currentTimeMillis()}_${res.originalName}", type, mimeType)
        if (saved == null) {
            try { File(res.outputPath).delete() } catch (_: Exception) {}
            withContext(Dispatchers.Main) { onFinish(res.copy(isSuccess = false, errorMessage = "Output could not be saved to MediaCompressor folder.")) }
            return
        }
        val finalRes = res.copy(outputUri = saved)
        historyStore.saveItem(HistoryItem(
            fileName = res.originalName,
            originalSize = res.originalSize,
            compressedSize = res.compressedSize,
            dateText = FormatUtils.getCurrentDateFormatted(),
            outputPath = saved.toString(),
            mediaType = type
        ))
        try { File(res.outputPath).delete() } catch (_: Exception) {}
        withContext(Dispatchers.Main) { onFinish(finalRes) }
    }

    private fun shareUri(uri: Uri) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share with"))
    }

    override fun onDestroy() {
        activeJob?.cancel()
        StorageUtils.clearCache(this)
        super.onDestroy()
    }
}
