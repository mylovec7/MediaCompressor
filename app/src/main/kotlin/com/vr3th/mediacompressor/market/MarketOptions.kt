package com.vr3th.mediacompressor.market

import org.json.JSONArray

// =============================================================================
// MARKET MODULE PHASE 3 — CRYPTO OPTIONS (P2)
// =============================================================================
// Deribit's `public/get_book_summary_by_currency` is a documented, free,
// keyless REST endpoint (confirmed against Deribit's own API docs) — no
// client ID/secret needed for this public market-data call. BTC/ETH options
// only; any other underlying is simply not supported by Deribit and reports
// UNAVAILABLE. Every derived figure (put/call ratio, IV context, OI
// concentration) is explicitly computed here from the raw per-instrument
// summaries — never presented as something Deribit returned directly.
// =============================================================================

object MarketOptionsProvider {

    private val supportedCurrencies = setOf("BTC", "ETH")

    fun fetchOptions(instrument: MarketInstrument): OptionsSnapshot? {
        if (instrument.assetClass != AssetClass.CRYPTO) return null
        val currency = instrument.symbol.trim().uppercase()
        if (currency !in supportedCurrencies) return null // Deribit genuinely doesn't list options for this coin

        val url = "https://www.deribit.com/api/v2/public/get_book_summary_by_currency?currency=$currency&kind=option"
        val res = MarketHttp.get(url)
        val now = System.currentTimeMillis()
        val body = when (res) {
            is MarketHttp.HttpResult.Ok -> res.body
            is MarketHttp.HttpResult.HttpError -> return OptionsSnapshot(currency, null, null, null, emptyList(), null, 0, DataState.ERROR, "Deribit", now)
            is MarketHttp.HttpResult.NetworkError -> return OptionsSnapshot(currency, null, null, null, emptyList(), null, 0, DataState.ERROR, "Deribit", now)
        }

        return try {
            val root = org.json.JSONObject(body)
            val result = root.optJSONArray("result") ?: JSONArray()
            if (result.length() == 0) return OptionsSnapshot(currency, null, null, null, emptyList(), null, 0, DataState.UNAVAILABLE, "Deribit", now)

            var callOi = 0.0; var putOi = 0.0
            var indexPrice: Double? = null
            var ivWeightedSum = 0.0; var ivWeightTotal = 0.0
            var nearestExpiry: Long? = null
            val oiByStrike = HashMap<Double, Double>()

            for (i in 0 until result.length()) {
                val row = result.getJSONObject(i)
                val name = row.optString("instrument_name") // e.g. BTC-27JUN26-70000-C
                val parts = name.split("-")
                if (parts.size < 4) continue
                val strike = parts[2].toDoubleOrNull() ?: continue
                val isCall = parts[3].startsWith("C", ignoreCase = true)
                val oi = row.optDouble("open_interest", 0.0)
                val markIv = if (row.has("mark_iv")) row.optDouble("mark_iv") else null
                if (row.has("underlying_price")) indexPrice = row.optDouble("underlying_price")
                if (isCall) callOi += oi else putOi += oi
                if (markIv != null && oi > 0) { ivWeightedSum += markIv * oi; ivWeightTotal += oi }
                oiByStrike[strike] = (oiByStrike[strike] ?: 0.0) + oi
                val expiryMillis = parseDeribitExpiry(parts[1])
                if (expiryMillis != null && (nearestExpiry == null || expiryMillis < nearestExpiry!!)) nearestExpiry = expiryMillis
            }

            val putCallRatio = if (callOi > 0) putOi / callOi else null
            val ivContext = if (ivWeightTotal > 0) ivWeightedSum / ivWeightTotal else null
            val topStrikes = oiByStrike.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }

            OptionsSnapshot(
                underlyingCurrency = currency, indexPrice = indexPrice,
                putCallOiRatioDerived = putCallRatio, atmIvContextDerived = ivContext,
                topOiStrikesDerived = topStrikes, nearestExpiryMillis = nearestExpiry,
                contractsConsidered = result.length(), state = DataState.LIVE, sourceLabel = "Deribit", asOfMillis = now
            )
        } catch (_: Exception) {
            OptionsSnapshot(currency, null, null, null, emptyList(), null, 0, DataState.ERROR, "Deribit", now)
        }
    }

    /** Deribit expiry format is DDMMMYY (e.g. "27JUN26"). Returns millis at UTC midnight of that date, or null if unparseable. */
    private fun parseDeribitExpiry(token: String): Long? = try {
        val fmt = java.text.SimpleDateFormat("ddMMMyy", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(token)?.time
    } catch (_: Exception) { null }
}
