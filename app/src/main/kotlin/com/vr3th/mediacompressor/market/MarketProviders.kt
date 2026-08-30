package com.vr3th.mediacompressor.market

import org.json.JSONArray
import org.json.JSONObject

// =============================================================================
// MARKET MODULE — PROVIDER ADAPTERS
// =============================================================================
// Each provider ONLY knows how to talk to one legitimate, keyless public API
// and normalize its response into [Candle]/[ProviderResult]. Nothing here
// guesses or invents a value: if a response doesn't validate, the provider
// reports Unavailable/Failure and [MarketRepository] tries the next provider
// or falls back to cache — it never fabricates a replacement.
//
// DEEP API RESEARCH SUMMARY (see MARKET_MODULE.md for the full comparison):
//  - Crypto            -> CoinGecko public API (no key, wide global coverage,
//                          real OHLC + 24h ticker endpoints).
//  - US equities/ETFs/  -> Stooq (stooq.com), no key, CSV daily OHLCV,
//    indices/commodities   documented exchange suffixes (.US, .UK, .DE, .JP, .HK...).
//  - Indonesian IDX     -> attempted via Stooq using a Jakarta suffix on a
//                          best-effort basis; Stooq's documented suffix list
//                          does not confirm IDX coverage, so every response is
//                          validated and IDX cleanly falls back to
//                          DATA UNAVAILABLE if Stooq doesn't actually carry it.
//                          No other free, keyless, legitimate IDX provider was
//                          found — see MARKET_MODULE.md before adding one.
//  - Forex (FX rates)   -> Frankfurter (api.frankfurter.app), ECB reference
//                          rates, no key.
//  - Order book / Level-2, sentiment/funding, fundamentals, news, options,
//    futures, bonds -> no legitimate keyless provider is wired in this build.
//    The UI shows "DATA UNAVAILABLE" for these rather than fabricating them
//    or silently hiding the section.
// =============================================================================

sealed class ProviderResult {
    data class Success(
        val candles: List<Candle>,
        val lastPrice: Double,
        val changePercent: Double,
        val high: Double,
        val low: Double,
        val volume: Double?,
        val asOfMillis: Long,
        val sourceLabel: String
    ) : ProviderResult()

    /** The provider is reachable but genuinely does not support this instrument/timeframe. */
    data class Unavailable(val reason: String) : ProviderResult()

    /** Network/parse/rate-limit problem — worth trying the next provider or cache, not the same as Unavailable. */
    data class Failure(val reason: String) : ProviderResult()
}

interface MarketProvider {
    val id: String
    fun supports(instrument: MarketInstrument): Boolean
    /** BLOCKING network call. Must only be invoked from [MarketExecutors.io]. */
    fun fetchQuote(instrument: MarketInstrument, timeframe: String): ProviderResult
}

class CoinGeckoProvider : MarketProvider {
    override val id = "coingecko"
    private val base = "https://api.coingecko.com/api/v3"

    override fun supports(instrument: MarketInstrument): Boolean =
        instrument.assetClass == AssetClass.CRYPTO && instrument.providerId == id

    // UI timeframe -> CoinGecko `days` window. CoinGecko's free OHLC endpoint
    // fixes candle spacing by the `days` value: 1-2d -> 30m candles,
    // 3-30d -> 4h candles, >30d -> 4-day candles. We only claim a UI timeframe
    // is "supported" when the resulting spacing is a reasonably close match;
    // otherwise we still show the data but a coarser real spacing, never a
    // relabeled one.
    private fun daysFor(timeframe: String): Int = when (timeframe) {
        "1M", "5M", "15M" -> 1   // -> ~30m real candles (closest CoinGecko offers to intraday)
        "1H", "4H" -> 14         // -> ~4h real candles
        else -> 90               // "1D" -> ~4-day real candles (closest to daily on the free tier)
    }

    override fun fetchQuote(instrument: MarketInstrument, timeframe: String): ProviderResult {
        val days = daysFor(timeframe)
        val ohlcUrl = "$base/coins/${instrument.providerSymbol}/ohlc?vs_currency=usd&days=$days"
        val ohlcRes = MarketHttp.get(ohlcUrl)
        val candles = when (ohlcRes) {
            is MarketHttp.HttpResult.Ok -> parseOhlc(ohlcRes.body)
            is MarketHttp.HttpResult.HttpError ->
                return if (ohlcRes.code == 404) ProviderResult.Unavailable("COINGECKO_404") else ProviderResult.Failure("HTTP_${ohlcRes.code}")
            is MarketHttp.HttpResult.NetworkError -> return ProviderResult.Failure(ohlcRes.message)
        } ?: return ProviderResult.Failure("BAD_OHLC_JSON")

        if (candles.isEmpty()) return ProviderResult.Unavailable("NO_CANDLES")

        // 24h ticker (price, change%, volume) — separate lightweight endpoint,
        // kept independent so a hiccup here never blocks the OHLC candles above.
        val tickerUrl = "$base/simple/price?ids=${instrument.providerSymbol}&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true&include_last_updated_at=true"
        val tickerRes = MarketHttp.get(tickerUrl)
        var lastPrice = candles.last().close
        var changePct = 0.0
        var vol24h: Double? = null
        var asOf = System.currentTimeMillis()
        if (tickerRes is MarketHttp.HttpResult.Ok) {
            try {
                val obj = JSONObject(tickerRes.body).optJSONObject(instrument.providerSymbol)
                if (obj != null) {
                    lastPrice = obj.optDouble("usd", lastPrice)
                    changePct = obj.optDouble("usd_24h_change", changePct)
                    if (obj.has("usd_24h_vol")) vol24h = obj.optDouble("usd_24h_vol")
                    if (obj.has("last_updated_at")) asOf = obj.optLong("last_updated_at") * 1000L
                }
            } catch (_: Exception) { /* ticker is best-effort; OHLC-derived values already stand in above */ }
        }

        val high = candles.maxOf { it.high }
        val low = candles.minOf { it.low }
        return ProviderResult.Success(
            candles = candles, lastPrice = lastPrice, changePercent = changePct,
            high = high, low = low, volume = vol24h, asOfMillis = asOf, sourceLabel = "CoinGecko"
        )
    }

