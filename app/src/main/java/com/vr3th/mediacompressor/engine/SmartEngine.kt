package com.vr3th.mediacompressor.engine

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.vr3th.mediacompressor.data.CompressionPlan
import com.vr3th.mediacompressor.data.MediaMetadataInfo

/** Small adaptive decision engine. */
class SmartEngine(private val context: Context) {
    fun analyzeVideo(uri: Uri): MediaMetadataInfo {
        val r = MediaMetadataRetriever()
        var width = 0; var height = 0; var duration = 0L; var bitrate = 0L; var rotation = 0
        var videoCodec = "Unknown"; var audioCodec: String? = null; var sampleRate = 0; var channels = 0; var fps = 30
        try {
            r.setDataSource(context, uri)
            width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {} finally { try { r.release() } catch (_: Exception) {} }
        val e = MediaExtractor()
        try {
            e.setDataSource(context, uri, null)
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoCodec = mime
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = f.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1,120)
                } else if (mime.startsWith("audio/")) {
                    audioCodec = mime
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } catch (_: Exception) {} finally { try { e.release() } catch (_: Exception) {} }
        var size = 0L; context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
        val hevc = isHardwareEncoderAvailable(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val avc = isHardwareEncoderAvailable(MediaFormat.MIMETYPE_VIDEO_AVC)
        val preferred = when { hevc -> MediaFormat.MIMETYPE_VIDEO_HEVC; avc -> MediaFormat.MIMETYPE_VIDEO_AVC; else -> MediaFormat.MIMETYPE_VIDEO_AVC }
        return MediaMetadataInfo(size, "MP4", videoCodec, audioCodec, width, height, fps, bitrate, duration, channels, sampleRate, rotation, hevc || avc, preferred)
    }

    fun makeVideoPlan(info: MediaMetadataInfo): CompressionPlan {
        val width = info.width.coerceAtLeast(2); val height = info.height.coerceAtLeast(2)
        val pixels = width.toLong() * height
        // Very small / already low bitrate: avoid unnecessary re-encode.
        if (info.bitrate in 1..500_000 && width <= 1280 && height <= 1280) {
            return CompressionPlan(true, info.videoCodec ?: MediaFormat.MIMETYPE_VIDEO_AVC, width and -2, height and -2,
                info.fps.coerceIn(1,60), info.bitrate.toInt(), 96_000, false,
                "Already efficiently compressed — using stream copy to preserve quality.")
        }
        val scale = when {
            pixels >= 8_000_000L -> 0.50f
            pixels >= 3_000_000L -> 0.67f
            pixels >= 2_000_000L -> 0.75f
            else -> 1.0f
        }
        val targetW = ((width * scale).toInt() and -2).coerceAtLeast(2)
        val targetH = ((height * scale).toInt() and -2).coerceAtLeast(2)
        val targetFps = info.fps.coerceIn(15, 30)
        val hevc = info.recommendedCodec == MediaFormat.MIMETYPE_VIDEO_HEVC
        val pixelsPerSecond = targetW.toLong() * targetH * targetFps
        val calculated = (pixelsPerSecond * if (hevc) 0.045 else 0.065).toLong()
        val targetBitrate = calculated.coerceIn(300_000L, if (targetW >= 1920) 4_000_000L else 2_500_000L).toInt()
        return CompressionPlan(false, info.recommendedCodec, targetW, targetH, targetFps, targetBitrate, 128_000,
            info.hasHardwareEncoder, "Adaptive real video compression: ${targetW}x${targetH} @ ${targetFps}fps, target ${targetBitrate/1000} kbps.")
    }

    private fun isHardwareEncoderAvailable(mime: String) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
        it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } && isHardwareCodec(it.name)
    }
    private fun isHardwareCodec(name: String): Boolean {
        val n = name.lowercase()
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.") && !n.contains("software") && !n.contains("sw")
    }
}
