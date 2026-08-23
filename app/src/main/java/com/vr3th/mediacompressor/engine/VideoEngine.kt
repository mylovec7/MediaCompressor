package com.vr3th.mediacompressor.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import com.vr3th.mediacompressor.data.*
import com.vr3th.mediacompressor.utils.StorageUtils
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/** Android-native video engine. No FFmpeg/Media3 payload. */
class VideoEngine(private val context: Context) {

    fun processVideo(
        uri: Uri,
        info: MediaMetadataInfo,
        plan: CompressionPlan,
        originalName: String,
        onProgress: (ProcessStatus) -> Unit = {}
    ): ProcessResult {
        return if (plan.shouldRemux) {
            remuxOrDirectCopy(uri, info, originalName, onProgress)
        } else {
            compressNative(uri, info, plan, originalName, onProgress)
        }
    }

    fun remuxOrDirectCopy(
        uri: Uri,
        originalInfo: MediaMetadataInfo,
        originalName: String,
        onProgress: (ProcessStatus) -> Unit
    ): ProcessResult {
        val start = System.currentTimeMillis()
        val out = StorageUtils.createTempFile(context, "remux_", ".mp4")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    map[i] = muxer.addTrack(format)
                    extractor.selectTrack(i)
                }
            }
            if (map.isEmpty()) throw IllegalStateException("No compatible media tracks found.")
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val outTrack = map[track]
                if (outTrack != null) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size > 0) {
                        bi.offset = 0; bi.size = size; bi.presentationTimeUs = extractor.sampleTime; bi.flags = extractor.sampleFlags
                        muxer.writeSampleData(outTrack, buffer, bi)
                    }
                }
                extractor.advance()
            }
            return ProcessResult(true, originalName, originalInfo.size, out.length(),
                "${originalInfo.width}x${originalInfo.height}", "${originalInfo.width}x${originalInfo.height}",
                originalInfo.fps, originalInfo.fps, originalInfo.videoCodec ?: "Unknown", "Stream Copy / Remux",
                System.currentTimeMillis() - start, out.absolutePath)
        } catch (e: Exception) {
            out.delete()
            return failed(originalName, originalInfo, e.localizedMessage ?: "Remux failed")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Real video re-encode using Android's MediaCodec encoder and MediaMetadataRetriever frames.
     * This deliberately avoids FFmpeg/Media3 so APK size stays small. Audio is stream-copied.
     */
    private fun compressNative(
        uri: Uri,
        info: MediaMetadataInfo,
        plan: CompressionPlan,
        originalName: String,
        onProgress: (ProcessStatus) -> Unit
    ): ProcessResult {
        val start = System.currentTimeMillis()
        val out = StorageUtils.createTempFile(context, "video_", ".mp4")
        val retriever = MediaMetadataRetriever()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        try {
            retriever.setDataSource(context, uri)
            val width = plan.targetWidth.coerceAtLeast(2) and -2
            val height = plan.targetHeight.coerceAtLeast(2) and -2
            val fps = plan.targetFps.coerceIn(1, 60)
            val codec = MediaCodec.createEncoderByType(plan.targetCodec)
            encoder = codec
            val caps = codec.codecInfo.getCapabilitiesForType(plan.targetCodec)
            val color = caps.colorFormats.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar }
                ?: caps.colorFormats.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar }
                ?: throw IllegalStateException("No compatible YUV420 encoder format available.")
            val format = MediaFormat.createVideoFormat(plan.targetCodec, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.targetBitrate.coerceAtLeast(120_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val audioInfo = findAudioTrack(uri)
            val durationUs = info.durationMs.coerceAtLeast(1) * 1000L
            val intervalUs = 1_000_000L / fps
            val total = (durationUs / intervalUs).toInt().coerceAtLeast(1)
            val bi = MediaCodec.BufferInfo()
            var frameIndex = 0
            var formatReady = false
            fun drain(flush: Boolean = false): Boolean {
                var eos = false
                while (true) {
                    when (val status = codec.dequeueOutputBuffer(bi, if (flush) 20_000 else 0)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> return eos
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!formatReady) {
                                muxTrack = muxer.addTrack(codec.outputFormat)
                                if (audioInfo != null) audioTrack = muxer.addTrack(audioInfo.second)
                                muxer.start(); muxerStarted = true; formatReady = true
                            }
                        }
                        else -> if (status >= 0) {
                            val buffer = codec.getOutputBuffer(status)
                            if (buffer != null && bi.size > 0 && muxerStarted) {
                                buffer.position(bi.offset); buffer.limit(bi.offset + bi.size)
                                muxer.writeSampleData(muxTrack, buffer, bi)
                            }
                            eos = (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(status, false)
                            if (eos) return true
                        }
                    }
                }
            }
            while (frameIndex < total) {
                val inputIndex = codec.dequeueInputBuffer(20_000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: throw IllegalStateException("Encoder input unavailable")
                    input.clear()
                    val timeUs = (frameIndex * intervalUs).coerceAtMost(durationUs - 1)
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: throw IllegalStateException("Unable to decode video frame at ${timeUs / 1000} ms")
                    val scaled = if (frame.width != width || frame.height != height) Bitmap.createScaledBitmap(frame, width, height, true) else frame
                    try {
                        writeBitmapYuv420(scaled, input, color, width, height)
                        codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, timeUs, 0)
                    } finally {
                        if (scaled !== frame) scaled.recycle()
                        frame.recycle()
                    }
                    frameIndex++
                    if (frameIndex % max(1, total / 20) == 0) onProgress(ProcessStatus("Encoding video ${frameIndex * 100 / total}%", frameIndex.toFloat() / total, plan.targetCodec, info.hasHardwareEncoder))
                }
                drain()
            }
            val eosIndex = codec.dequeueInputBuffer(20_000)
            if (eosIndex >= 0) codec.queueInputBuffer(eosIndex, 0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var ended = false
            while (!ended) ended = drain(true)
            // Copy original audio after video encoding. No audio re-encode or extra library required.
            if (muxerStarted && audioInfo != null && audioTrack >= 0) copyAudio(uri, audioInfo.first, muxer, audioTrack)
            if (!muxerStarted) throw IllegalStateException("Encoder produced no output.")
            return ProcessResult(true, originalName, info.size, out.length(),
                "${info.width}x${info.height}", "${width}x${height}", info.fps, fps,
                info.videoCodec ?: "Unknown", "${plan.targetCodec} / Adaptive Native Encode + Audio Copy",
                System.currentTimeMillis() - start, out.absolutePath)
        } catch (e: Exception) {
            out.delete()
            return failed(originalName, info, e.localizedMessage ?: "Video compression failed")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun findAudioTrack(uri: Uri): Pair<Int, MediaFormat>? {
        val e = MediaExtractor()
        return try {
            e.setDataSource(context, uri, null)
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) return i to f
            }
            null
        } finally { try { e.release() } catch (_: Exception) {} }
    }

    private fun copyAudio(uri: Uri, trackIndex: Int, muxer: MediaMuxer, outTrack: Int) {
        val e = MediaExtractor()
        try {
            e.setDataSource(context, uri, null); e.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocateDirect(512 * 1024); val bi = MediaCodec.BufferInfo()
            while (true) {
                val t = e.sampleTrackIndex; if (t < 0) break
                buffer.clear(); val n = e.readSampleData(buffer, 0)
                if (n > 0) { bi.offset = 0; bi.size = n; bi.presentationTimeUs = e.sampleTime; bi.flags = e.sampleFlags; muxer.writeSampleData(outTrack, buffer, bi) }
                e.advance()
            }
        } finally { try { e.release() } catch (_: Exception) {} }
    }

    private fun writeBitmapYuv420(bitmap: Bitmap, out: ByteBuffer, colorFormat: Int, width: Int, height: Int) {
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ySize = width * height; val yuv = ByteArray(ySize + ySize / 2); var y = 0; var u = 0; var v = 0
        for (row in 0 until height) for (col in 0 until width) {
            val c = pixels[row * width + col]; val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            yuv[y++] = (((66*r + 129*g + 25*b + 128) shr 8) + 16).coerceIn(0,255).toByte()
            if (row % 2 == 0 && col % 2 == 0) {
                val uu = ((-38*r - 74*g + 112*b + 128) shr 8) + 128
                val vv = ((112*r - 94*g - 18*b + 128) shr 8) + 128
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    yuv[ySize + u++] = uu.coerceIn(0,255).toByte(); yuv[ySize + ySize/4 + v++] = vv.coerceIn(0,255).toByte()
                } else {
                    val pos = ySize + (row/2)*width + col; yuv[pos] = uu.coerceIn(0,255).toByte(); yuv[pos+1] = vv.coerceIn(0,255).toByte()
                }
            }
        }
        out.put(yuv)
    }

    private fun failed(name: String, info: MediaMetadataInfo, message: String) = ProcessResult(
        false, name, info.size, 0, "${info.width}x${info.height}", "-", info.fps, 0,
        info.videoCodec ?: "Unknown", "-", 0, "", errorMessage = message
    )
}
