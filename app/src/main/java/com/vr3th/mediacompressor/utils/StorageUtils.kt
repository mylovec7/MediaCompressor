package com.vr3th.mediacompressor.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vr3th.mediacompressor.data.MediaType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object StorageUtils {
    fun getOutputSubfolder(type: MediaType) = when (type) {
        MediaType.VIDEO -> "Video"
        MediaType.IMAGE -> "Image"
        MediaType.AUDIO -> "Audio"
        MediaType.GIF -> "GIF"
        MediaType.ARCHIVE -> "Archive"
        MediaType.DOCUMENT -> "Document"
        MediaType.UNKNOWN -> "Extract"
    }

    fun createTempFile(context: Context, prefix: String, extension: String): File {
        val cacheDir = File(context.cacheDir, "mc_temp")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File.createTempFile(prefix, extension, cacheDir)
    }

    fun clearCache(context: Context) {
        try {
            File(context.cacheDir, "mc_temp").takeIf { it.exists() }?.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun saveToMediaCompressorFolder(
        context: Context,
        tempFile: File,
        fileName: String,
        mediaType: MediaType,
        mimeType: String
    ): Uri? {
        if (!tempFile.isFile || tempFile.length() <= 0L) return null

        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/MediaCompressor/${getOutputSubfolder(mediaType)}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(tempFile).use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("Unable to open output stream.")

                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, done, null, null)
                return uri
            } catch (_: Exception) {
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                return null
            }
        }

        return try {
            val baseDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MediaCompressor/${getOutputSubfolder(mediaType)}"
            )
            if (!baseDir.exists() && !baseDir.mkdirs()) return null
            val destFile = File(baseDir, fileName)
            FileInputStream(tempFile).use { input ->
                FileOutputStream(destFile).use { out -> input.copyTo(out) }
            }
            if (destFile.length() != tempFile.length()) {
                destFile.delete()
                null
            } else {
                Uri.fromFile(destFile)
            }
        } catch (_: Exception) {
            null
        }
    }
}
