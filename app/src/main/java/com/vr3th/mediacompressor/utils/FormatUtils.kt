package com.vr3th.mediacompressor.utils

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units=arrayOf("B","KB","MB","GB","TB")
        val digitGroups=(Math.log10(bytes.toDouble())/Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val df=DecimalFormat("#,##0.#")
        return "${df.format(bytes/Math.pow(1024.0,digitGroups.toDouble()))} ${units[digitGroups]}"
    }
    fun formatDuration(ms: Long): String {
        val seconds=(ms/1000)%60; val minutes=(ms/(1000*60))%60; val hours=ms/(1000*60*60)
        return if(hours>0) String.format(Locale.US,"%d:%02d:%02d",hours,minutes,seconds)
        else String.format(Locale.US,"%02d:%02d",minutes,seconds)
    }
    fun calculateReduction(original: Long, compressed: Long): String {
        if(original<=0 || compressed>=original) return "0%"
        return String.format(Locale.US,"-%.1f%%",((original-compressed).toDouble()/original)*100.0)
    }
    fun getCurrentDateFormatted(): String = SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date())
}
