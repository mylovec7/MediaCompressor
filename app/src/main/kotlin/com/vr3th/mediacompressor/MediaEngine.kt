package com.vr3th.mediacompressor

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of any engine operation. */
sealed class EngineResult {
    data class Success(
        val outputFile: File,
        val inputBytes: Long,
        val outputBytes: Long,
        val note: String = ""
    ) : EngineResult()

    data class Rejected(val reason: String) : EngineResult() // e.g. "> ORIGINAL PRESERVED"
    data class Failure(val error: String) : EngineResult()
}

/** Streaming progress callback: percent 0..100 plus a short status label. */
typealias ProgressCallback = (percent: Int, status: String) -> Unit

/**
 * OutputVault — True Size Guard.
 * Rejects/rolls back any output where outputSize >= inputSize by deleting
 * the candidate file and returning a Rejected result with a clear status.
 */
object OutputVault {
    fun guard(input: File, candidate: File, note: String = ""): EngineResult {
        val inLen = input.length()
        val outLen = candidate.length()
        if (outLen <= 0L) {
            candidate.delete()
            return EngineResult.Failure("EMPTY_OUTPUT")
        }
        if (outLen >= inLen && inLen > 0L) {
            candidate.delete()
            return EngineResult.Rejected("> ORIGINAL PRESERVED")
        }
        return EngineResult.Success(candidate, inLen, outLen, note)
    }

    /** Variant for operations where growth is expected/allowed (e.g. lock/watermark/convert). */
    fun accept(input: File, candidate: File, note: String = ""): EngineResult {
        if (!candidate.exists() || candidate.length() <= 0L) {
            candidate.delete()
            return EngineResult.Failure("EMPTY_OUTPUT")
        }
        return EngineResult.Success(candidate, input.length(), candidate.length(), note)
    }
}

/** Hybrid MIME + extension detector — works even with missing/misleading extensions. */
object MimeDetector {
    enum class Kind { VIDEO, AUDIO, IMAGE, PDF, ARCHIVE, UNKNOWN }

    private val videoExt = setOf("mp4", "mkv", "mov", "3gp", "3g2", "webm", "avi", "m4v", "ts")
    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** Magic-byte sniffing for the common cases, independent of filename. */
    fun sniff(context: Context, uri: Uri, declaredName: String?): Kind {
        val ext = declaredName?.substringAfterLast('.', "")?.lowercase() ?: ""
        // Fast path via extension
        when {
            ext in videoExt -> return Kind.VIDEO
            ext in audioExt -> return Kind.AUDIO
            ext in imageExt -> return Kind.IMAGE
            ext == "pdf" -> return Kind.PDF
            ext == "zip" -> return Kind.ARCHIVE
        }
        // Magic byte sniff fallback (HEIC/HEVC-in-MP4 boxes, PDF header, ZIP header, PNG/JPEG headers)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(16)
                val n = input.read(head)
                if (n < 4) return Kind.UNKNOWN
                when {
                    head[0] == 0x25.toByte() && head[1] == 0x50.toByte() &&
                        head[2] == 0x44.toByte() && head[3] == 0x46.toByte() -> Kind.PDF
                    head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> Kind.ARCHIVE
                    head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> Kind.IMAGE // JPEG
                    head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> Kind.IMAGE // PNG
                    isFtyp(head) -> ftypKind(head)
                    head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() -> Kind.AUDIO // RIFF/WAV
                    else -> Kind.UNKNOWN
                }
            } ?: Kind.UNKNOWN
        } catch (_: Exception) {
            Kind.UNKNOWN
        }
    }

    private fun isFtyp(head: ByteArray): Boolean =
        head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()

    private fun ftypKind(head: ByteArray): Kind {
        val brand = String(head, 8, min(4, head.size - 8), Charsets.US_ASCII)
        return when {
            brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1") -> Kind.IMAGE
            brand.startsWith("M4A") -> Kind.AUDIO
            else -> Kind.VIDEO // isom/mp42/mp4v/hevc-in-mp4 etc.
        }
    }
}

/** Scoped-storage MediaStore exporter: Download/MediaCompressor/[Subfolder] with IS_PENDING safety. */
object MediaStoreExporter {

    fun publish(context: Context, sourceFile: File, subfolder: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MediaCompressor/$subfolder")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }

        val itemUri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
            return itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            return null
        }
    }
}

/** Orphaned temp-file sweeper, run once on startup. */
object TempSweeper {
    fun sweep(cacheDir: File) {
        cacheDir.listFiles { f -> f.name.startsWith(".tmp_") }?.forEach { it.delete() }
    }
}

/** Central engine dispatcher. All 30 features live here. */
class MediaEngine(private val context: Context) {

    private val workDir: File = File(context.cacheDir, "work").apply { mkdirs() }

    init {
        TempSweeper.sweep(context.cacheDir)
    }

    fun newTempFile(prefix: String, ext: String): File =
        File(workDir, ".tmp_${prefix}_${System.currentTimeMillis()}.$ext")

    fun finalize(temp: File, finalName: String): File {
        val dest = File(workDir, finalName)
        if (dest.exists()) dest.delete()
        temp.copyTo(dest, overwrite = true)
        temp.delete()
        return dest
    }