    private fun parseOhlc(body: String): List<Candle>? = try {
        val arr = JSONArray(body)
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.getJSONArray(i)
            if (row.length() < 5) continue
            out.add(Candle(
                timeMillis = row.getLong(0),
                open = row.getDouble(1), high = row.getDouble(2),
                low = row.getDouble(3), close = row.getDouble(4),
                volume = null // CoinGecko's free OHLC endpoint carries no per-candle volume — never fabricated
            ))
        }
        out
    } catch (_: Exception) { null }
}

/**
 * Stooq CSV daily-history provider. Covers US equities/ETFs/indices/commodities
 * with a documented suffix (e.g. AAPL.US), and is attempted best-effort for
 * IDX (see the module-level note above) — an invalid/"N/D" response is
 * detected and reported Unavailable rather than shown as a price.
 *
 * Stooq's free CSV endpoint only returns DAILY candles, so every UI timeframe
 * maps to the same daily series here; intraday timeframes for equities/IDX
 * correctly degrade to "closest available: 1D" rather than pretending to be
 * intraday — see [MarketRepository] for how that's surfaced.
 */
class StooqProvider : MarketProvider {
    override val id = "stooq"

    override fun supports(instrument: MarketInstrument): Boolean =
        instrument.providerId == id && instrument.assetClass in setOf(
            AssetClass.US_EQUITY, AssetClass.IDX_EQUITY, AssetClass.ETF, AssetClass.INDEX, AssetClass.COMMODITY
        )

    override fun fetchQuote(instrument: MarketInstrument, timeframe: String): ProviderResult {
        val url = "https://stooq.com/q/d/l/?s=${instrument.providerSymbol}&i=d"
        val res = MarketHttp.get(url)
        val body = when (res) {
            is MarketHttp.HttpResult.Ok -> res.body
            is MarketHttp.HttpResult.HttpError -> return ProviderResult.Failure("HTTP_${res.code}")
            is MarketHttp.HttpResult.NetworkError -> return ProviderResult.Failure(res.message)
        }
        if (body.isBlank() || body.contains("N/D", ignoreCase = false) || body.startsWith("<")) {
            // Stooq returns a bare "N/D" body (or an HTML error page) for a
            // symbol/exchange combination it doesn't actually carry.
            return ProviderResult.Unavailable("SYMBOL_NOT_CARRIED")
        }
        val candles = parseCsv(body) ?: return ProviderResult.Failure("BAD_CSV")
        if (candles.size < 2) return ProviderResult.Unavailable("NO_HISTORY")

        val last = candles.last()
        val prev = candles[candles.size - 2]
        val changePct = if (prev.close != 0.0) ((last.close - prev.close) / prev.close) * 100.0 else 0.0
        return ProviderResult.Success(
            candles = candles, lastPrice = last.close, changePercent = changePct,
            high = last.high, low = last.low, volume = last.volume,
            asOfMillis = last.timeMillis, sourceLabel = "Stooq"
        )
    }

    private fun parseCsv(body: String): List<Candle>? = try {
        val lines = body.trim().lines()
        if (lines.size < 2) null else {
            val out = ArrayList<Candle>(lines.size - 1)
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            for (i in 1 until lines.size) {
                val cols = lines[i].split(",")
                if (cols.size < 6) continue
                val t = fmt.parse(cols[0])?.time ?: continue
                val o = cols[1].toDoubleOrNull() ?: continue
                val h = cols[2].toDoubleOrNull() ?: continue
                val l = cols[3].toDoubleOrNull() ?: continue
                val c = cols[4].toDoubleOrNull() ?: continue
                val v = cols[5].toDoubleOrNull()
                out.add(Candle(t, o, h, l, c, v))
            }
            out
        }
    } catch (_: Exception) { null }
}

/** ECB reference rates via Frankfurter — used only for the IDR/USD/EUR/JPY display-currency toggle (Section P), never for instrument pricing. */
class FrankfurterFxProvider {
    fun fetchRates(): FxRates? {
        val res = MarketHttp.get("https://api.frankfurter.app/latest?from=USD&to=IDR,EUR,JPY")
        if (res !is MarketHttp.HttpResult.Ok) return null
        return try {
            val obj = JSONObject(res.body)
            val rates = obj.optJSONObject("rates") ?: return null
            val idr = if (rates.has("IDR")) rates.optDouble("IDR") else null
            val eur = if (rates.has("EUR")) rates.optDouble("EUR") else null
            val jpy = if (rates.has("JPY")) rates.optDouble("JPY") else null
            if (idr == null && eur == null && jpy == null) return null
            FxRates(idr, eur, jpy, System.currentTimeMillis(), live = true)
        } catch (_: Exception) { null }
    }
}
