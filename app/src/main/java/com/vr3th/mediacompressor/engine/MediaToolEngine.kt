package com.vr3th.mediacompressor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.*
import android.net.Uri
import com.vr3th.mediacompressor.data.ProcessResult
import com.vr3th.mediacompressor.utils.StorageUtils
import java.io.*
import java.nio.ByteBuffer
import kotlin.math.max

/** Lightweight platform-only media tools. No FFmpeg/Media3 payload is added. */
class MediaToolEngine(private val context: Context) {

    fun trimMedia(uri: Uri, originalName: String, startMs: Long, endMs: Long, video: Boolean): ProcessResult {
        val started = System.currentTimeMillis()
        var originalSize = 0L
        context.contentResolver.openFileDescriptor(uri, "r")?.use { originalSize = it.statSize }
        val out = StorageUtils.createTempFile(context, "trim_", ".mp4")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            require(startMs >= 0 && endMs > startMs) { "End time must be greater than start time." }
            extractor.setDataSource(context, uri, null)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    map[i] = muxer.addTrack(f)
                }
            }
            if (map.isEmpty()) throw IllegalStateException("No compatible audio/video track found.")
            muxer.start()
            map.keys.forEach { extractor.selectTrack(it) }
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buffer = ByteBuffer.allocateDirect(max(2 * 1024 * 1024, maxInputBufferForTracks(extractor, map.keys)))
            val info = MediaCodec.BufferInfo()
            var wrote = false
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                if (time < 0 || time > endMs * 1000L) break
                val outTrack = map[track]
                if (outTrack != null) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size > 0) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = max(0L, time - startMs * 1000L)
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(outTrack, buffer, info)
                        wrote = true
                    }
                }
                extractor.advance()
            }
            if (!wrote) throw IllegalStateException("No media samples found in selected range.")
            return ProcessResult(true, originalName, originalSize, out.length(), "Trim", "Trim ${startMs}ms → ${endMs}ms", 0, 0, if (video) "Video" else "Audio", "Stream Copy / Trim", System.currentTimeMillis() - started, out.absolutePath)
        } catch (e: Exception) {
            out.delete()
            return ProcessResult(false, originalName, originalSize, 0, "-", "-", 0, 0, "-", "-", 0, "", errorMessage = e.localizedMessage ?: "Trim failed")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    fun splitPdf(uri: Uri, originalName: String, startPage: Int, endPage: Int): ProcessResult {
        val started = System.currentTimeMillis()
        var originalSize = 0L
        context.contentResolver.openFileDescriptor(uri, "r")?.use { originalSize = it.statSize }
        val out = StorageUtils.createTempFile(context, "split_", ".pdf")
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        try {
            require(pfd != null) { "Cannot open PDF file." }
            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                require(startPage in 1..renderer.pageCount && endPage in startPage..renderer.pageCount) { "Invalid PDF page range." }
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    for (number in startPage..endPage) {
                        val page = renderer.openPage(number - 1)
                        try {
                            val w = page.width
                            val h = page.height
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val pi = android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, number - startPage + 1).create()
                                val outPage = doc.startPage(pi)
                                outPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                                doc.finishPage(outPage)
                            } finally { bitmap.recycle() }
                        } finally { page.close() }
                    }
                    FileOutputStream(out).use { doc.writeTo(it) }
                } finally { doc.close() }
            }
            return ProcessResult(true, originalName, originalSize, out.length(), "Pages ${startPage}-${endPage}", "${endPage - startPage + 1} Pages", 0, 0, "PDF", "PDF Split", System.currentTimeMillis() - started, out.absolutePath)
        } catch (e: Exception) {
            out.delete()
            return ProcessResult(false, originalName, originalSize, 0, "-", "-", 0, 0, "-", "-", 0, "", errorMessage = e.localizedMessage ?: "PDF split failed")
        } finally { try { pfd?.close() } catch (_: Exception) {} }
    }

    /** Reverse a video without external libraries. Output is video-only; original audio is intentionally not copied. */
    fun reverseVideo(uri: Uri, originalName: String): ProcessResult {
        val started = System.currentTimeMillis()
        var originalSize = 0L
        context.contentResolver.openFileDescriptor(uri, "r")?.use { originalSize = it.statSize }
        val retriever = MediaMetadataRetriever()
        val out = StorageUtils.createTempFile(context, "reverse_", ".mp4")
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: throw IllegalStateException("Unknown video width")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: throw IllegalStateException("Unknown video height")
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: throw IllegalStateException("Unknown video duration")
            val fps = run {
                var detected = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                if (detected == null) {
                    val probe = MediaExtractor()
                    try {
                        probe.setDataSource(context, uri, null)
                        for (t in 0 until probe.trackCount) {
                            val f = probe.getTrackFormat(t)
                            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                detected = f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat(); break
                            }
                        }
                    } finally { try { probe.release() } catch (_: Exception) {} }
                }
                (detected ?: 30f).coerceIn(1f, 60f)
            }
            val safeW = width and 1.inv()
            val safeH = height and 1.inv()
            val encInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } && info.name.let { n -> !n.startsWith("OMX.google.") && !n.startsWith("c2.android.") }
            } ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                ?: throw IllegalStateException("No AVC video encoder available.")
            val caps = encInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val color = caps.colorFormats.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar }
                ?: caps.colorFormats.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar }
                ?: throw IllegalStateException("Encoder has no compatible YUV420 input.")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, safeW, safeH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(MediaFormat.KEY_BIT_RATE, (safeW.toLong() * safeH * fps * 0.08f).toInt().coerceIn(300_000, 8_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceIn(1, 60))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createByCodecName(encInfo.name)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxTrack = -1
            val bufferInfo = MediaCodec.BufferInfo()
            val frameIntervalUs = (1_000_000.0 / fps.toDouble()).toLong().coerceAtLeast(1L)
            val totalFrames = ((durationMs * 1000L + frameIntervalUs - 1L) / frameIntervalUs)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            var fed = 0
            for (i in totalFrames - 1 downTo 0) {
                val timeUs = (i.toLong() * frameIntervalUs).coerceAtMost((durationMs * 1000L - 1L).coerceAtLeast(0L))
                val frame = decodeFrameSafely(retriever, timeUs, durationMs * 1000L)
                    ?: continue
                val bitmap = if (frame.width != safeW || frame.height != safeH) Bitmap.createScaledBitmap(frame, safeW, safeH, true) else frame
                try {
                    var inputIndex = encoder.dequeueInputBuffer(20_000)
                    while (inputIndex < 0) {
                        muxTrack = drainEncoder(encoder, muxer, bufferInfo, muxTrack)
                        inputIndex = encoder.dequeueInputBuffer(20_000)
                    }
                    val input = encoder.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Reverse encoder input unavailable")
                    input.clear()
                    val frameBytes = safeW * safeH * 3 / 2
                    require(input.capacity() >= frameBytes) { "Encoder input buffer is too small for ${safeW}x${safeH}." }
                    writeBitmapYuv420(bitmap, input, color, safeW, safeH)
                    encoder.queueInputBuffer(inputIndex, 0, frameBytes, fed * frameIntervalUs, 0)
                    fed++
                    muxTrack = drainEncoder(encoder, muxer, bufferInfo, muxTrack)
                } finally { if (bitmap !== frame) bitmap.recycle(); frame.recycle() }
            }
            var eosQueued = false
            while (!eosQueued) {
                val eosIndex = encoder.dequeueInputBuffer(20_000)
                if (eosIndex >= 0) {
                    encoder.queueInputBuffer(eosIndex, 0, 0, fed * frameIntervalUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                } else {
                    muxTrack = drainEncoder(encoder, muxer, bufferInfo, muxTrack)
                }
            }
            var done = false
            var idleLoops = 0
            while (!done && idleLoops < 250) {
                val status = encoder.dequeueOutputBuffer(bufferInfo, 20_000)
                when {
                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { if (muxTrack < 0) { muxTrack = muxer.addTrack(encoder.outputFormat); muxer.start() } }
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> idleLoops++
                    status >= 0 -> {
                        idleLoops = 0
                        val b = encoder.getOutputBuffer(status)
                        if (b != null && bufferInfo.size > 0 && muxTrack >= 0) {
                            val start = bufferInfo.offset.coerceAtLeast(0)
                            val end = (start + bufferInfo.size).coerceAtMost(b.capacity())
                            if (end > start) { b.position(start); b.limit(end); muxer.writeSampleData(muxTrack, b, bufferInfo) }
                        }
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(status, false)
                        if (eos) done = true
                    }
                }
            }
            if (!done) throw IllegalStateException("Reverse encoder timed out while draining output.")
            if (muxTrack < 0) throw IllegalStateException("Reverse encoder produced no output.")
            return ProcessResult(true, originalName, originalSize, out.length(), "${width}x${height}", "${safeW}x${safeH}", fps.toInt(), fps.toInt(), "Video", "H.264 Reverse (video only)", System.currentTimeMillis() - started, out.absolutePath)
        } catch (e: Exception) {
            out.delete()
            return ProcessResult(false, originalName, originalSize, 0, "-", "-", 0, 0, "-", "-", 0, "", errorMessage = e.localizedMessage ?: "Video reverse failed")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(codec: MediaCodec, muxer: MediaMuxer, info: MediaCodec.BufferInfo, currentTrack: Int): Int {
        var track = currentTrack
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return track
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (track < 0) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                }
                index >= 0 -> {
                    val b = codec.getOutputBuffer(index)
                    if (b != null && info.size > 0 && track >= 0) {
                        val start = info.offset.coerceAtLeast(0)
                        val end = (start + info.size).coerceAtMost(b.capacity())
                        if (end > start) {
                            b.position(start)
                            b.limit(end)
                            muxer.writeSampleData(track, b, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun writeBitmapYuv420(bitmap: Bitmap, out: ByteBuffer, colorFormat: Int, width: Int, height: Int) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ySize = width * height
        val yuv = ByteArray(ySize + ySize / 2)
        var y = 0; var u = 0; var v = 0
        val uvBase = ySize
        for (row in 0 until height) for (col in 0 until width) {
            val c = pixels[row * width + col]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            yuv[y++] = yy.coerceIn(0, 255).toByte()
            if (row % 2 == 0 && col % 2 == 0) {
                val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    yuv[uvBase + u++] = uu.coerceIn(0, 255).toByte()
                    yuv[uvBase + (ySize / 4) + v++] = vv.coerceIn(0, 255).toByte()
                } else {
                    val pos = uvBase + (row / 2) * width + col
                    yuv[pos] = uu.coerceIn(0, 255).toByte()
                    yuv[pos + 1] = vv.coerceIn(0, 255).toByte()
                }
            }
        }
        out.put(yuv)
    }

    fun muteVideo(uri: Uri, originalName: String): ProcessResult {
        val started = System.currentTimeMillis(); val originalSize = fileSize(uri)
        val out = StorageUtils.createTempFile(context, "mute_", ".mp4")
        val e = MediaExtractor(); var muxer: MediaMuxer? = null
        return try {
            e.setDataSource(context, uri, null); muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var video = -1
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) { video = i; break }
            }
            require(video >= 0) { "No video track found." }
            val outTrack = muxer.addTrack(e.getTrackFormat(video)); muxer.start(); e.selectTrack(video)
            copyTrack(e, muxer, outTrack)
            ProcessResult(true, originalName, originalSize, out.length(), "Video + Audio", "Video only", 0, 0, "Video", "Mute / Video Stream Copy", System.currentTimeMillis()-started, out.absolutePath)
        } catch (ex: Exception) {
            out.delete(); ProcessResult(false, originalName, originalSize, 0, "-", "-", 0, 0, "-", "-", 0, "", errorMessage = ex.localizedMessage ?: "Mute failed")
        } finally { try { e.release() } catch (_: Exception) {}; try { muxer?.stop(); muxer?.release() } catch (_: Exception) {} }
    }

    fun mergeVideos(uris: List<Uri>, originalName: String): ProcessResult {
        require(uris.size >= 2) { "Select at least two videos." }
        val started = System.currentTimeMillis(); val originalSize = uris.sumOf { fileSize(it) }
        val out = StorageUtils.createTempFile(context, "merge_", ".mp4")
        var muxer: MediaMuxer? = null
        val extractors = mutableListOf<MediaExtractor>()
        return try {
            val first = MediaExtractor(); first.setDataSource(context, uris[0], null); extractors += first
            val baseTracks = (0 until first.trackCount).map { first.getTrackFormat(it) }.filter { (it.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/") || (it.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/") }
            require(baseTracks.isNotEmpty()) { "No compatible tracks in first video." }
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTracks = baseTracks.map { muxer.addTrack(it) }
            muxer.start()
            var offsets = LongArray(baseTracks.size)
            val buffer = ByteBuffer.allocateDirect(max(2 * 1024 * 1024, maxInputBufferForTracks(first, 0 until first.trackCount))); val bi = MediaCodec.BufferInfo()
            for ((index, uri) in uris.withIndex()) {
                val e = if (index == 0) first else MediaExtractor().also { it.setDataSource(context, uri, null); extractors += it }
                val current = (0 until e.trackCount).filter {
                    val m = e.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: ""; m.startsWith("video/") || m.startsWith("audio/")
                }
                require(current.size == baseTracks.size) { "Videos are not stream-compatible." }
                for (j in current.indices) {
                    val f = e.getTrackFormat(current[j])
                    require(compatibleTrackFormats(baseTracks[j], f)) { "Video/audio stream formats differ; merge requires compatible streams." }
                    e.selectTrack(current[j])
                }
                val lastTimes = LongArray(current.size)
                while (true) {
                    val ti = e.sampleTrackIndex; if (ti < 0) break
                    val pos = current.indexOf(ti); if (pos >= 0) {
                        val sampleTime = e.sampleTime
                        lastTimes[pos] = maxOf(lastTimes[pos], sampleTime)
                        buffer.clear(); val n = e.readSampleData(buffer, 0)
                        if (n > 0) { bi.offset=0; bi.size=n; bi.presentationTimeUs=sampleTime+offsets[pos]; bi.flags=e.sampleFlags; muxer.writeSampleData(outTracks[pos], buffer, bi) }
                    }
                    e.advance()
                }
                for (j in current.indices) {
                    val duration = e.getTrackFormat(current[j]).getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }
                        ?: (lastTimes[j] + 1L)
                    offsets[j] += duration
                }
            }
            ProcessResult(true, originalName, originalSize, out.length(), "${uris.size} Videos", "Merged", 0, 0, "Compatible Streams", "Video Merge / Stream Copy", System.currentTimeMillis()-started, out.absolutePath)
        } catch (ex: Exception) {
            out.delete(); ProcessResult(false, originalName, originalSize, 0, "-", "-", 0, 0, "-", "-", 0, "", errorMessage = ex.localizedMessage ?: "Video merge failed")
        } finally { extractors.forEach { try { it.release() } catch (_: Exception) {} }; try { muxer?.stop(); muxer?.release() } catch (_: Exception) {} }
    }

    fun mergePdfs(uris: List<Uri>, originalName: String): ProcessResult {
        require(uris.size >= 2) { "Select at least two PDF files." }
        val started=System.currentTimeMillis(); val originalSize=uris.sumOf{fileSize(it)}; val out=StorageUtils.createTempFile(context,"merge_pdf_",".pdf")
        val doc=android.graphics.pdf.PdfDocument()
        return try {
            var pageNo=1
            for(uri in uris){
                val pfd=context.contentResolver.openFileDescriptor(uri,"r") ?: continue
                android.graphics.pdf.PdfRenderer(pfd).use{renderer->
                    for(i in 0 until renderer.pageCount){
                        val page=renderer.openPage(i); val w=page.width; val h=page.height; val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                        try{page.render(bmp,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT);val pi=android.graphics.pdf.PdfDocument.PageInfo.Builder(w,h,pageNo++).create();val np=doc.startPage(pi);np.canvas.drawBitmap(bmp,0f,0f,null);doc.finishPage(np)}finally{bmp.recycle();page.close()}
                    }
                };pfd.close()
            }
            FileOutputStream(out).use{doc.writeTo(it)};doc.close()
            ProcessResult(true,originalName,originalSize,out.length(),"${uris.size} PDFs","Merged PDF",0,0,"PDF","PDF Merge",System.currentTimeMillis()-started,out.absolutePath)
        }catch(ex:Exception){try{doc.close()}catch(_:Exception){};out.delete();ProcessResult(false,originalName,originalSize,0,"-","-",0,0,"-","-",0,"",errorMessage=ex.localizedMessage?:"PDF merge failed")}
    }

    fun splitPdfReverse(uri: Uri, originalName: String, startPage: Int, endPage: Int): ProcessResult {
        val started=System.currentTimeMillis(); val originalSize=fileSize(uri); val out=StorageUtils.createTempFile(context,"split_rev_",".pdf"); val pfd=context.contentResolver.openFileDescriptor(uri,"r")
        return try{require(pfd!=null){"Cannot open PDF file."};android.graphics.pdf.PdfRenderer(pfd).use{renderer->require(startPage in 1..renderer.pageCount&&endPage in startPage..renderer.pageCount){"Invalid PDF page range."};val doc=android.graphics.pdf.PdfDocument();try{for(number in endPage downTo startPage){val page=renderer.openPage(number-1);val w=page.width;val h=page.height;val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);try{page.render(bmp,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT);val pi=android.graphics.pdf.PdfDocument.PageInfo.Builder(w,h,endPage-number+1).create();val np=doc.startPage(pi);np.canvas.drawBitmap(bmp,0f,0f,null);doc.finishPage(np)}finally{bmp.recycle();page.close()}};FileOutputStream(out).use{doc.writeTo(it)}}finally{doc.close()}};ProcessResult(true,originalName,originalSize,out.length(),"Pages ${startPage}-${endPage}","${endPage-startPage+1} Pages (Reverse)",0,0,"PDF","PDF Split / Reverse Order",System.currentTimeMillis()-started,out.absolutePath)}catch(ex:Exception){out.delete();ProcessResult(false,originalName,originalSize,0,"-","-",0,0,"-","-",0,"",errorMessage=ex.localizedMessage?:"Reverse PDF split failed")}finally{try{pfd?.close()}catch(_:Exception){}}
    }

    fun batchCompressImages(uris: List<Uri>, originalName: String): ProcessResult {
        val started = System.currentTimeMillis()
        require(uris.isNotEmpty()) { "Select at least one image." }
        val totalOriginal = uris.sumOf { fileSize(it) }
        val out = StorageUtils.createTempFile(context, "batch_img_", ".zip")
        return try {
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(FileOutputStream(out))).use { zip ->
                zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
                uris.forEachIndexed { index, uri ->
                    val name = (uri.lastPathSegment ?: "image_$index").substringAfterLast('/').ifBlank { "image_$index" }
                    val tmp = StorageUtils.createTempFile(context, "batch_one_", ".jpg")
                    try {
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                            ?: throw IllegalStateException("Unable to decode $name")
                        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 84, it) }
                        bitmap.recycle()
                        zip.putNextEntry(java.util.zip.ZipEntry(name.substringBeforeLast('.') + ".jpg"))
                        tmp.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    } finally { tmp.delete() }
                }
            }
            ProcessResult(true, originalName, totalOriginal, out.length(), "${uris.size} Images", "Batch JPEG ZIP", 0, 0, "Images", "Batch Compress / ZIP", System.currentTimeMillis()-started, out.absolutePath)
        } catch (e: Exception) {
            out.delete()
            ProcessResult(false, originalName, totalOriginal, 0, "-", "-", 0, 0, "-", "-", 0, "", errorMessage=e.localizedMessage ?: "Batch image compression failed")
        }
    }

    fun removeExif(uri: Uri, originalName: String): ProcessResult {
        val started = System.currentTimeMillis()
        val originalSize = fileSize(uri)
        val out = StorageUtils.createTempFile(context, "no_exif_", ".jpg")
        return try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: throw IllegalStateException("Unable to decode image.")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            ProcessResult(true, originalName, originalSize, out.length(), "Image", "Image", 0, 0, "Original metadata", "JPEG / Metadata Removed", System.currentTimeMillis()-started, out.absolutePath)
        } catch (e: Exception) {
            out.delete()
            ProcessResult(false, originalName, originalSize, 0, "-", "-", 0, 0, "-", "-", 0, "", errorMessage=e.localizedMessage ?: "EXIF removal failed")
        }
    }

    private fun maxInputBufferForTracks(extractor: MediaExtractor, tracks: Collection<Int>): Int {
        var maxSize = 0
        for (i in tracks) maxSize = max(maxSize, (if (extractor.getTrackFormat(i).containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) extractor.getTrackFormat(i).getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0))
        return maxSize.coerceAtMost(16 * 1024 * 1024)
    }

    private fun decodeFrameSafely(retriever: MediaMetadataRetriever, timeUs: Long, durationUs: Long): Bitmap? {
        val safe = timeUs.coerceIn(0L, (durationUs - 1L).coerceAtLeast(0L))
        val options = intArrayOf(MediaMetadataRetriever.OPTION_CLOSEST, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
        for (option in options) {
            try { retriever.getFrameAtTime(safe, option)?.let { return it } } catch (_: RuntimeException) {}
        }
        return null
    }

    private fun fileSize(uri: Uri): Long = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    private fun compatibleTrackFormats(a: MediaFormat, b: MediaFormat): Boolean {
        val ma = a.getString(MediaFormat.KEY_MIME) ?: return false
        val mb = b.getString(MediaFormat.KEY_MIME) ?: return false
        if (!ma.equals(mb, ignoreCase = true)) return false
        val keys = if (ma.startsWith("video/")) {
            listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_FRAME_RATE, MediaFormat.KEY_COLOR_FORMAT)
        } else {
            listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)
        }
        return keys.all { key ->
            if (!a.containsKey(key) || !b.containsKey(key)) true
            else a.getInteger(key) == b.getInteger(key)
        }
    }

    private fun copyTrack(e: MediaExtractor, muxer: MediaMuxer, outTrack: Int) { val selected = (0 until e.trackCount).filter { try { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } catch (_: Exception) { false } }; val maxSize = selected.maxOfOrNull { (if (e.getTrackFormat(it).containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) e.getTrackFormat(it).getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0) } ?: 0; val buffer=ByteBuffer.allocateDirect(max(2*1024*1024, maxSize));val bi=MediaCodec.BufferInfo();while(true){val ti=e.sampleTrackIndex;if(ti<0)break;buffer.clear();val n=e.readSampleData(buffer,0);if(n>0){bi.offset=0;bi.size=n;bi.presentationTimeUs=e.sampleTime;bi.flags=e.sampleFlags;muxer.writeSampleData(outTrack,buffer,bi)};e.advance()} }

}