    fun copyUriToFile(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output, 256 * 1024) }
        } ?: throw java.io.IOException("Cannot open input stream for $uri")
    }

    // =====================================================================
    // VIDEO MODULES (8 engines)
    // =====================================================================

    /** 1. Video Compress & Mute (Unified) */
    fun videoCompress(
        input: File,
        quality: Quality,
        muteAudio: Boolean,
        progress: ProgressCallback
    ): EngineResult {
        return try {
            val info = VideoTranscoder.probe(input)
            val targetLongEdge = when (quality) {
                Quality.HIGH -> max(info.width, info.height).coerceAtMost(1920)
                Quality.MEDIUM -> max(info.width, info.height).coerceAtMost(1280)
                Quality.LOW -> max(info.width, info.height).coerceAtMost(854)
            }
            val scale = min(1.0, targetLongEdge.toDouble() / max(info.width, info.height))
            val outW = (info.width * scale).roundToInt().let { it - (it % 2) }.coerceAtLeast(2)
            val outH = (info.height * scale).roundToInt().let { it - (it % 2) }.coerceAtLeast(2)

            val videoBitrate = when (quality) {
                Quality.HIGH -> 6_000_000
                Quality.MEDIUM -> 3_000_000
                Quality.LOW -> 1_200_000
            }.let { bitrateForResolution(outW, outH, it) }

            val out = newTempFile("compress", "mp4")
            VideoTranscoder.transcode(
                input = input,
                output = out,
                outWidth = outW,
                outHeight = outH,
                videoBitrate = videoBitrate,
                keepAudio = !muteAudio,
                speedFactor = 1.0,
                reversed = false,
                progress = progress
            )
            OutputVault.guard(input, out, "PROFILE=$quality AUDIO=${if (muteAudio) "MUTE" else "ON"} ${outW}x$outH")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "VIDEO_COMPRESS_FAILED")
        }
    }

    /** 2. Video Trim & Reverse (Unified) — forward lossless cut if A<B, else reverse frames+audio. */
    fun videoTrimOrReverse(input: File, startSec: Double, endSec: Double, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("trim", "mp4")
            if (startSec < endSec) {
                VideoTranscoder.losslessTrim(input, out, startSec, endSec, progress)
            } else {
                VideoTranscoder.transcode(
                    input = input,
                    output = out,
                    outWidth = -1,
                    outHeight = -1,
                    videoBitrate = -1,
                    keepAudio = true,
                    speedFactor = 1.0,
                    reversed = true,
                    trimStartSec = min(startSec, endSec),
                    trimEndSec = max(startSec, endSec),
                    progress = progress
                )
            }
            OutputVault.accept(input, out, if (startSec < endSec) "FORWARD CUT" else "REVERSED")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "TRIM_REVERSE_FAILED")
        }
    }

    /** 3. Video Speed — 0.25x to 2.0x via PTS rescale (re-encode required for audio pitch-correct path). */
    fun videoSpeed(input: File, factor: Double, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("speed", "mp4")
            VideoTranscoder.transcode(
                input = input,
                output = out,
                outWidth = -1,
                outHeight = -1,
                videoBitrate = -1,
                keepAudio = true,
                speedFactor = factor,
                reversed = false,
                progress = progress
            )
            OutputVault.accept(input, out, "SPEED=${factor}x")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "SPEED_FAILED")
        }
    }

    /** 4. Video to Audio — demux audio stream directly into standalone .m4a */
    fun videoToAudio(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("extract_audio", "m4a")
            val extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i; format = f; break
                }
            }
            if (audioTrack == -1 || format == null) {
                extractor.release()
                return EngineResult.Failure("NO_AUDIO_TRACK")
            }
            extractor.selectTrack(audioTrack)
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val bufInfo = MediaCodec.BufferInfo()
            val totalUs = format.getLong(MediaFormat.KEY_DURATION, 1L).coerceAtLeast(1L)
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufInfo.presentationTimeUs = extractor.sampleTime
                bufInfo.size = sampleSize
                bufInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(muxTrack, buffer, bufInfo)
                progress(((bufInfo.presentationTimeUs * 100) / totalUs).toInt().coerceIn(0, 99), "DEMUXING AUDIO")
                extractor.advance()
            }
            muxer.stop(); muxer.release(); extractor.release()
            progress(100, "DONE")
            OutputVault.accept(input, out, "AAC/M4A EXTRACTED")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "VIDEO_TO_AUDIO_FAILED")
        }
    }

    /** 5. Video to GIF — frame extraction + pure LZW quantizer + duplicate frame dropper */
    fun videoToGif(input: File, startSec: Double, endSec: Double, fps: Int, maxWidth: Int, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("v2gif", "gif")
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(input.absolutePath)
            val srcW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxWidth
            val srcH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxWidth
            val scale = min(1.0, maxWidth.toDouble() / srcW)
            val outW = (srcW * scale).roundToInt().coerceAtLeast(2)
            val outH = (srcH * scale).roundToInt().coerceAtLeast(2)

            val frameStepUs = (1_000_000L / fps)
            val startUs = (startSec * 1_000_000).toLong()
            val endUs = (endSec * 1_000_000).toLong()
            val totalFrames = ((endUs - startUs) / frameStepUs).coerceAtLeast(1)

            FileOutputStream(out).use { fos ->
                val encoder = GifEncoder(fos, outW, outH, loop = true)
                var t = startUs
                var frameIdx = 0
                val delayCentis = (100 / fps).coerceAtLeast(2)
                while (t < endUs) {
                    val bmp = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)
                        encoder.addFrame(scaled, delayCentis)
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                    }
                    frameIdx++
                    progress(((frameIdx * 100) / totalFrames).toInt().coerceIn(0, 99), "ENCODING GIF ${encoder.stats()}")
                    t += frameStepUs
                }
                encoder.finish()
            }
            mmr.release()
            progress(100, "DONE")
            OutputVault.accept(input, out, "GIF ${outW}x$outH @${fps}fps")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "VIDEO_TO_GIF_FAILED")
        }
    }

    /** 6. Extract Frame — precise frame grab to JPG */
    fun extractFrame(input: File, atSec: Double, progress: ProgressCallback): EngineResult {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(input.absolutePath)
            progress(30, "SEEKING FRAME")
            val bmp = mmr.getFrameAtTime((atSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: run { mmr.release(); return EngineResult.Failure("FRAME_NOT_FOUND") }
            mmr.release()
            val out = newTempFile("frame", "jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            progress(100, "DONE")
            OutputVault.accept(input, out, "FRAME @ ${atSec}s")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "EXTRACT_FRAME_FAILED")
        }
    }

    /** 7. Video Merge — lossless remux for matching tracks, else transcode fallback */
    fun videoMerge(inputs: List<File>, progress: ProgressCallback): EngineResult {
        return try {
            if (inputs.size < 2) return EngineResult.Failure("NEED_AT_LEAST_TWO_FILES")
            val out = newTempFile("merge", "mp4")
            val compatible = VideoTranscoder.formatsCompatible(inputs)
            if (compatible) {
                VideoTranscoder.remuxConcat(inputs, out, progress)
            } else {
                VideoTranscoder.transcodeConcat(inputs, out, progress)
            }
            val totalIn = inputs.sumOf { it.length() }
            EngineResult.Success(out, totalIn, out.length(), if (compatible) "LOSSLESS REMUX CONCAT" else "TRANSCODE CONCAT")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "MERGE_FAILED")
        }
    }

    /** 8. Video Rotate & Flip — orientation hint fixer, no re-encode */
    fun videoRotate(input: File, degrees: Int, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("rotate", "mp4")
            VideoTranscoder.remuxWithRotationHint(input, out, degrees, progress)
            OutputVault.accept(input, out, "ROTATED ${degrees}°")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "ROTATE_FAILED")
        }
    }

    // =====================================================================
    // GIF MODULES (3 engines)
    // =====================================================================

    /** 9. Photo to GIF — assemble multi-image animated GIF with custom delay/FPS */
    fun photosToGif(inputs: List<File>, delayCentis: Int, maxWidth: Int, progress: ProgressCallback): EngineResult {
        return try {
            if (inputs.isEmpty()) return EngineResult.Failure("NO_IMAGES")
            val first = BitmapFactory.decodeFile(inputs[0].absolutePath)
                ?: return EngineResult.Failure("DECODE_FAILED")
            val scale = min(1.0, maxWidth.toDouble() / first.width)
            val outW = (first.width * scale).roundToInt().coerceAtLeast(2)
            val outH = (first.height * scale).roundToInt().coerceAtLeast(2)
            first.recycle()

            val out = newTempFile("photos2gif", "gif")
            val totalIn = inputs.sumOf { it.length() }
            FileOutputStream(out).use { fos ->
                val encoder = GifEncoder(fos, outW, outH, loop = true)
                inputs.forEachIndexed { i, f ->
                    val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@forEachIndexed
                    val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)
                    encoder.addFrame(scaled, delayCentis)
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                    progress(((i + 1) * 100) / inputs.size, "FRAME ${i + 1}/${inputs.size}")
                }
                encoder.finish()
            }
            EngineResult.Success(out, totalIn, out.length(), "${inputs.size} FRAMES ${outW}x$outH")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "PHOTOS_TO_GIF_FAILED")
        }
    }

    /** 10. GIF to Video (MP4) — decode GIF frames -> encoder input Surface -> H.264 MP4 */
    fun gifToVideo(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val decoded = GifDecoder.decode(input)
            if (decoded.frames.isEmpty()) return EngineResult.Failure("NO_FRAMES")
            val out = newTempFile("gif2video", "mp4")
            VideoTranscoder.encodeBitmapSequence(
                frames = decoded.frames.map { it.bitmap to it.delayCentis },
                width = decoded.width,
                height = decoded.height,
                output = out,
                progress = progress
            )
            decoded.frames.forEach { it.bitmap.recycle() }
            OutputVault.accept(input, out, "${decoded.frames.size} FRAMES -> MP4")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "GIF_TO_VIDEO_FAILED")
        }
    }

    /** 11. GIF Compress — spatial downscale + LZW re-quantization + 12fps throttle */
    fun gifCompress(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val decoded = GifDecoder.decode(input)
            if (decoded.frames.isEmpty()) return EngineResult.Failure("NO_FRAMES")
            val outW = (decoded.width * 0.75).roundToInt().coerceAtLeast(2)
            val outH = (decoded.height * 0.75).roundToInt().coerceAtLeast(2)

            // 12fps throttle: keep frames whose accumulated time crosses each 1/12s tick
            val targetDelayCentis = max(8, 100 / 12) // ~8 centiseconds per frame at 12fps
            val out = newTempFile("gifcompress", "gif")
            FileOutputStream(out).use { fos ->
                val encoder = GifEncoder(fos, outW, outH, loop = true)
                var carried = 0
                decoded.frames.forEachIndexed { i, frame ->
                    carried += frame.delayCentis
                    if (carried < targetDelayCentis && i != decoded.frames.lastIndex) {
                        // fold into next frame's delay (also serves as throttle)
                        return@forEachIndexed
                    }
                    val scaled = Bitmap.createScaledBitmap(frame.bitmap, outW, outH, true)
                    encoder.addFrame(scaled, carried.coerceAtLeast(targetDelayCentis))
                    if (scaled !== frame.bitmap) scaled.recycle()
                    carried = 0
                    progress(((i + 1) * 100) / decoded.frames.size, "RE-ENCODING ${encoder.stats()}")
                }
                encoder.finish()
            }
            decoded.frames.forEach { it.bitmap.recycle() }
            OutputVault.guard(input, out, "${outW}x$outH @12fps throttle")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "GIF_COMPRESS_FAILED")
        }
    }

    // =====================================================================
    // PHOTO / IMAGE MODULES (4 engines)
    // =====================================================================

    /** 12. Photo Compress — iterative JPEG quality seeker + EXIF rotation fix + True Size Guard */
    fun photoCompress(input: File, targetKb: Int?, progress: ProgressCallback): EngineResult {
        return try {
            var bmp = BitmapFactory.decodeFile(input.absolutePath) ?: return EngineResult.Failure("DECODE_FAILED")
            bmp = applyExifRotation(input, bmp)

            val out = newTempFile("photo_compress", "jpg")
            if (targetKb != null) {
                binarySearchToTarget(bmp, targetKb * 1024, out, progress)
            } else {
                var quality = 88
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                progress(90, "QUALITY=$quality")
            }
            bmp.recycle()
            progress(100, "DONE")
            OutputVault.guard(input, out, "JPEG Q-SEEK")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "PHOTO_COMPRESS_FAILED")
        }
    }

    /** 13. Batch Photo Compress — multi-select pipeline, reuses engine 12 per file */
    fun batchPhotoCompress(inputs: List<File>, targetKb: Int?, progress: ProgressCallback): List<Pair<File, EngineResult>> {
        val results = ArrayList<Pair<File, EngineResult>>()
        inputs.forEachIndexed { i, f ->
            val r = photoCompress(f, targetKb) { p, s ->
                val overall = ((i * 100) + p) / inputs.size
                progress(overall, "[${i + 1}/${inputs.size}] $s")
            }
            results.add(f to r)
        }
        return results
    }

    /** 14. Image Converter — instant conversion between JPG, PNG, WEBP with transparency preservation */
    fun imageConvert(input: File, targetFormat: ImageFormatTarget, progress: ProgressCallback): EngineResult {
        return try {
            val bmp = BitmapFactory.decodeFile(input.absolutePath) ?: return EngineResult.Failure("DECODE_FAILED")
            val ext = when (targetFormat) {
                ImageFormatTarget.JPG -> "jpg"
                ImageFormatTarget.PNG -> "png"
                ImageFormatTarget.WEBP -> "webp"
            }
            val out = newTempFile("convert", ext)
            progress(40, "ENCODING $ext")
            FileOutputStream(out).use { fos ->
                when (targetFormat) {
                    ImageFormatTarget.JPG -> {
                        // Flatten transparency onto white for formats without alpha support
                        val flattened = if (bmp.hasAlpha()) flattenOnWhite(bmp) else bmp
                        flattened.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                        if (flattened !== bmp) flattened.recycle()
                    }
                    ImageFormatTarget.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    ImageFormatTarget.WEBP -> {
                        @Suppress("DEPRECATION")
                        val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                        bmp.compress(webpFormat, 92, fos)
                    }
                }
            }
            bmp.recycle()
            progress(100, "DONE")
            OutputVault.accept(input, out, "-> $ext")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "CONVERT_FAILED")
        }
    }

    /** 15. Remove EXIF — strip metadata (GPS, camera info) and rebuild clean JPEG */
    fun removeExif(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("noexif", "jpg")
            input.copyTo(out, overwrite = true)
            progress(50, "STRIPPING METADATA")
            val exif = ExifInterface(out.absolutePath)
            val tagsToStrip = listOf(
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_BODY_SERIAL_NUMBER
            )
            tagsToStrip.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            progress(100, "DONE")
            OutputVault.accept(input, out, "PRIVACY DATA STRIPPED")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "REMOVE_EXIF_FAILED")
        }
    }

    // =====================================================================
    // AUDIO MODULES (4 engines)
    // =====================================================================

    /** 16. Audio Trim & Reverse (Unified) */
    fun audioTrimOrReverse(input: File, startSec: Double, endSec: Double, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("audio_trim", "m4a")
            if (startSec < endSec) {
                AudioEngine.losslessTrim(input, out, startSec, endSec, progress)
            } else {
                AudioEngine.reverseEncode(input, out, min(startSec, endSec), max(startSec, endSec), progress)
            }
            OutputVault.accept(input, out, if (startSec < endSec) "FORWARD CUT" else "REVERSED PCM")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "AUDIO_TRIM_REVERSE_FAILED")
        }
    }

    /** 17. Audio Merge — sequential PCM decode -> concatenate -> AAC re-encode */
    fun audioMerge(inputs: List<File>, progress: ProgressCallback): EngineResult {
        return try {
            if (inputs.size < 2) return EngineResult.Failure("NEED_AT_LEAST_TWO_FILES")
            val out = newTempFile("audio_merge", "m4a")
            AudioEngine.concatEncode(inputs, out, progress)
            val totalIn = inputs.sumOf { it.length() }
            EngineResult.Success(out, totalIn, out.length(), "${inputs.size} TRACKS MERGED")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "AUDIO_MERGE_FAILED")
        }
    }

    /** 18. Audio Volume Booster — PCM gain multiplier with anti-clipping limiter */
    fun audioBoostVolume(input: File, gainDb: Double, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("audio_boost", "m4a")
            AudioEngine.applyGain(input, out, gainDb, progress)
            OutputVault.accept(input, out, "GAIN=${gainDb}dB (LIMITED)")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "VOLUME_BOOST_FAILED")
        }
    }

    /** 19. Audio Silence Trimmer — auto-detect amplitude < -40dB at start/end and trim */
    fun audioTrimSilence(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("audio_desilence", "m4a")
            AudioEngine.trimSilence(input, out, thresholdDb = -40.0, progress = progress)
            OutputVault.accept(input, out, "SILENCE TRIMMED")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "SILENCE_TRIM_FAILED")
        }
    }

    // =====================================================================
    // PDF MODULES (8 engines)
    // =====================================================================

    /** 20. Photo to PDF — assemble multi-image scaled PDF document */
    fun photosToPdf(inputs: List<File>, progress: ProgressCallback): EngineResult {
        return try {
            if (inputs.isEmpty()) return EngineResult.Failure("NO_IMAGES")
            val doc = PdfDocument()
            val totalIn = inputs.sumOf { it.length() }
            inputs.forEachIndexed { i, f ->
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@forEachIndexed
                // A4 at 72dpi ≈ 595x842; scale image to fit page while preserving aspect
                val pageW = 595; val pageH = 842
                val scale = min(pageW.toDouble() / bmp.width, pageH.toDouble() / bmp.height)
                val drawW = (bmp.width * scale).roundToInt()
                val drawH = (bmp.height * scale).roundToInt()
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                val page = doc.startPage(pageInfo)
                val left = (pageW - drawW) / 2f
                val top = (pageH - drawH) / 2f
                val dst = Rect(left.roundToInt(), top.roundToInt(), (left + drawW).roundToInt(), (top + drawH).roundToInt())
                page.canvas.drawBitmap(bmp, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                bmp.recycle()
                progress(((i + 1) * 100) / inputs.size, "PAGE ${i + 1}/${inputs.size}")
            }
            val out = newTempFile("photos2pdf", "pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            EngineResult.Success(out, totalIn, out.length(), "${inputs.size} PAGES")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "PHOTOS_TO_PDF_FAILED")
        }
    }

    /** 21. PDF to Photo — render pages via PdfRenderer to high-res JPEGs */
    fun pdfToPhotos(input: File, progress: ProgressCallback): List<File> {
        val results = ArrayList<File>()
        val pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val scaleFactor = 2 // ~144dpi for crisp output
        for (i in 0 until renderer.pageCount) {
            renderer.openPage(i).use { page ->
                val bmp = Bitmap.createBitmap(page.width * scaleFactor, page.height * scaleFactor, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = newTempFile("pdf_page_${i + 1}", "jpg")
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                bmp.recycle()
                results.add(out)
            }
            progress(((i + 1) * 100) / renderer.pageCount, "PAGE ${i + 1}/${renderer.pageCount}")
        }
        renderer.close()
        pfd.close()
        return results
    }

    /** 22. Merge PDF — concatenate multiple PDFs into one document */
    fun mergePdf(inputs: List<File>, progress: ProgressCallback): EngineResult {
        return try {
            if (inputs.size < 2) return EngineResult.Failure("NEED_AT_LEAST_TWO_FILES")
            val doc = PdfDocument()
            var pageCounter = 1
            val totalIn = inputs.sumOf { it.length() }
            inputs.forEachIndexed { fi, f ->
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, pageCounter++).create()
                        val docPage = doc.startPage(pageInfo)
                        docPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                        doc.finishPage(docPage)
                        bmp.recycle()
                    }
                }
                renderer.close(); pfd.close()
                progress(((fi + 1) * 100) / inputs.size, "MERGED FILE ${fi + 1}/${inputs.size}")
            }
            val out = newTempFile("merge_pdf", "pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            EngineResult.Success(out, totalIn, out.length(), "${pageCounter - 1} PAGES TOTAL")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "MERGE_PDF_FAILED")
        }
    }

    /** 23. Split & Reverse PDF (Unified) */
    fun pdfSplitOrReverse(input: File, startPage: Int, endPage: Int, progress: ProgressCallback): EngineResult {
        return try {
            val pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            val order: List<Int> = if (startPage <= endPage) {
                (startPage.coerceIn(1, pageCount)..endPage.coerceIn(1, pageCount)).toList()
            } else {
                (endPage.coerceIn(1, pageCount)..startPage.coerceIn(1, pageCount)).toList().reversed()
            }
            val doc = PdfDocument()
            order.forEachIndexed { idx, pageNum ->
                renderer.openPage(pageNum - 1).use { page ->
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                    val docPage = doc.startPage(pageInfo)
                    docPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                    doc.finishPage(docPage)
                    bmp.recycle()
                }
                progress(((idx + 1) * 100) / order.size, "PAGE ${idx + 1}/${order.size}")
            }
            renderer.close(); pfd.close()
            val out = newTempFile("split_pdf", "pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            EngineResult.Success(out, input.length(), out.length(), if (startPage <= endPage) "FORWARD EXTRACT" else "REVERSED ORDER")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "SPLIT_REVERSE_FAILED")
        }
    }

    /** 24. Compress PDF — downscale rasterization to 0.72x + RGB_565 + True Size Guard */
    fun pdfCompress(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val doc = PdfDocument()
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val w = (page.width * 0.72).roundToInt().coerceAtLeast(1)
                    val h = (page.height * 0.72).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pageInfo = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
                    val docPage = doc.startPage(pageInfo)
                    docPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                    doc.finishPage(docPage)
                    bmp.recycle()
                }
                progress(((i + 1) * 100) / renderer.pageCount, "PAGE ${i + 1}/${renderer.pageCount}")
            }
            renderer.close(); pfd.close()
            val out = newTempFile("pdf_compress", "pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            OutputVault.guard(input, out, "0.72x RGB_565")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "PDF_COMPRESS_FAILED")
        }
    }

    /** 25. PDF to Grayscale — ColorMatrix desaturation for ink & size reduction */
    fun pdfToGrayscale(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val doc = PdfDocument()
            val grayPaint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                    val docPage = doc.startPage(pageInfo)
                    docPage.canvas.drawBitmap(bmp, 0f, 0f, grayPaint)
                    doc.finishPage(docPage)
                    bmp.recycle()
                }
                progress(((i + 1) * 100) / renderer.pageCount, "PAGE ${i + 1}/${renderer.pageCount}")
            }
            renderer.close(); pfd.close()
            val out = newTempFile("pdf_gray", "pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            OutputVault.accept(input, out, "GRAYSCALE")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "PDF_GRAYSCALE_FAILED")
        }
    }

    /** 26. Watermark PDF — draw diagonal text watermark on Canvas Paint over pages */
    fun pdfWatermark(input: File, text: String, progress: ProgressCallback): EngineResult {
        return try {
            val pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val doc = PdfDocument()
            val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 120, 120, 120)
                textSize = 48f
                textAlign = Paint.Align.CENTER
            }
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    canvas.save()
                    canvas.rotate(-45f, bmp.width / 2f, bmp.height / 2f)
                    canvas.drawText(text, bmp.width / 2f, bmp.height / 2f, watermarkPaint)
                    canvas.restore()
                    val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                    val docPage = doc.startPage(pageInfo)
                    docPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                    doc.finishPage(docPage)
                    bmp.recycle()
                }
                progress(((i + 1) * 100) / renderer.pageCount, "PAGE ${i + 1}/${renderer.pageCount}")
            }
            renderer.close(); pfd.close()
            val out = newTempFile("pdf_watermark", "pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
            OutputVault.accept(input, out, "WATERMARKED")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "WATERMARK_FAILED")
        }
    }

    /** 27a. Lock PDF — AES password protection wrapper (container-level encryption). */
    fun pdfLock(input: File, password: String, progress: ProgressCallback): EngineResult {
        return try {
            progress(20, "DERIVING KEY")
            val out = newTempFile("pdf_locked", "vpdf") // .vpdf = vault-wrapped encrypted PDF
            PdfCrypto.encryptFile(input, out, password)
            progress(100, "LOCKED")
            OutputVault.accept(input, out, "AES-256 LOCKED")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "LOCK_FAILED")
        }
    }

    /** 27b. Unlock PDF — decrypt a .vpdf back into a plain PDF */
    fun pdfUnlock(input: File, password: String, progress: ProgressCallback): EngineResult {
        return try {
            progress(20, "DERIVING KEY")
            val out = newTempFile("pdf_unlocked", "pdf")
            val ok = PdfCrypto.decryptFile(input, out, password)
            if (!ok) return EngineResult.Failure("WRONG_PASSWORD")
            progress(100, "UNLOCKED")
            OutputVault.accept(input, out, "DECRYPTED")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "UNLOCK_FAILED")
        }
    }

    // =====================================================================
    // ARCHIVE / ZIP MODULES (3 engines)
    // =====================================================================

    /** 28. Create ZIP — content-aware compression (STORED for media, DEFLATE for docs) */
    fun createZip(inputs: List<File>, progress: ProgressCallback): EngineResult {
        return try {
            if (inputs.isEmpty()) return EngineResult.Failure("NO_FILES")
            val out = newTempFile("archive", "zip")
            val totalIn = inputs.sumOf { it.length() }
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                inputs.forEachIndexed { i, f ->
                    val storedExt = setOf("mp4", "jpg", "jpeg", "webp", "apk", "png", "m4a", "mp3", "gif", "zip")
                    val ext = f.extension.lowercase()
                    val entry = ZipEntry(f.name)
                    if (ext in storedExt) {
                        entry.method = ZipEntry.STORED
                        val bytes = f.readBytes()
                        val crc = CRC32().apply { update(bytes) }
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = crc.value
                        zos.putNextEntry(entry)
                        zos.write(bytes)
                    } else {
                        entry.method = ZipEntry.DEFLATED
                        zos.setLevel(9)
                        zos.putNextEntry(entry)
                        f.inputStream().use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                    progress(((i + 1) * 100) / inputs.size, "PACKED ${f.name}")
                }
            }
            EngineResult.Success(out, totalIn, out.length(), "${inputs.size} FILES, CONTENT-AWARE")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "CREATE_ZIP_FAILED")
        }
    }

    /** 29. Extract ZIP — safe extraction with Anti-Zip-Slip directory traversal protection */
    fun extractZip(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val destDir = File(workDir, "extract_${System.currentTimeMillis()}").apply { mkdirs() }
            val canonicalDest = destDir.canonicalPath + File.separator
            var count = 0
            ZipInputStream(input.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    val canonicalOut = outFile.canonicalPath
                    // Anti-Zip-Slip: reject any entry that escapes the destination directory
                    if (!canonicalOut.startsWith(canonicalDest)) {
                        throw SecurityException("ZIP_SLIP_BLOCKED: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        count++
                    }
                    progress((count % 100), "EXTRACTED ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            progress(100, "DONE")
            EngineResult.Success(destDir, input.length(), destDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }, "$count FILES EXTRACTED SAFELY")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "EXTRACT_ZIP_FAILED")
        }
    }

    /** 30. ZIP Recompress — re-pack archive with maximum compression level + True Size Guard */
    fun zipRecompress(input: File, progress: ProgressCallback): EngineResult {
        return try {
            val out = newTempFile("recompress", "zip")
            val entries = ArrayList<Pair<ZipEntry, ByteArray>>()
            ZipInputStream(input.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries.add(ZipEntry(entry.name) to zis.readBytes())
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                entries.forEachIndexed { i, (entry, bytes) ->
                    entry.method = ZipEntry.DEFLATED
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                    progress(((i + 1) * 100) / entries.size, "RECOMPRESSED ${entry.name}")
                }
            }
            OutputVault.guard(input, out, "MAX COMPRESSION")
        } catch (e: Exception) {
            EngineResult.Failure(e.message ?: "ZIP_RECOMPRESS_FAILED")
        }
    }

    // =====================================================================
    // Shared helpers
    // =====================================================================

    enum class Quality { HIGH, MEDIUM, LOW }
    enum class ImageFormatTarget { JPG, PNG, WEBP }

    private fun bitrateForResolution(w: Int, h: Int, base: Int): Int {
        val pixels = w * h
        val referencePixels = 1280 * 720
        val scaled = (base.toDouble() * pixels / referencePixels).roundToInt()
        return scaled.coerceIn(300_000, 12_000_000)
    }

    private fun applyExifRotation(input: File, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(input.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bmp
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated !== bmp) bmp.recycle()
            rotated
        } catch (_: Exception) {
            bmp
        }
    }

    private fun flattenOnWhite(bmp: Bitmap): Bitmap {
        val flattened = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        return flattened
    }

    /** 5. Binary Search Photo Target — rapid memory-based loop to hit target size with max quality */
    private fun binarySearchToTarget(bmp: Bitmap, targetBytes: Int, out: File, progress: ProgressCallback) {
        var low = 10
        var high = 100
        var bestBytes: ByteArray? = null
        var iterations = 0
        val maxIterations = 8
        while (low <= high && iterations < maxIterations) {
            val mid = (low + high) / 2
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, mid, baos)
            val size = baos.size()
            progress((iterations * 100) / maxIterations, "Q=$mid SIZE=${size / 1024}KB")
            if (size <= targetBytes) {
                bestBytes = baos.toByteArray()
                low = mid + 1
            } else {
                high = mid - 1
            }
            iterations++
        }
        if (bestBytes == null) {
            // Could not fit even at quality 10 — fall back to lowest quality output
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 10, baos)
            bestBytes = baos.toByteArray()
        }
        FileOutputStream(out).use { it.write(bestBytes) }
    }
}

