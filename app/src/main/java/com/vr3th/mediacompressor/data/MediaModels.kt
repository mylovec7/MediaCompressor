package com.vr3th.mediacompressor.data

import android.net.Uri

enum class MediaType { VIDEO, IMAGE, AUDIO, GIF, ARCHIVE, DOCUMENT, UNKNOWN }

data class MediaItem(val uri: Uri, val name: String, val size: Long, val mimeType: String, val type: MediaType)
data class MediaMetadataInfo(
    val size: Long, val container: String, val videoCodec: String? = null, val audioCodec: String? = null,
    val width: Int = 0, val height: Int = 0, val fps: Int = 0, val bitrate: Long = 0, val durationMs: Long = 0,
    val audioChannels: Int = 0, val sampleRate: Int = 0, val rotation: Int = 0,
    val hasHardwareEncoder: Boolean = false, val recommendedCodec: String = "AVC"
)
data class CompressionPlan(
    val shouldRemux: Boolean, val targetCodec: String, val targetWidth: Int, val targetHeight: Int,
    val targetFps: Int, val targetBitrate: Int, val targetAudioBitrate: Int,
    val isHardwareAccelerated: Boolean, val reason: String
)
data class ProcessStatus(
    val stepText: String = "Analyzing...", val progress: Float = 0f, val encoderUsed: String = "Auto",
    val isHardware: Boolean = false, val isDone: Boolean = false, val error: String? = null
)
data class ProcessResult(
    val isSuccess: Boolean, val originalName: String, val originalSize: Long, val compressedSize: Long,
    val originalRes: String, val outputRes: String, val originalFps: Int, val outputFps: Int,
    val originalCodec: String, val outputCodec: String, val processingTimeMs: Long, val outputPath: String,
    val outputUri: Uri? = null, val errorMessage: String? = null
)
data class HistoryItem(
    val id: Long = System.currentTimeMillis(), val fileName: String, val originalSize: Long,
    val compressedSize: Long, val dateText: String, val outputPath: String, val mediaType: MediaType
)
