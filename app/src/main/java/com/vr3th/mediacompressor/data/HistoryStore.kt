package com.vr3th.mediacompressor.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class HistoryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mc_history_prefs", Context.MODE_PRIVATE)
    fun saveItem(item: HistoryItem) {
        val list = getHistory().toMutableList(); list.add(0, item)
        if (list.size > 100) list.removeAt(list.size - 1); saveList(list)
    }
    fun getHistory(): List<HistoryItem> {
        val jsonString = prefs.getString("history_items", null) ?: return emptyList()
        val list = mutableListOf<HistoryItem>()
        try {
            val arr = JSONArray(jsonString)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(HistoryItem(
                    id=obj.optLong("id"), fileName=obj.getString("fileName"), originalSize=obj.getLong("originalSize"),
                    compressedSize=obj.getLong("compressedSize"), dateText=obj.getString("dateText"),
                    outputPath=obj.getString("outputPath"), mediaType=MediaType.valueOf(obj.optString("mediaType","VIDEO"))
                ))
            }
        } catch (_: Exception) {}
        return list
    }
    fun clear() { prefs.edit().clear().apply() }
    private fun saveList(list: List<HistoryItem>) {
        val arr = JSONArray()
        for (item in list) {
            val obj=JSONObject()
            obj.put("id",item.id); obj.put("fileName",item.fileName); obj.put("originalSize",item.originalSize)
            obj.put("compressedSize",item.compressedSize); obj.put("dateText",item.dateText)
            obj.put("outputPath",item.outputPath); obj.put("mediaType",item.mediaType.name); arr.put(obj)
        }
        prefs.edit().putString("history_items",arr.toString()).apply()
    }
}