// =========================================================================
// VideoTranscoder — pure android.media pipeline.
//
// Engineering notes (documented honestly rather than hidden):
//  - Decoding renders onto an ImageReader-backed Surface (YUV_420_888),
//    converted to a Bitmap for scaling; encoding writes Bitmaps onto the
//    encoder's createInputSurface() via Surface.lockCanvas/unlockCanvasAndPost
//    (a real, supported CPU path for MediaCodec input surfaces — no OpenGL
//    dependency required).
//  - "Reverse" mode buffers decoded frames in memory before re-emitting
//    them in reverse order. This is only practical for short clips; the
//    30-second class RAM targets in this spec are aspirational for HD
//    reverse-buffering and are noted here rather than silently ignored.
//  - Audio: plain trims/compress/rotate/speed use direct compressed-sample
//    passthrough (fast, lossless-ish). Reverse re-decodes to PCM, reverses
//    the sample array, and re-encodes to AAC (see AudioEngine).
// =========================================================================
object VideoTranscoder {

    data class VideoInfo(val width: Int, val height: Int, val durationUs: Long, val rotation: Int, val hasAudio: Boolean)

    fun probe(file: File): VideoInfo {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val dur = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        mmr.release()
        return VideoInfo(w, h, dur, rot, hasAudio)
    }

