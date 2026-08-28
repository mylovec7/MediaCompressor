package com.vr3th.mediacompressor.market

// =============================================================================
// MARKET MODULE — MODELS
// =============================================================================
// Everything in the `market` package is intentionally isolated from
// MediaEngine.kt / GifEncoder.kt: no media type in this package, and nothing
// here is imported by the media-processing engine. See MARKET_MODULE.md at
// the project root for the full file map and the isolation rationale.
// =============================================================================

/** Broad instrument classes. Only the classes an active [MarketProvider] can
 * actually serve are ever shown as available — see [MarketProviders]. */
enum class AssetClass { CRYPTO, US_EQUITY, IDX_EQUITY, ETF, INDEX, FOREX, COMMODITY }

/** The 3 default favorites tabs shown in the Market UI. These are DISPLAY
 * groupings only — the [MarketInstrumentIndex] search is not limited to them. */
enum class MarketCategory { CRYPTO, US_STOCKS, IDX }

fun MarketCategory.toAssetClass(): AssetClass = when (this) {
    MarketCategory.CRYPTO -> AssetClass.CRYPTO
    MarketCategory.US_STOCKS -> AssetClass.US_EQUITY
    MarketCategory.IDX -> AssetClass.IDX_EQUITY
}

/** Explicit data-provenance state — see spec section H. Cached data must
 * never be presented as LIVE. */
enum class DataState { LIVE, CACHED, OFFLINE, UNAVAILABLE }

/**
 * A discoverable instrument. [providerId]/[providerSymbol] describe how to
 * fetch it — the UI and indicator engine never depend on a provider's raw
 * response shape, only on this normalized model.
 */
data class MarketInstrument(
    val symbol: String,
    val name: String,
    val assetClass: AssetClass,
    val exchange: String,
    val country: String,
    val currency: String,
    val providerId: String,
    val providerSymbol: String
)

/** One OHLCV candle. [volume] is null when the source genuinely has no
 * per-candle volume (e.g. CoinGecko's free OHLC endpoint) — never fabricated. */
data class Candle(val timeMillis: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double?)

data class IndicatorSnapshot(
    val rsi14: Double,
    val ema20: Double,
    val ema50: Double,
    val ema200: Double,
    val macd: Double,
    val macdSignal: Double,
    val macdHist: Double,
    val bbUpper: Double,
    val bbMid: Double,
    val bbLower: Double,
    /** Percent change of recent vs. older average volume. Null = source has no volume data (never fabricated). */
    val volumeDeltaPercent: Double?,
    val pivot: Double,
    val support1: Double,
    val resistance1: Double,
    /** False when there isn't enough candle history for the longer-period readings (EMA200 etc.) to be meaningful. */
    val sufficientHistory: Boolean
)

enum class QuantSignal { STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL }

data class QuantVerdict(
    val signal: QuantSignal,
    val confidencePercent: Double,
    val bullishCount: Int,
    val totalIndicators: Int,
    val riskRewardRatio: Double
)

/**
 * A fully-resolved quote ready for the UI. [state] is the single source of
 * truth for LIVE/CACHED/OFFLINE/UNAVAILABLE — the UI renders directly from it
 * and never infers freshness itself.
 */
data class MarketQuote(
    val instrument: MarketInstrument,
    val state: DataState,
    val sourceLabel: String,
    val asOfMillis: Long,
    val lastPrice: Double,
    val changePercent: Double,
    val high: Double,
    val low: Double,
    val volume: Double?,
    val candles: List<Candle>,
    val indicators: IndicatorSnapshot?,
    val verdict: QuantVerdict?,
    val unavailableReason: String? = null
) {
    companion object {
        fun unavailable(instrument: MarketInstrument, reason: String): MarketQuote = MarketQuote(
            instrument = instrument, state = DataState.UNAVAILABLE, sourceLabel = "—", asOfMillis = 0L,
            lastPrice = 0.0, changePercent = 0.0, high = 0.0, low = 0.0, volume = null,
            candles = emptyList(), indicators = null, verdict = null, unavailableReason = reason
        )
    }
}

enum class DisplayCurrency { IDR, USD, EUR, JPY }

/** Live/cached FX snapshot. [live] is false whenever these numbers came from cache, not a fresh fetch. */
data class FxRates(val usdToIdr: Double?, val usdToEur: Double?, val usdToJpy: Double?, val asOfMillis: Long, val live: Boolean)
