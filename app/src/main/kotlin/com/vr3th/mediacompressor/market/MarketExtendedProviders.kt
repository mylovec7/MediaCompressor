package com.vr3th.mediacompressor.market

import org.json.JSONArray
import org.json.JSONObject

// =============================================================================
// MARKET MODULE PHASE 2 — EXTENDED DATA PROVIDERS
// =============================================================================
// All endpoints here are documented, free, keyless PUBLIC market-data
// endpoints (verified against Binance's own "Market Data Only" docs, which
// state these URLs need no authentication, and Alternative.me's public Fear &
// Greed API). Nothing here is a paid/authenticated feature masquerading as
// free. Every response is validated before use; anything that doesn't
// validate degrades to UNAVAILABLE/ERROR rather than a guessed number.
//
// Order book, derivatives (funding/OI/long-short), and sentiment are CRYPTO
// ONLY — no equivalent free/keyless source was found for equities/IDX/FX/
// commodities, so those asset classes correctly get UNAVAILABLE for this
// whole section (see MARKET_MODULE.md for what was investigated and why).
// =============================================================================

/** Best-effort CoinGecko-id -> Binance ticker mapping. Curated for our favorites; falls back to
 * SYMBOL+"USDT" for anything else, which is validated at call time (an unknown pair returns an
 * HTTP error from Binance and is treated as Unavailable, never assumed to have succeeded). */
internal object BinanceSymbolMap {
    private val curated = mapOf(
        "bitcoin" to "BTCUSDT", "ethereum" to "ETHUSDT", "solana" to "SOLUSDT",
        "binancecoin" to "BNBUSDT", "ripple" to "XRPUSDT", "dogecoin" to "DOGEUSDT", "cardano" to "ADAUSDT"
    )

    fun symbolFor(instrument: MarketInstrument): String? {
        if (instrument.assetClass != AssetClass.CRYPTO) return null
        curated[instrument.providerSymbol]?.let { return it }
        val sym = instrument.symbol.trim().uppercase()
        return if (sym.isNotEmpty()) "${sym}USDT" else null
    }
}

object MarketOrderFlowProvider {
    /** Top-of-book snapshot from Binance Spot's public depth endpoint (`/api/v3/depth`, no key required). */
    fun fetchOrderBook(instrument: MarketInstrument): OrderBookSnapshot? {
        val symbol = BinanceSymbolMap.symbolFor(instrument) ?: return null
        val res = MarketHttp.get("https://api.binance.com/api/v3/depth?symbol=$symbol&limit=20")
        val now = System.currentTimeMillis()
        return when (res) {
            is MarketHttp.HttpResult.Ok -> try {
                val obj = JSONObject(res.body)
                val bids = obj.optJSONArray("bids")
                val asks = obj.optJSONArray("asks")
                if (bids == null || asks == null || bids.length() == 0 || asks.length() == 0) {
                    OrderBookSnapshot(null, null, null, null, null, null, DataState.UNAVAILABLE, "Binance", now)
                } else {
                    val bestBid = bids.getJSONArray(0).getString(0).toDoubleOrNull()
                    val bestAsk = asks.getJSONArray(0).getString(0).toDoubleOrNull()
                    var bidDepth = 0.0; var askDepth = 0.0
                    for (i in 0 until bids.length()) bidDepth += (bids.getJSONArray(i).getString(1).toDoubleOrNull() ?: 0.0)
                    for (i in 0 until asks.length()) askDepth += (asks.getJSONArray(i).getString(1).toDoubleOrNull() ?: 0.0)
                    val spreadPct = if (bestBid != null && bestAsk != null && bestAsk > 0) ((bestAsk - bestBid) / bestAsk) * 100.0 else null
                    val totalDepth = bidDepth + askDepth
                    val imbalance = if (totalDepth > 0) ((bidDepth - askDepth) / totalDepth) * 100.0 else null
                    OrderBookSnapshot(bestBid, bestAsk, spreadPct, bidDepth, askDepth, imbalance, DataState.LIVE, "Binance", now)
                }
            } catch (_: Exception) {
                OrderBookSnapshot(null, null, null, null, null, null, DataState.ERROR, "Binance", now)
            }
            is MarketHttp.HttpResult.HttpError -> if (res.code == 400 || res.code == 404) null // symbol simply doesn't exist on Binance — Unavailable, not Error
                else OrderBookSnapshot(null, null, null, null, null, null, DataState.ERROR, "Binance", now)
            is MarketHttp.HttpResult.NetworkError -> OrderBookSnapshot(null, null, null, null, null, null, DataState.ERROR, "Binance", now)
        }
    }
}