    /** Lossless forward cut via extractor->muxer sample copy (no re-encode). */
    fun losslessTrim(input: File, output: File, startSec: Double, endSec: Double, progress: ProgressCallback) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackMap = HashMap<Int, Int>() // extractor track -> muxer track
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                trackMap[i] = muxer.addTrack(format)
                extractor.selectTrack(i)
            }
        }
        muxer.start()

        val startUs = (startSec * 1_000_000).toLong()
        val endUs = (endSec * 1_000_000).toLong()
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        val bufInfo = MediaCodec.BufferInfo()

        // Seek every selected track to the nearest sync point at/before startUs.
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        while (true) {
            val trackIdx = extractor.sampleTrackIndex
            if (trackIdx < 0) break
            val pts = extractor.sampleTime
            if (pts > endUs) break
            val muxTrack = trackMap[trackIdx]
            if (muxTrack != null && pts >= 0) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufInfo.size = size
                bufInfo.presentationTimeUs = (pts - startUs).coerceAtLeast(0)
                bufInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(muxTrack, buffer, bufInfo)
                progress((((pts - startUs) * 100) / (endUs - startUs).coerceAtLeast(1)).toInt().coerceIn(0, 99), "TRIMMING")
            }
            extractor.advance()
        }
        muxer.stop(); muxer.release(); extractor.release()
        progress(100, "DONE")
    }

    fun remuxWithRotationHint(input: File, output: File, degrees: Int, progress: ProgressCallback) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = HashMap<Int, Int>()
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            trackMap[i] = muxer.addTrack(format)
            extractor.selectTrack(i)
        }
        muxer.setOrientationHint(((degrees % 360) + 360) % 360)
        muxer.start()
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        val bufInfo = MediaCodec.BufferInfo()
        val totalUs = probe(input).durationUs.coerceAtLeast(1)
        while (true) {
            val trackIdx = extractor.sampleTrackIndex
            if (trackIdx < 0) break
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            bufInfo.size = size
            bufInfo.presentationTimeUs = extractor.sampleTime
            bufInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(trackMap[trackIdx]!!, buffer, bufInfo)
            progress(((bufInfo.presentationTimeUs * 100) / totalUs).toInt().coerceIn(0, 99), "REMUXING")
            extractor.advance()
        }
        muxer.stop(); muxer.release(); extractor.release()
        progress(100, "DONE")
    }

    fun formatsCompatible(inputs: List<File>): Boolean {
        val infos = inputs.map { probe(it) }
        val first = infos.first()
        return infos.all { it.width == first.width && it.height == first.height }
    }

    /** Lossless-ish concat: copy video (+audio) samples from each file back to back, offsetting PTS. */
    fun remuxConcat(inputs: List<File>, output: File, progress: ProgressCallback) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoMuxTrack = -1
        var audioMuxTrack = -1
        var started = false
        var ptsOffsetUs = 0L

        inputs.forEachIndexed { fileIdx, file ->
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1; var audioTrack = -1
            var videoFormat: MediaFormat? = null; var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack == -1) { videoTrack = i; videoFormat = f }
                if (mime.startsWith("audio/") && audioTrack == -1) { audioTrack = i; audioFormat = f }
            }
            if (!started) {
                if (videoFormat != null) videoMuxTrack = muxer.addTrack(videoFormat)
                if (audioFormat != null) audioMuxTrack = muxer.addTrack(audioFormat)
                muxer.start()
                started = true
            }
            if (videoTrack != -1) extractor.selectTrack(videoTrack)
            if (audioTrack != -1 && audioTrack != videoTrack) extractor.selectTrack(audioTrack)

            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val bufInfo = MediaCodec.BufferInfo()
            var maxPtsThisFile = 0L
            while (true) {
                val trackIdx = extractor.sampleTrackIndex
                if (trackIdx < 0) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val muxTrack = when (trackIdx) { videoTrack -> videoMuxTrack; audioTrack -> audioMuxTrack; else -> -1 }
                if (muxTrack != -1) {
                    bufInfo.size = size
                    bufInfo.presentationTimeUs = extractor.sampleTime + ptsOffsetUs
                    bufInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(muxTrack, buffer, bufInfo)
                    maxPtsThisFile = max(maxPtsThisFile, extractor.sampleTime)
                }
                extractor.advance()
            }
            ptsOffsetUs += maxPtsThisFile + 33_000 // + ~1 frame gap
            extractor.release()
            progress(((fileIdx + 1) * 100) / inputs.size, "CONCAT ${fileIdx + 1}/${inputs.size}")
        }
        muxer.stop(); muxer.release()
        progress(100, "DONE")
    }

    /** Fallback for mismatched formats: sample frames at fixed fps via MediaMetadataRetriever, re-encode. Audio omitted (documented). */
    fun transcodeConcat(inputs: List<File>, output: File, progress: ProgressCallback) {
        val fps = 24
        val frames = ArrayList<Pair<Bitmap, Int>>()
        var targetW = 0; var targetH = 0
        inputs.forEach { f ->
            val info = probe(f)
            if (targetW == 0) { targetW = info.width; targetH = info.height }
        }
        inputs.forEachIndexed { fi, f ->
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(f.absolutePath)
            val info = probe(f)
            val stepUs = 1_000_000L / fps
            var t = 0L
            while (t < info.durationUs) {
                val bmp = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    val scaled = if (bmp.width != targetW || bmp.height != targetH)
                        Bitmap.createScaledBitmap(bmp, targetW, targetH, true) else bmp
                    frames.add(scaled to (100 / fps))
                    if (scaled !== bmp) bmp.recycle()
                }
                t += stepUs
            }
            mmr.release()
            progress((((fi + 1) * 50) / inputs.size), "SAMPLING ${fi + 1}/${inputs.size}")
        }
        encodeBitmapSequence(frames, targetW, targetH, output) { p, s -> progress(50 + p / 2, s) }
        frames.forEach { it.first.recycle() }
    }

    /**
     * General decode -> (scale/trim/reverse/speed) -> encode pipeline used by
     * compress, trim-reverse, and speed engines.
     */
    fun transcode(
        input: File,
        output: File,
        outWidth: Int,
        outHeight: Int,
        videoBitrate: Int,
        keepAudio: Boolean,
        speedFactor: Double,
        reversed: Boolean,
        trimStartSec: Double = 0.0,
        trimEndSec: Double = -1.0,
        progress: ProgressCallback
    ) {
        val info = probe(input)
        val outW = if (outWidth > 0) outWidth else info.width
        val outH = if (outHeight > 0) outHeight else info.height
        val startUs = (trimStartSec * 1_000_000).toLong()
        val endUs = if (trimEndSec > 0) (trimEndSec * 1_000_000).toLong() else info.durationUs

        val decodedFrames = FrameDecoder.decodeRange(input, startUs, endUs, outW, outH)
        val orderedFrames = if (reversed) decodedFrames.asReversed() else decodedFrames
        // Re-timestamp sequentially at source frame spacing / speedFactor so
        // playback speed changes (and reverse) both come out with monotonic PTS.
        val frameIntervalUs = if (decodedFrames.size >= 2)
            (decodedFrames[1].second - decodedFrames[0].second).let { if (it > 0) it else 33_000L }
        else 33_000L
        val bitmapsWithDelay = orderedFrames.mapIndexed { i, (bmp, _) ->
            bmp to (i * (frameIntervalUs / speedFactor)).toLong()
        }

        val bitrate = if (videoBitrate > 0) videoBitrate else 4_000_000
        val fps = (1_000_000.0 / frameIntervalUs).roundToInt().coerceIn(10, 60)

        if (!keepAudio) {
            encodeBitmapSequenceUs(bitmapsWithDelay, outW, outH, output, bitrate, fps, progress)
        } else {
            val videoOnly = File(output.parentFile, "._vtmp_${output.name}")
            encodeBitmapSequenceUs(bitmapsWithDelay, outW, outH, videoOnly, bitrate, fps) { p, s -> progress(p / 2, s) }

            val audioOnly = File(output.parentFile, "._atmp_${output.name}.m4a")
            if (reversed) {
                AudioEngine.reverseEncode(input, audioOnly, trimStartSec, if (trimEndSec > 0) trimEndSec else info.durationUs / 1_000_000.0) { p, s -> progress(50 + p / 2, s) }
            } else {
                AudioEngine.speedAdjustPassthrough(input, audioOnly, speedFactor, trimStartSec, trimEndSec) { p, s -> progress(50 + p / 2, s) }
            }
            muxVideoAudio(videoOnly, audioOnly, output)
            videoOnly.delete(); audioOnly.delete()
        }
        decodedFrames.forEach { it.first.recycle() }
        progress(100, "DONE")
    }

    private fun muxVideoAudio(videoFile: File, audioFile: File, output: File) {
        val vExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val aExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vTrack = muxer.addTrack(vExtractor.getTrackFormat(0))
        val aTrack = muxer.addTrack(aExtractor.getTrackFormat(0))
        vExtractor.selectTrack(0); aExtractor.selectTrack(0)
        muxer.start()
        copyAllSamples(vExtractor, muxer, vTrack)
        copyAllSamples(aExtractor, muxer, aTrack)
        muxer.stop(); muxer.release(); vExtractor.release(); aExtractor.release()
    }

    private fun copyAllSamples(extractor: MediaExtractor, muxer: MediaMuxer, muxTrack: Int) {
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        val bufInfo = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            bufInfo.size = size
            bufInfo.presentationTimeUs = extractor.sampleTime
            bufInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(muxTrack, buffer, bufInfo)
            extractor.advance()
        }
    }

    fun encodeBitmapSequence(frames: List<Pair<Bitmap, Int>>, width: Int, height: Int, output: File, progress: ProgressCallback) {
        // frames: (bitmap, delayCentis) -> convert to running PTS in us
        var t = 0L
        val withUs = frames.map { (bmp, delayCentis) ->
            val cur = t
            t += delayCentis * 10_000L
            bmp to cur
        }
        val fps = if (frames.isNotEmpty()) (100.0 / frames[0].second.coerceAtLeast(1)).roundToInt().coerceIn(5, 30) else 12
        encodeBitmapSequenceUs(withUs, width, height, output, 2_500_000, fps, progress)
    }

    private fun encodeBitmapSequenceUs(
        frames: List<Pair<Bitmap, Long>>,
        width: Int,
        height: Int,
        output: File,
        bitrate: Int,
        fps: Int,
        progress: ProgressCallback
    ) {
        if (frames.isEmpty()) throw java.io.IOException("NO_FRAMES_TO_ENCODE")
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxerStarted = false
        val bufInfo = MediaCodec.BufferInfo()

        // Feed frames onto the encoder's input surface via software Canvas.
        for ((bmp, ptsUs) in frames) {
            val canvas = inputSurface.lockCanvas(null)
            try {
                val src = Rect(0, 0, bmp.width, bmp.height)
                val dst = Rect(0, 0, width, height)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bmp, src, dst, null)
            } finally {
                inputSurface.unlockCanvasAndPost(canvas)
            }
            drainEncoder(encoder, muxer, bufInfo) { track -> muxTrack = track; muxerStarted = true }
        }
        encoder.signalEndOfInputStream()
        drainEncoder(encoder, muxer, bufInfo, endOfStream = true) { track -> muxTrack = track; muxerStarted = true }

        encoder.stop(); encoder.release(); inputSurface.release()
        if (muxerStarted) { muxer.stop() }
        muxer.release()
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean = false,
        onTrackAdded: (Int) -> Unit
    ) {
        var muxTrack = -1
        var started = false
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                    onTrackAdded(muxTrack)
                }
                outIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if (encodedData != null && bufInfo.size > 0 && started) {
                        encodedData.position(bufInfo.offset)
                        encodedData.limit(bufInfo.offset + bufInfo.size)
                        muxer.writeSampleData(muxTrack, encodedData, bufInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}

/** Decodes a time range of a video into scaled Bitmaps via an ImageReader-backed decoder Surface. */
object FrameDecoder {

    fun decodeRange(input: File, startUs: Long, endUs: Long, outW: Int, outH: Int): List<Pair<Bitmap, Long>> {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var trackIdx = -1; var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { trackIdx = i; format = f; break }
        }
        if (trackIdx == -1 || format == null) { extractor.release(); return emptyList() }
        extractor.selectTrack(trackIdx)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val srcW = format.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = format.getInteger(MediaFormat.KEY_HEIGHT)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val reader = ImageReader.newInstance(srcW, srcH, ImageFormat.YUV_420_888, 4)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, reader.surface, null, 0)
        decoder.start()

        val results = ArrayList<Pair<Bitmap, Long>>()
        val bufInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    val sampleTime = extractor.sampleTime
                    if (sampleSize < 0 || sampleTime > endUs) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(bufInfo, 10_000)
            if (outIndex >= 0) {
                val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val render = bufInfo.size > 0 && bufInfo.presentationTimeUs in startUs..endUs
                decoder.releaseOutputBuffer(outIndex, render)
                if (render) {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val bmp = yuv420888ToBitmap(image, srcW, srcH)
                        image.close()
                        val scaled = if (outW != srcW || outH != srcH) Bitmap.createScaledBitmap(bmp, outW, outH, true) else bmp
                        if (scaled !== bmp) bmp.recycle()
                        results.add(scaled to bufInfo.presentationTimeUs)
                    }
                }
                if (eos) sawOutputEos = true
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && sawInputEos) {
                // give the decoder a final chance, then bail to avoid infinite loop
                if (results.isEmpty()) continue else break
            }
        }

        decoder.stop(); decoder.release()
        reader.close()
        extractor.release()
        return results
    }

    /** Minimal, dependency-free YUV_420_888 -> Bitmap conversion via JPEG re-encode of an NV21 buffer. */
    private fun yuv420888ToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val baos = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, baos)
        val bytes = baos.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + (width * height / 2))

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var pos = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }
}

