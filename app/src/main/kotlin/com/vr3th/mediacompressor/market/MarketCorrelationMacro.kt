package com.vr3th.mediacompressor.market

import android.content.Context
import kotlin.math.sqrt

// =============================================================================
// MARKET MODULE PHASE 3 — CROSS-MARKET CORRELATION (P6)
// =============================================================================
// Reuses MarketRepository's existing providers/cache for the reference
// instrument — no new network provider type. Crypto candles from CoinGecko's
// free tier and Stooq's daily candles have different real spacings (see
// MARKET_MODULE.md), so points are matched by NEAREST calendar timestamp
// within a tolerance window rather than paired by raw index, and that window
// is stated in [CorrelationSnapshot.windowDescription] — never presented as
// same-timeframe data it isn't.
// =============================================================================

object MarketCorrelationEngine {
    private const val MIN_SAMPLES = 15
    private const val MATCH_TOLERANCE_MILLIS = 2L * 24 * 60 * 60 * 1000L // 2 days

    private fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 2) return null
        val n = xs.size
        val meanX = xs.average(); val meanY = ys.average()
        var cov = 0.0; var varX = 0.0; var varY = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX; val dy = ys[i] - meanY
            cov += dx * dy; varX += dx * dx; varY += dy * dy
        }
        val denom = sqrt(varX * varY)
        return if (denom > 0) (cov / denom).coerceIn(-1.0, 1.0) else null
    }

    /** BLOCKING — fetches the reference instrument via the existing repository/cache. Call only from a background thread. */
    fun analyze(context: Context, primary: MarketQuote, referenceLabel: String, referenceInstrument: MarketInstrument): CorrelationSnapshot {
        val now = System.currentTimeMillis()
        val reference = MarketRepository.fetchQuoteBlocking(context, referenceInstrument, "1D", indonesian = false)
        if (!reference.state.isUsable() || reference.candles.size < MIN_SAMPLES || primary.candles.size < MIN_SAMPLES) {
            return CorrelationSnapshot(referenceLabel, null, 0, "insufficient synchronized data", CorrelationDirection.NEUTRAL, sufficientSamples = false, state = DataState.UNAVAILABLE, asOfMillis = now)
        }

        val pairedX = ArrayList<Double>(); val pairedY = ArrayList<Double>()
        for (c in primary.candles) {
            val match = reference.candles.minByOrNull { kotlin.math.abs(it.timeMillis - c.timeMillis) } ?: continue
            if (kotlin.math.abs(match.timeMillis - c.timeMillis) <= MATCH_TOLERANCE_MILLIS) {
                pairedX.add(c.close); pairedY.add(match.close)
            }
        }

        if (pairedX.size < MIN_SAMPLES) {
            return CorrelationSnapshot(referenceLabel, null, pairedX.size, "nearest-timestamp matched, tolerance 2 days", CorrelationDirection.NEUTRAL, sufficientSamples = false, state = DataState.UNAVAILABLE, asOfMillis = now)
        }

        val r = pearson(pairedX, pairedY)
        val direction = when {
            r == null -> CorrelationDirection.NEUTRAL
            r > 0.2 -> CorrelationDirection.POSITIVE
            r < -0.2 -> CorrelationDirection.NEGATIVE
            else -> CorrelationDirection.NEUTRAL
        }
        return CorrelationSnapshot(
            referenceInstrumentLabel = referenceLabel, coefficient = r, sampleSize = pairedX.size,
            windowDescription = "nearest-timestamp matched (tolerance 2 days); primary candles vs ${referenceInstrument.exchange} daily candles",
            direction = direction, sufficientSamples = pairedX.size >= MIN_SAMPLES, state = DataState.LIVE, asOfMillis = now
        )
    }
}

// =============================================================================
// MARKET MODULE PHASE 3 — MACRO (P8)
// =============================================================================
// ECB Data Portal (data-api.ecb.europa.eu) is genuinely free and keyless —
// confirmed against ECB's own documentation. Series keys below are the
// long-documented ECB "Key interest rates" (FM) and HICP (ICP) dataflows;
// they were NOT hit live from this sandbox (no network access here), so if
// ECB has changed a key format this degrades to UNAVAILABLE rather than a
// crash or a guessed number — verify on a real device.
//
// FRED (US macro) requires a free API key. Per spec Section 14 ("if an API
// key is required: never hardcode it, treat it as optional configuration"),
// FRED support is provider-ready but OFF by default — [FRED_API_KEY] is
// intentionally blank; supply your own free key there to enable it, or wire
// it to a settings field. Leaving it blank means FRED reports UNAVAILABLE,
// which is the correct, honest behavior for an unconfigured optional source.
// =============================================================================

