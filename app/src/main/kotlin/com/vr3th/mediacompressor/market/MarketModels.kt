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

/**
 * Explicit data-provenance state (upgraded per MARKET_ONLY_MASTER_UPGRADE_PROMPT
 * Section 6 — Data Quality Contract). Cached data must never be presented as LIVE,
 * and a provider's own "delayed" data must never be presented as real-time.
 *
 *  LIVE        — fresh data straight from a provider that offers real-time-ish data (e.g. CoinGecko ticker).
 *  DELAYED     — fresh fetch succeeded, but the source is inherently non-real-time (e.g. Stooq's end-of-day CSV).
 *  CACHED      — a previously validated fetch, still within the freshness threshold for its timeframe.
 *  STALE       — a previously validated fetch, past the freshness threshold — still shown (better than nothing) but flagged.
 *  OFFLINE     — no network and nothing usable in cache.
 *  UNAVAILABLE — the provider is reachable but does not carry this dataset (never faked).
 *  ERROR       — an unexpected provider/parse failure (distinct from "doesn't carry it").
 */
enum class DataState { LIVE, DELAYED, CACHED, STALE, OFFLINE, UNAVAILABLE, ERROR }

/** Is this state one the AI/UI may treat as "reasonably current" data worth analyzing? */
fun DataState.isUsable(): Boolean = this == DataState.LIVE || this == DataState.DELAYED || this == DataState.CACHED || this == DataState.STALE


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
    val unavailableReason: String? = null,
    /** Full Market Intelligence pipeline output (structure/S-R/volatility/confluence/risk/AI narrative) — null only when there isn't enough data to say anything responsible. */
    val analysis: MarketAnalysis? = null
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

// =============================================================================
// MARKET INTELLIGENCE UPGRADE — structure / S-R / volatility / confluence /
// risk / explainable AI. Everything below is derived ONLY from the candles
// a provider actually returned. Nothing here invents a price or a level.
// =============================================================================

enum class SwingType { HIGH, LOW }
data class SwingPoint(val index: Int, val timeMillis: Long, val price: Double, val type: SwingType)

enum class StructureTrend { UPTREND, DOWNTREND, RANGE, TRANSITION }
enum class StructureEvent { BOS_BULLISH, BOS_BEARISH, CHOCH_BULLISH, CHOCH_BEARISH, NONE }
enum class BreakoutState { BREAKOUT, BREAKDOWN, RETEST, FAILED_BREAKOUT, FAILED_BREAKDOWN, NONE }

data class MarketStructureSnapshot(
    val trend: StructureTrend,
    val lastEvent: StructureEvent,
    val breakout: BreakoutState,
    val swingHighs: List<SwingPoint>,
    val swingLows: List<SwingPoint>,
    /** 0..1 — how clean/consistent the HH/HL or LH/LL sequence is. Not a probability. */
    val structureStrength: Double,
    val sufficientData: Boolean
)

enum class SRLevelType { SUPPORT, RESISTANCE }
data class SRLevel(
    val price: Double,
    val type: SRLevelType,
    val touches: Int,
    /** 0..1, from touch count + recency + confluence with EMA/pivot. Not a probability. */
    val strengthScore: Double,
    val distancePercent: Double,
    val recentlyBroken: Boolean,
    val flippedRole: Boolean
)

enum class VolatilityRegime { LOW, NORMAL, ELEVATED, HIGH, UNKNOWN }
data class VolatilitySnapshot(
    val atr14: Double?,
    val atrPercentOfPrice: Double?,
    val regime: VolatilityRegime,
    /** Null when the provider has no per-candle volume (e.g. CoinGecko free tier) — VWAP is never estimated without it. */
    val vwap: Double?,
    val priceAboveVwap: Boolean?,
    /** Relative volume: latest volume vs. its recent average. Null = no volume data. */
    val rvol: Double?
)

data class DivergenceSnapshot(
    val rsiBullishDivergence: Boolean,
    val rsiBearishDivergence: Boolean,
    val macdBullishDivergence: Boolean,
    val macdBearishDivergence: Boolean,
    val sufficientData: Boolean
)

enum class MarketRegime { STRONG_BULL, WEAK_BULL, STRONG_BEAR, WEAK_BEAR, RANGE, BREAKOUT_REGIME, TRANSITION, UNKNOWN }

enum class ConfluenceCategory { TREND, STRUCTURE, MOMENTUM, VOLUME, VOLATILITY, VWAP, SUPPORT_RESISTANCE, DIVERGENCE, ORDER_FLOW, DERIVATIVES, SENTIMENT, MULTI_TIMEFRAME, LIQUIDATIONS, OPTIONS }

/** One category's vote. [direction] is -1 (bearish) / 0 (neutral) / +1 (bullish); [weight] reflects how much independent
 * information this category actually adds (correlated categories are weighted down, never double-counted as full votes). */