// =========================================================================
// AudioEngine — PCM decode/encode helpers built purely on MediaCodec.
// =========================================================================
object AudioEngine {

    private data class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    /** Lossless forward cut via extractor->muxer sample copy (audio track only). */
    fun losslessTrim(input: File, output: File, startSec: Double, endSec: Double, progress: ProgressCallback) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var trackIdx = -1; var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIdx = i; format = f; break }
        }
        if (trackIdx == -1 || format == null) throw java.io.IOException("NO_AUDIO_TRACK")
        extractor.selectTrack(trackIdx)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxTrack = muxer.addTrack(format)
        muxer.start()

        val startUs = (startSec * 1_000_000).toLong()
        val endUs = (endSec * 1_000_000).toLong()
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buffer = ByteBuffer.allocateDirect(1 shl 18)
        val bufInfo = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            val pts = extractor.sampleTime
            if (size < 0 || pts > endUs) break
            bufInfo.size = size
            bufInfo.presentationTimeUs = (pts - startUs).coerceAtLeast(0)
            bufInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
            muxer.writeSampleData(muxTrack, buffer, bufInfo)
            progress((((pts - startUs) * 100) / (endUs - startUs).coerceAtLeast(1)).toInt().coerceIn(0, 99), "TRIMMING")
            extractor.advance()
        }
        muxer.stop(); muxer.release(); extractor.release()
        progress(100, "DONE")
    }

    /** Simplified speed adjustment: rescales sample PTS by 1/factor (passthrough, no pitch correction). */
    fun speedAdjustPassthrough(input: File, output: File, factor: Double, startSec: Double, endSec: Double, progress: ProgressCallback) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var trackIdx = -1; var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIdx = i; format = f; break }
        }
        if (trackIdx == -1 || format == null) throw java.io.IOException("NO_AUDIO_TRACK")
        extractor.selectTrack(trackIdx)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxTrack = muxer.addTrack(format)
        muxer.start()
        val startUs = (startSec * 1_000_000).toLong()
        val endUs = if (endSec > 0) (endSec * 1_000_000).toLong() else Long.MAX_VALUE
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buffer = ByteBuffer.allocateDirect(1 shl 18)
        val bufInfo = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            val pts = extractor.sampleTime
            if (size < 0 || pts > endUs) break
            bufInfo.size = size
            bufInfo.presentationTimeUs = ((pts - startUs).coerceAtLeast(0) / factor).toLong()
            bufInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
            muxer.writeSampleData(muxTrack, buffer, bufInfo)
            progress(50, "SPEED PASSTHROUGH")
            extractor.advance()
        }
        muxer.stop(); muxer.release(); extractor.release()
        progress(100, "DONE")
    }

    fun reverseEncode(input: File, output: File, startSec: Double, endSec: Double, progress: ProgressCallback) {
        val pcm = decodeToPcm(input, (startSec * 1_000_000).toLong(), (endSec * 1_000_000).toLong()) { p, s -> progress(p / 2, s) }
        reverseInterleaved(pcm.samples, pcm.channels)
        encodePcmToAac(pcm.samples, pcm.sampleRate, pcm.channels, output) { p, s -> progress(50 + p / 2, s) }
    }

    fun concatEncode(inputs: List<File>, output: File, progress: ProgressCallback) {
        var sampleRate = 44100; var channels = 2
        val chunks = ArrayList<ShortArray>()
        inputs.forEachIndexed { i, f ->
            val pcm = decodeToPcm(f, 0, Long.MAX_VALUE) { p, s -> progress(((i * 80) + p * 8 / 10) / inputs.size, s) }
            sampleRate = pcm.sampleRate; channels = pcm.channels
            chunks.add(pcm.samples)
        }
        val total = chunks.sumOf { it.size }
        val merged = ShortArray(total)
        var offset = 0
        for (c in chunks) { c.copyInto(merged, offset); offset += c.size }
        encodePcmToAac(merged, sampleRate, channels, output) { p, s -> progress(80 + p / 5, s) }
    }

    fun applyGain(input: File, output: File, gainDb: Double, progress: ProgressCallback) {
        val pcm = decodeToPcm(input, 0, Long.MAX_VALUE) { p, s -> progress(p / 2, s) }
        val multiplier = Math.pow(10.0, gainDb / 20.0)
        for (i in pcm.samples.indices) {
            // Anti-clipping limiter: mathematical clamp to the full 16-bit signed range.
            val boosted = (pcm.samples[i] * multiplier).roundToInt()
            pcm.samples[i] = boosted.coerceIn(-32768, 32767).toShort()
        }
        encodePcmToAac(pcm.samples, pcm.sampleRate, pcm.channels, output) { p, s -> progress(50 + p / 2, s) }
    }

    fun trimSilence(input: File, output: File, thresholdDb: Double, progress: ProgressCallback) {
        val pcm = decodeToPcm(input, 0, Long.MAX_VALUE) { p, s -> progress(p / 3, s) }
        val threshold = (32767 * Math.pow(10.0, thresholdDb / 20.0)).toInt()
        val frameSize = pcm.channels
        var startFrame = 0
        val totalFrames = pcm.samples.size / frameSize
        while (startFrame < totalFrames) {
            val amp = frameAmplitude(pcm.samples, startFrame, frameSize)
            if (amp > threshold) break
            startFrame++
        }
        var endFrame = totalFrames - 1
        while (endFrame > startFrame) {
            val amp = frameAmplitude(pcm.samples, endFrame, frameSize)
            if (amp > threshold) break
            endFrame--
        }
        val trimmed = pcm.samples.copyOfRange(startFrame * frameSize, (endFrame + 1) * frameSize)
        encodePcmToAac(trimmed, pcm.sampleRate, pcm.channels, output) { p, s -> progress(33 + (p * 2 / 3), s) }
    }

    private fun frameAmplitude(samples: ShortArray, frame: Int, channels: Int): Int {
        var maxAbs = 0
        for (c in 0 until channels) {
            val idx = frame * channels + c
            if (idx < samples.size) maxAbs = max(maxAbs, abs(samples[idx].toInt()))
        }
        return maxAbs
    }

    private fun reverseInterleaved(samples: ShortArray, channels: Int) {
        val frameCount = samples.size / channels
        var i = 0; var j = frameCount - 1
        val tmp = ShortArray(channels)
        while (i < j) {
            val iOff = i * channels; val jOff = j * channels
            for (c in 0 until channels) tmp[c] = samples[iOff + c]
            for (c in 0 until channels) samples[iOff + c] = samples[jOff + c]
            for (c in 0 until channels) samples[jOff + c] = tmp[c]
            i++; j--
        }
    }

    private fun decodeToPcm(input: File, startUs: Long, endUs: Long, progress: ProgressCallback): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var trackIdx = -1; var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIdx = i; format = f; break }
        }
        if (trackIdx == -1 || format == null) throw java.io.IOException("NO_AUDIO_TRACK")
        extractor.selectTrack(trackIdx)
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val out = java.io.ByteArrayOutputStream()
        val bufInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        val totalUs = (endUs - startUs).coerceAtLeast(1)

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    val pts = extractor.sampleTime
                    if (sampleSize < 0 || pts > endUs) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(bufInfo, 10_000)
            if (outIndex >= 0) {
                if (bufInfo.size > 0) {
                    val outBuf = decoder.getOutputBuffer(outIndex)!!
                    outBuf.position(bufInfo.offset)
                    outBuf.limit(bufInfo.offset + bufInfo.size)
                    val chunk = ByteArray(bufInfo.size)
                    outBuf.get(chunk)
                    out.write(chunk)
                    progress((((bufInfo.presentationTimeUs - startUs) * 100) / totalUs).toInt().coerceIn(0, 99), "DECODING PCM")
                }
                val eos = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                decoder.releaseOutputBuffer(outIndex, false)
                if (eos) sawOutputEos = true
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && sawInputEos) {
                break
            }
        }
        decoder.stop(); decoder.release(); extractor.release()

        val bytes = out.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return Pcm(shorts, sampleRate, channels)
    }

    private fun encodePcmToAac(samples: ShortArray, sampleRate: Int, channels: Int, output: File, progress: ProgressCallback) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxerStarted = false
        val bufInfo = MediaCodec.BufferInfo()

        val bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

        var offset = 0
        var ptsUs = 0L
        val bytesPerSampleFrame = 2 * channels
        val samplesPerChunk = 4096
        val chunkBytes = samplesPerChunk * bytesPerSampleFrame
        var sawInputEos = false

        while (true) {
            if (!sawInputEos) {
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = encoder.getInputBuffer(inIndex)!!
                    buf.clear()
                    val remaining = bytes.size - offset
                    if (remaining <= 0) {
                        encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        val toWrite = min(chunkBytes, remaining)
                        buf.put(bytes, offset, toWrite)
                        encoder.queueInputBuffer(inIndex, 0, toWrite, ptsUs, 0)
                        offset += toWrite
                        ptsUs += (toWrite / bytesPerSampleFrame) * 1_000_000L / sampleRate
                    }
                    progress(((offset * 100) / bytes.size.coerceAtLeast(1)), "ENCODING AAC")
                }
            }
            val outIndex = encoder.dequeueOutputBuffer(bufInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex != null && outIndex >= 0 -> {
                    val encoded = encoder.getOutputBuffer(outIndex)
                    if (encoded != null && bufInfo.size > 0 && muxerStarted) {
                        encoded.position(bufInfo.offset)
                        encoded.limit(bufInfo.offset + bufInfo.size)
                        muxer.writeSampleData(muxTrack, encoded, bufInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.stop(); encoder.release()
                        if (muxerStarted) muxer.stop()
                        muxer.release()
                        progress(100, "DONE")
                        return
                    }
                }
            }
        }
    }
}

