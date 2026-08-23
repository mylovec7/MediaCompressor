package com.vr3th.mediacompressor.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ToolsScreen(
    onTrimVideo: (Uri, Long, Long) -> Unit,
    onTrimAudio: (Uri, Long, Long) -> Unit,
    onReverseVideo: (Uri) -> Unit,
    onMuteVideo: (Uri) -> Unit,
    onMergeVideos: (List<Uri>) -> Unit,
    onMergePdfs: (List<Uri>) -> Unit,
    onSplitPdf: (Uri, Int, Int) -> Unit,
    onSplitPdfReverse: (Uri, Int, Int) -> Unit,
    onBatchImages: (List<Uri>) -> Unit,
    onRemoveExif: (Uri) -> Unit,
    onBack: () -> Unit
) {
    var start by remember { mutableStateOf("0") }
    var end by remember { mutableStateOf("10") }
    var pageStart by remember { mutableStateOf("1") }
    var pageEnd by remember { mutableStateOf("1") }
    var action by remember { mutableStateOf("") }

    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (action) {
            "VIDEO_TRIM" -> { val s=start.toLongOrNull(); val e=end.toLongOrNull(); if(s!=null&&e!=null) onTrimVideo(uri,s*1000,e*1000) }
            "AUDIO_TRIM" -> { val s=start.toLongOrNull(); val e=end.toLongOrNull(); if(s!=null&&e!=null) onTrimAudio(uri,s*1000,e*1000) }
            "REVERSE" -> onReverseVideo(uri)
            "MUTE" -> onMuteVideo(uri)
            "SPLIT" -> { val s=pageStart.toIntOrNull(); val e=pageEnd.toIntOrNull(); if(s!=null&&e!=null) onSplitPdf(uri,s,e) }
            "SPLIT_REVERSE" -> { val s=pageStart.toIntOrNull(); val e=pageEnd.toIntOrNull(); if(s!=null&&e!=null) onSplitPdfReverse(uri,s,e) }
            "EXIF" -> onRemoveExif(uri)
        }
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        when(action){ "MERGE_VIDEO"->onMergeVideos(uris); "MERGE_PDF"->onMergePdfs(uris); "BATCH_IMAGE"->onBatchImages(uris) }
    }
    fun single(mode:String, types:Array<String>){ action=mode; singlePicker.launch(types) }
    fun multi(mode:String, types:Array<String>){ action=mode; multiPicker.launch(types) }

    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Text("Media Tools", style=MaterialTheme.typography.titleLarge); Text("Native • Smart • Ultra Light", style=MaterialTheme.typography.bodyMedium) }
        item { OutlinedTextField(start,{start=it},Modifier.fillMaxWidth(),label={Text("Trim start (seconds)")}) }
        item { OutlinedTextField(end,{end=it},Modifier.fillMaxWidth(),label={Text("Trim end (seconds)")}) }
        item { OutlinedTextField(pageStart,{pageStart=it},Modifier.fillMaxWidth(),label={Text("PDF first page")}) }
        item { OutlinedTextField(pageEnd,{pageEnd=it},Modifier.fillMaxWidth(),label={Text("PDF last page")}) }
        item { Button({single("VIDEO_TRIM",arrayOf("video/*"))},Modifier.fillMaxWidth()){Text("Video Trim") } }
        item { Button({single("AUDIO_TRIM",arrayOf("audio/*"))},Modifier.fillMaxWidth()){Text("Audio Trim") } }
        item { Button({single("REVERSE",arrayOf("video/*"))},Modifier.fillMaxWidth()){Text("Reverse Video") } }
        item { Button({single("MUTE",arrayOf("video/*"))},Modifier.fillMaxWidth()){Text("Mute Video") } }
        item { Button({multi("MERGE_VIDEO",arrayOf("video/*"))},Modifier.fillMaxWidth()){Text("Merge Video") } }
        item { Button({multi("MERGE_PDF",arrayOf("application/pdf"))},Modifier.fillMaxWidth()){Text("Merge PDF") } }
        item { Button({single("SPLIT",arrayOf("application/pdf"))},Modifier.fillMaxWidth()){Text("Split PDF • Normal") } }
        item { Button({single("SPLIT_REVERSE",arrayOf("application/pdf"))},Modifier.fillMaxWidth()){Text("Split PDF • Reverse Order") } }
        item { Button({multi("BATCH_IMAGE",arrayOf("image/*"))},Modifier.fillMaxWidth()){Text("Batch Photo → ZIP") } }
        item { Button({single("EXIF",arrayOf("image/*"))},Modifier.fillMaxWidth()){Text("Remove EXIF / Metadata") } }
        item { OutlinedButton({onBack()},Modifier.fillMaxWidth()){Text("Back") } }
    }
}