object MarketMacroProvider {

    /** Optional, user-supplied FRED key — intentionally blank. Never hardcode a real key here. */
    private const val FRED_API_KEY = ""

    private fun fetchEcbSeries(label: String, flow: String, key: String, unit: String): MacroSeriesPoint {
        val now = System.currentTimeMillis()
        val url = "https://data-api.ecb.europa.eu/service/data/$flow/$key?lastNObservations=1&format=csvdata"
        val res = MarketHttp.get(url, mapOf("Accept" to "text/csv"))
        return when (res) {
            is MarketHttp.HttpResult.Ok -> {
                try {
                    val lines = res.body.trim().lines()
                    if (lines.size < 2) return MacroSeriesPoint(label, null, unit, null, DataState.UNAVAILABLE, "ECB Data Portal", now)
                    val header = lines[0].split(",")
                    val valueIdx = header.indexOf("OBS_VALUE")
                    val periodIdx = header.indexOf("TIME_PERIOD")
                    if (valueIdx < 0) return MacroSeriesPoint(label, null, unit, null, DataState.UNAVAILABLE, "ECB Data Portal", now)
                    val cols = lines.last().split(",")
                    val value = cols.getOrNull(valueIdx)?.toDoubleOrNull()
                    val period = if (periodIdx >= 0) cols.getOrNull(periodIdx) else null
                    if (value == null) MacroSeriesPoint(label, null, unit, null, DataState.UNAVAILABLE, "ECB Data Portal", now)
                    else MacroSeriesPoint(label, value, unit, period, DataState.LIVE, "ECB Data Portal", now)
                } catch (_: Exception) { MacroSeriesPoint(label, null, unit, null, DataState.ERROR, "ECB Data Portal", now) }
            }
            is MarketHttp.HttpResult.HttpError -> MacroSeriesPoint(label, null, unit, null, DataState.UNAVAILABLE, "ECB Data Portal", now)
            is MarketHttp.HttpResult.NetworkError -> MacroSeriesPoint(label, null, unit, null, DataState.ERROR, "ECB Data Portal", now)
        }
    }

    private fun fetchFredSeries(label: String, seriesId: String, unit: String): MacroSeriesPoint {
        val now = System.currentTimeMillis()
        if (FRED_API_KEY.isBlank()) return MacroSeriesPoint(label, null, unit, null, DataState.UNAVAILABLE, "FRED (no key configured)", now)
        val url = "https://api.stlouisfed.org/fred/series/observations?series_id=$seriesId&api_key=$FRED_API_KEY&file_type=json&sort_order=desc&limit=1"
        val res = MarketHttp.get(url)
        return when (res) {
            is MarketHttp.HttpResult.Ok -> try {
                val obs = org.json.JSONObject(res.body).optJSONArray("observations")
                val entry = obs?.optJSONObject(0)
                val value = entry?.optString("value")?.toDoubleOrNull()
                val date = entry?.optString("date")
                if (value == null) MacroSeriesPoint(label, null, unit, null, DataState.UNAVAILABLE, "FRED", now)
                else MacroSeriesPoint(label, value, unit, date, DataState.LIVE, "FRED", now)
            } catch (_: Exception) { MacroSeriesPoint(label, null, unit, null, DataState.ERROR, "FRED", now) }
            else -> MacroSeriesPoint(label, null, unit, null, DataState.ERROR, "FRED", now)
        }
    }

    /** BLOCKING — several small REST calls. Call only from a background thread; result is small and safe to cache by the caller. */
    fun fetchMacro(): MacroSnapshot {
        val points = ArrayList<MacroSeriesPoint>()
        // ECB deposit facility rate — key interest rates dataflow (FM), daily frequency.
        points.add(fetchEcbSeries("ECB Deposit Facility Rate", "FM", "D.U2.EUR.4F.KR.DFR.LEV", "%"))
        // Euro area HICP inflation, annual rate of change.
        points.add(fetchEcbSeries("Euro Area HICP Inflation (YoY)", "ICP", "M.U2.N.000000.4.ANR", "%"))
        // FRED — optional, off unless a key is configured (see FRED_API_KEY above).
        points.add(fetchFredSeries("US Federal Funds Rate", "FEDFUNDS", "%"))
        points.add(fetchFredSeries("US CPI (YoY)", "CPIAUCSL", "index"))
        return MacroSnapshot(points)
    }
}