// =========================================================================
// GifDecoder — pure Kotlin GIF87a/89a reader (frames + delays), no deps.
// =========================================================================
object GifDecoder {
    data class Frame(val bitmap: Bitmap, val delayCentis: Int)
    data class Decoded(val width: Int, val height: Int, val frames: List<Frame>)

    fun decode(file: File): Decoded {
        val bytes = file.readBytes()
        val r = ByteReader(bytes)
        val sig = r.readString(6)
        require(sig.startsWith("GIF")) { "NOT_A_GIF" }
        val width = r.readU16LE()
        val height = r.readU16LE()
        val packed = r.readU8()
        val gctFlag = (packed and 0x80) != 0
        val gctSize = 2 shl (packed and 0x07)
        r.readU8() // background color index
        r.readU8() // pixel aspect ratio
        val gct = if (gctFlag) readColorTable(r, gctSize) else IntArray(0)

        val frames = ArrayList<Frame>()
        var delayCentis = 10
        var transparentIndex = -1
        var canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        loop@ while (r.hasMore()) {
            when (val block = r.readU8()) {
                0x21 -> { // extension
                    val label = r.readU8()
                    if (label == 0xF9) {
                        val blockSize = r.readU8()
                        val flags = r.readU8()
                        delayCentis = r.readU16LE().let { if (it <= 0) 10 else it }
                        val ti = r.readU8()
                        transparentIndex = if (flags and 0x01 != 0) ti else -1
                        r.readU8() // terminator
                    } else {
                        skipSubBlocks(r)
                    }
                }
                0x2C -> { // image descriptor
                    val left = r.readU16LE(); val top = r.readU16LE()
                    val w = r.readU16LE(); val h = r.readU16LE()
                    val imgPacked = r.readU8()
                    val localGctFlag = (imgPacked and 0x80) != 0
                    val interlaced = (imgPacked and 0x40) != 0
                    val localGctSize = 2 shl (imgPacked and 0x07)
                    val palette = if (localGctFlag) readColorTable(r, localGctSize) else gct

                    val minCodeSize = r.readU8()
                    val data = readSubBlocksConcat(r)
                    val indices = LzwDecoder.decode(data, minCodeSize, w * h)

                    val frameBmp = Bitmap.createBitmap(canvas)
                    var idx = 0
                    if (interlaced) {
                        val passes = intArrayOf(0, 4, 2, 1)
                        val starts = intArrayOf(0, 4, 2, 1)
                        var srcRow = 0
                        for (passIdx in 0 until 4) {
                            var row = starts[passIdx]
                            while (row < h) {
                                writeRow(frameBmp, indices, idx, palette, transparentIndex, left, top + row, w)
                                idx += w
                                row += passes[passIdx].let { if (it == 0) 8 else it }
                            }
                        }
                    } else {
                        for (row in 0 until h) {
                            writeRow(frameBmp, indices, idx, palette, transparentIndex, left, top + row, w)
                            idx += w
                        }
                    }
                    frames.add(Frame(frameBmp, delayCentis))
                    canvas = Bitmap.createBitmap(frameBmp)
                    transparentIndex = -1
                }
                0x3B -> break@loop // trailer
                else -> break@loop
            }
        }
        return Decoded(width, height, frames)
    }