data class CategoryEvidence(val category: ConfluenceCategory, val direction: Int, val weight: Double, val note: String)

data class ConfluenceResult(
    val evidences: List<CategoryEvidence>,
    /** -1..+1 weighted net score. */
    val netScore: Double,
    /** 0..100, derived from netScore, evidence count, AND data completeness/contradictions — never a raw indicator count. */
    val convergencePercent: Double,
    val contradictions: List<String>,
    val dataCompletenessPercent: Double
)

enum class SignalQuality { HIGH, MEDIUM, LOW, NO_VALID_SETUP }

/** Real, level-derived risk plan — never a formula driven by signal strength alone. [valid] is false when there isn't
 * enough structure/volatility data to place a responsible stop, in which case the UI must show "NO TRADE", not a guess. */
data class RiskPlan(
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val riskAmount: Double,
    val rewardAmount: Double,
    val riskRewardRatio: Double,
    val basis: String,
    val valid: Boolean
)

enum class AiVerdict { STRONG_BUY, BUY, NEUTRAL_WAIT, SELL, STRONG_SELL, NO_TRADE }

data class ScenarioSet(val bullishScenario: String, val bearishScenario: String, val invalidation: String, val waitCondition: String?)

/**
 * The full "AI Market Brain" output for one instrument+timeframe — structured
 * evidence + an explainable narrative, never a bare "BUY — 82%".
 */
data class MarketAnalysis(
    val regime: MarketRegime,
    val structure: MarketStructureSnapshot,
    val supportResistance: List<SRLevel>,
    val volatility: VolatilitySnapshot,
    val divergence: DivergenceSnapshot,
    val confluence: ConfluenceResult,
    val signalQuality: SignalQuality,
    val risk: RiskPlan?,
    val verdict: AiVerdict,
    val scenarios: ScenarioSet?,
    /** The human-readable, section-by-section explanation (bilingual ID/EN, matching the rest of the app). */
    val narrative: String,
    /** Phase 2 — all null unless the caller supplied them (only the instrument detail screen fetches these, never the list, to control API calls). */
    val orderBook: OrderBookSnapshot? = null,
    val derivatives: DerivativesSnapshot? = null,
    val sentiment: SentimentSnapshot? = null,
    val multiTimeframe: MultiTimeframeSnapshot? = null,
    /** Phase 3 — real-time state, options, liquidations, volume profile, fundamentals, cross-market, macro, historical validation. All null unless genuinely available/fetched. */
    val realtime: RealtimeQuoteState? = null,
    val options: OptionsSnapshot? = null,
    val liquidations: LiquidationSnapshot? = null,
    val volumeProfile: VolumeProfileSnapshot? = null,
    val fundamentals: FundamentalsSnapshot? = null,
    val correlation: CorrelationSnapshot? = null,
    val macro: MacroSnapshot? = null,
    val backtest: BacktestResult? = null
)

// =============================================================================
// PHASE 2 — DATA COVERAGE EXPANSION MODELS
// =============================================================================
// Order book / derivatives / sentiment are crypto-only (Binance + Alternative.me
// public endpoints — see MarketExtendedProviders.kt) and are fetched only for
// the single instrument the user has opened in detail, never for the whole
// favorites list, to keep the app lightweight and avoid hammering rate limits.
// =============================================================================

data class OrderBookSnapshot(
    val bestBid: Double?,
    val bestAsk: Double?,
    val spreadPercent: Double?,
    val bidDepthTop20: Double?,
    val askDepthTop20: Double?,
    /** (bidDepth-askDepth)/(bidDepth+askDepth)*100 — positive = more resting buy interest near the top of book. */
    val imbalancePercent: Double?,
    val state: DataState,
    val sourceLabel: String,
    val asOfMillis: Long
)

data class DerivativesSnapshot(
    val fundingRatePercent: Double?,
    val markPrice: Double?,
    val indexPrice: Double?,
    val openInterest: Double?,
    /** Ratio of long to short accounts on the exchange — positioning context, never a directional signal by itself. */
    val longShortRatio: Double?,
    val state: DataState,
    val sourceLabel: String,
    val asOfMillis: Long
)

data class SentimentSnapshot(
    val fearGreedValue: Int?,
    val fearGreedLabel: String?,
    val state: DataState,
    val sourceLabel: String,
    val asOfMillis: Long
)

enum class TimeframeAlignment { ALIGNED_BULLISH, ALIGNED_BEARISH, CONFLICTING, INSUFFICIENT }

/** Higher-timeframe context vs. lower-timeframe confirmation — never weighted equally (spec Section 8). */
data class MultiTimeframeSnapshot(
    val higherTimeframeLabel: String,
    val lowerTimeframeLabel: String,
    val higherTrend: StructureTrend?,
    val lowerTrend: StructureTrend?,
    val alignment: TimeframeAlignment,
    val note: String
)

