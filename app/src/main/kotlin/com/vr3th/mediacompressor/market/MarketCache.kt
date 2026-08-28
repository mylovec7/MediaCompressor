package com.vr3th.mediacompressor.market

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// =============================================================================
// MARKET MODULE — OFFLINE CACHE (Section Q)
// =============================================================================
// Native SharedPreferences only, keyed by provider+symbol+timeframe so one
// dataset never clobbers another. Only ever overwritten after a *validated*
// successful fetch — a failed refresh never replaces good cached data.
// =============================================================================

object MarketCache {
    private const val PREFS = "market_cache_v1"

    private fun key(instrument: MarketInstrument, timeframe: String) =
        "${instrument.providerId}:${instrument.symbol}:$timeframe"

    fun save(context: Context, instrument: MarketInstrument, timeframe: String, quote: MarketQuote) {
        if (quote.state != DataState.LIVE) return // only a validated live fetch is ever persisted
        try {
            val obj = JSONObject().apply {
                put("symbol", instrument.symbol)
                put("sourceLabel", quote.sourceLabel)
                put("asOfMillis", quote.asOfMillis)
                put("lastPrice", quote.lastPrice)
                put("changePercent", quote.changePercent)
                put("high", quote.high)
                put("low", quote.low)
                put("volume", quote.volume ?: JSONObject.NULL)
                put("candles", JSONArray().apply {
                    quote.candles.forEach { c ->
                        put(JSONObject().apply {
                            put("t", c.timeMillis); put("o", c.open); put("h", c.high)
                            put("l", c.low); put("c", c.close)
                            put("v", c.volume ?: JSONObject.NULL)
                        })
                    }
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(instrument, timeframe), obj.toString())
                .apply()
        } catch (_: Exception) { /* cache is best-effort; never crash the app over it */ }
    }

    /** Returns the last validated quote for this symbol+timeframe, tagged CACHED, or null if nothing was ever stored. */
    fun load(context: Context, instrument: MarketInstrument, timeframe: String): MarketQuote? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(instrument, timeframe), null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val candlesArr = obj.getJSONArray("candles")
            val candles = ArrayList<Candle>(candlesArr.length())
            for (i in 0 until candlesArr.length()) {
                val c = candlesArr.getJSONObject(i)
                candles.add(Candle(
                    timeMillis = c.getLong("t"), open = c.getDouble("o"), high = c.getDouble("h"),
                    low = c.getDouble("l"), close = c.getDouble("c"),
                    volume = if (c.isNull("v")) null else c.getDouble("v")
                ))
            }
            val lastPrice = obj.getDouble("lastPrice")
            val ind = MarketIndicators.compute(candles)
            val verdict = ind?.let { MarketIndicators.verdict(lastPrice, it) }
            MarketQuote(
                instrument = instrument, state = DataState.CACHED, sourceLabel = obj.getString("sourceLabel"),
                asOfMillis = obj.getLong("asOfMillis"), lastPrice = lastPrice,
                changePercent = obj.getDouble("changePercent"), high = obj.getDouble("high"), low = obj.getDouble("low"),
                volume = if (obj.isNull("volume")) null else obj.getDouble("volume"),
                candles = candles, indicators = ind, verdict = verdict
            )
        } catch (_: Exception) { null }
    }

    // ---- FX rates cache (Section P) -----------------------------------------

    private const val FX_KEY = "fx_rates_v1"

    fun saveFx(context: Context, rates: FxRates) {
        if (!rates.live) return
        try {
            val obj = JSONObject().apply {
                put("idr", rates.usdToIdr ?: JSONObject.NULL)
                put("eur", rates.usdToEur ?: JSONObject.NULL)
                put("jpy", rates.usdToJpy ?: JSONObject.NULL)
                put("asOfMillis", rates.asOfMillis)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(FX_KEY, obj.toString()).apply()
        } catch (_: Exception) { }
    }

    fun loadFx(context: Context): FxRates? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FX_KEY, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            FxRates(
                usdToIdr = if (obj.isNull("idr")) null else obj.getDouble("idr"),
                usdToEur = if (obj.isNull("eur")) null else obj.getDouble("eur"),
                usdToJpy = if (obj.isNull("jpy")) null else obj.getDouble("jpy"),
                asOfMillis = obj.getLong("asOfMillis"), live = false
            )
        } catch (_: Exception) { null }
    }

    // ---- Watchlist (Section T) — persists locally, works fully offline -----

    private const val WATCHLIST_KEY = "watchlist_v1"

    fun loadWatchlist(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(WATCHLIST_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun saveWatchlist(context: Context, symbols: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(WATCHLIST_KEY, symbols).apply()
    }
}