    private fun writeRow(bmp: Bitmap, indices: ByteArray, srcOffset: Int, palette: IntArray, transparentIndex: Int, left: Int, y: Int, w: Int) {
        if (y < 0 || y >= bmp.height) return
        for (x in 0 until w) {
            val srcIdx = srcOffset + x
            if (srcIdx >= indices.size) break
            val colorIndex = indices[srcIdx].toInt() and 0xFF
            if (colorIndex == transparentIndex) continue
            val px = left + x
            if (px < 0 || px >= bmp.width) continue
            val color = if (colorIndex < palette.size) palette[colorIndex] else 0
            bmp.setPixel(px, y, color or (0xFF shl 24))
        }
    }

    private fun readColorTable(r: ByteReader, size: Int): IntArray {
        val table = IntArray(size)
        for (i in 0 until size) {
            val red = r.readU8(); val green = r.readU8(); val blue = r.readU8()
            table[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return table
    }

    private fun skipSubBlocks(r: ByteReader) {
        while (true) {
            val size = r.readU8()
            if (size == 0) break
            r.skip(size)
        }
    }

    private fun readSubBlocksConcat(r: ByteReader): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val size = r.readU8()
            if (size == 0) break
            out.write(r.readBytes(size))
        }
        return out.toByteArray()
    }