object MarketDerivativesProvider {
    /** Funding rate + mark/index price from Binance USDS-M Futures `/fapi/v1/premiumIndex`, open interest from
     * `/fapi/v1/openInterest`, and long/short account ratio from `/futures/data/globalLongShortAccountRatio` —
     * all documented, free, keyless public market-data endpoints. Perpetual futures only exist for a subset of
     * coins, so a missing/failed response here is routine and correctly becomes null, not an error banner. */
    fun fetchDerivatives(instrument: MarketInstrument): DerivativesSnapshot? {
        val symbol = BinanceSymbolMap.symbolFor(instrument) ?: return null
        val now = System.currentTimeMillis()

        val premiumRes = MarketHttp.get("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$symbol")
        val premium = if (premiumRes is MarketHttp.HttpResult.Ok) try { JSONObject(premiumRes.body) } catch (_: Exception) { null } else null
        if (premiumRes is MarketHttp.HttpResult.HttpError && (premiumRes.code == 400 || premiumRes.code == 404)) return null // no perpetual for this symbol — genuinely unavailable

        val fundingRate = premium?.optString("lastFundingRate", null)?.toDoubleOrNull()?.times(100.0)
        val markPrice = premium?.optString("markPrice", null)?.toDoubleOrNull()
        val indexPrice = premium?.optString("indexPrice", null)?.toDoubleOrNull()

        val oiRes = MarketHttp.get("https://fapi.binance.com/fapi/v1/openInterest?symbol=$symbol")
        val openInterest = if (oiRes is MarketHttp.HttpResult.Ok) try { JSONObject(oiRes.body).optString("openInterest", null)?.toDoubleOrNull() } catch (_: Exception) { null } else null

        val lsRes = MarketHttp.get("https://fapi.binance.com/futures/data/globalLongShortAccountRatio?symbol=$symbol&period=1h&limit=1")
        val longShort = if (lsRes is MarketHttp.HttpResult.Ok) try {
            val arr = JSONArray(lsRes.body)
            if (arr.length() > 0) arr.getJSONObject(arr.length() - 1).optString("longShortRatio", null)?.toDoubleOrNull() else null
        } catch (_: Exception) { null } else null

        if (premium == null && openInterest == null && longShort == null) {
            return DerivativesSnapshot(null, null, null, null, null, DataState.UNAVAILABLE, "Binance Futures", now)
        }
        return DerivativesSnapshot(fundingRate, markPrice, indexPrice, openInterest, longShort, DataState.LIVE, "Binance Futures", now)
    }
}

object MarketSentimentProvider {
    /** Market-wide (not per-asset) crypto Fear & Greed Index from Alternative.me's free public API.
     * Per Alternative.me's terms, the source must be acknowledged wherever this value is shown. */
    fun fetchFearGreed(): SentimentSnapshot? {
        val res = MarketHttp.get("https://api.alternative.me/fng/?limit=1")
        val now = System.currentTimeMillis()
        return when (res) {
            is MarketHttp.HttpResult.Ok -> try {
                val arr = JSONObject(res.body).optJSONArray("data")
                val entry = arr?.optJSONObject(0)
                val value = entry?.optString("value")?.toIntOrNull()
                val label = entry?.optString("value_classification")
                if (value == null) SentimentSnapshot(null, null, DataState.UNAVAILABLE, "Alternative.me", now)
                else SentimentSnapshot(value, label, DataState.LIVE, "Alternative.me", now)
            } catch (_: Exception) {
                SentimentSnapshot(null, null, DataState.ERROR, "Alternative.me", now)
            }
            is MarketHttp.HttpResult.HttpError -> SentimentSnapshot(null, null, DataState.ERROR, "Alternative.me", now)
            is MarketHttp.HttpResult.NetworkError -> SentimentSnapshot(null, null, DataState.ERROR, "Alternative.me", now)
        }
    }
}