    private class ByteReader(private val data: ByteArray) {
        var pos = 0
        fun hasMore() = pos < data.size
        fun readU8(): Int = data[pos++].toInt() and 0xFF
        fun readU16LE(): Int { val lo = readU8(); val hi = readU8(); return lo or (hi shl 8) }
        fun readString(n: Int): String { val s = String(data, pos, n, Charsets.US_ASCII); pos += n; return s }
        fun readBytes(n: Int): ByteArray { val b = data.copyOfRange(pos, pos + n); pos += n; return b }
        fun skip(n: Int) { pos += n }
    }

    /** Standard GIF LZW decoder (inverse of GifEncoder's LzwWriter). */
    object LzwDecoder {
        fun decode(data: ByteArray, minCodeSize: Int, expectedPixels: Int): ByteArray {
            val clearCode = 1 shl minCodeSize
            val endCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var dict = ArrayList<ByteArray>()

            fun resetDict() {
                dict = ArrayList(4096)
                for (i in 0 until clearCode) dict.add(byteArrayOf(i.toByte()))
                dict.add(ByteArray(0)) // clear code placeholder
                dict.add(ByteArray(0)) // end code placeholder
                codeSize = minCodeSize + 1
            }
            resetDict()

            val out = java.io.ByteArrayOutputStream(expectedPixels)
            var bitBuffer = 0L
            var bitCount = 0
            var bytePos = 0
            var prev: ByteArray? = null

            fun nextCode(): Int {
                while (bitCount < codeSize && bytePos < data.size) {
                    bitBuffer = bitBuffer or ((data[bytePos].toLong() and 0xFF) shl bitCount)
                    bytePos++
                    bitCount += 8
                }
                if (bitCount < codeSize) return endCode
                val code = (bitBuffer and ((1L shl codeSize) - 1)).toInt()
                bitBuffer = bitBuffer ushr codeSize
                bitCount -= codeSize
                return code
            }

            while (out.size() < expectedPixels) {
                val code = nextCode()
                if (code == clearCode) { resetDict(); prev = null; continue }
                if (code == endCode) break
                val entry: ByteArray = when {
                    code < dict.size && dict[code].isNotEmpty() -> dict[code]
                    code == dict.size && prev != null -> prev!! + prev!![0]
                    else -> break
                }
                out.write(entry)
                if (prev != null && dict.size < 4096) {
                    dict.add(prev!! + entry[0])
                    if (dict.size == (1 shl codeSize) && codeSize < 12) codeSize++
                }
                prev = entry
            }
            val result = out.toByteArray()
            return if (result.size >= expectedPixels) result.copyOf(expectedPixels) else result + ByteArray(expectedPixels - result.size)
        }
    }
}

// =========================================================================
// PdfCrypto — AES-256/CBC container encryption for the Lock/Unlock PDF engine.
// A random salt+IV is stored in a small header; the key is derived from the
// user password via PBKDF2 (javax.crypto only, no external PDF security lib).
// =========================================================================
object PdfCrypto {
    private const val MAGIC = "VR3TPDFLOCK"
    private const val ITERATIONS = 65536
    private const val KEY_LEN_BITS = 256

    fun encryptFile(input: File, output: File, password: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        FileOutputStream(output).use { fos ->
            fos.write(MAGIC.toByteArray(Charsets.US_ASCII))
            fos.write(salt)
            fos.write(iv)
            javax.crypto.CipherOutputStream(fos, cipher).use { cos ->
                input.inputStream().use { it.copyTo(cos, 64 * 1024) }
            }
        }
    }

    fun decryptFile(input: File, output: File, password: String): Boolean {
        return try {
            input.inputStream().use { fis ->
                val magic = ByteArray(MAGIC.length)
                if (fis.read(magic) != magic.size || String(magic, Charsets.US_ASCII) != MAGIC) return false
                val salt = ByteArray(16); fis.read(salt)
                val iv = ByteArray(16); fis.read(iv)
                val key = deriveKey(password, salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                FileOutputStream(output).use { fos ->
                    javax.crypto.CipherInputStream(fis, cipher).use { cis -> cis.copyTo(fos, 64 * 1024) }
                }
            }
            // sanity check: decrypted file should start with %PDF
            output.inputStream().use { it.read(ByteArray(4)) }
            val head = output.inputStream().use { val b = ByteArray(4); it.read(b); b }
            String(head, Charsets.US_ASCII) == "%PDF"
        } catch (e: Exception) {
            output.delete()
            false
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LEN_BITS)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
