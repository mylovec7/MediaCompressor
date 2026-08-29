package com.vr3th.mediacompressor.market

// =============================================================================
// MARKET MODULE PHASE 3 — MODELS
// =============================================================================
// Split from MarketModels.kt purely to keep file sizes manageable — same
// package, same isolation guarantees (nothing here is imported by or imports
// MediaEngine.kt/GifEncoder.kt). Every field that can be genuinely unavailable
// is nullable; nothing here is ever filled with a guessed value.
// =============================================================================

// ---- P1: Real-time state -----------------------------------------------

enum class StreamConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, UNSUPPORTED }

/** Live in-memory view of a WebSocket-fed instrument. [state] reflects the actual socket lifecycle —
 * the UI must never say LIVE when this is anything other than CONNECTED with a recent [lastMessageMillis]. */
data class RealtimeQuoteState(
    val bestBid: Double?,
    val bestAsk: Double?,
    val lastTradePrice: Double?,
    val lastTradeQty: Double?,
    val connection: StreamConnectionState,
    val lastMessageMillis: Long,
    /** True once [lastMessageMillis] is older than the staleness window for a live stream — the UI must
     * downgrade its LIVE badge the moment this flips, per spec Section 5 ("if the stream stops long enough,
     * stop claiming LIVE"). */
    val isStale: Boolean
)

// ---- P2: Options (Deribit) ----------------------------------------------

data class OptionContractSummary(
    val instrumentName: String,
    val strike: Double,
    val expiryMillis: Long,
    val isCall: Boolean,
    val markIv: Double?,
    val openInterest: Double?,
    val volume24h: Double?
)

data class OptionsSnapshot(
    val underlyingCurrency: String,
    val indexPrice: Double?,
    /** DERIVED: total call OI / total put OI across all fetched contracts. */
    val putCallOiRatioDerived: Double?,
    /** DERIVED: volume-weighted average mark IV across all fetched contracts, as a rough IV context reading. */
    val atmIvContextDerived: Double?,
    /** DERIVED: strikes with the largest combined OI — a rough "where is positioning concentrated" view. */
    val topOiStrikesDerived: List<Pair<Double, Double>>,
    val nearestExpiryMillis: Long?,
    val contractsConsidered: Int,
    val state: DataState,
    val sourceLabel: String,
    val asOfMillis: Long
)

// ---- P3: Liquidations -----------------------------------------------------

enum class LiquidationSide { LONG, SHORT }
data class LiquidationEvent(val symbol: String, val side: LiquidationSide, val price: Double, val quantity: Double, val notional: Double, val timeMillis: Long)

data class LiquidationSnapshot(
    val recent: List<LiquidationEvent>,
    /** Sum of notional over the rolling window actually held in [recent] — real, not modeled. */
    val rollingLongNotional: Double,
    val rollingShortNotional: Double,
    /** True only when the rolling volume clearly exceeds what was observed earlier in the same session — a
     * contextual flag, never auto-interpreted as a reversal signal (spec explicitly forbids that). */
    val spikeDetected: Boolean,
    val connection: StreamConnectionState,
    val state: DataState,
    val sourceLabel: String,
    val asOfMillis: Long
)

// ---- P4: Volume Profile / VPOC ---------------------------------------------

data class VolumeProfileLevel(val priceLow: Double, val priceHigh: Double, val volume: Double)

/** Explicitly labeled CANDLE-DERIVED — built from OHLCV bars, not tick-by-tick trade prints, per spec Section 8. */
data class VolumeProfileSnapshot(
    val levels: List<VolumeProfileLevel>,
    val pointOfControl: Double?, // VPOC — the level with the most (candle-derived) volume
    val valueAreaLow: Double?,
    val valueAreaHigh: Double?,
    val methodLabel: String, // always "CANDLE-DERIVED VOLUME PROFILE"
    val sufficientData: Boolean
)

// ---- P5: SEC EDGAR fundamentals --------------------------------------------

enum class FundamentalBasis { REPORTED, CALCULATED }
data class FundamentalFact(val label: String, val value: Double?, val unit: String, val fiscalPeriod: String?, val filedDate: String?, val basis: FundamentalBasis)

data class FundamentalsSnapshot(
    val ticker: String,
    val cik: String?,
    val facts: List<FundamentalFact>,
    val state: DataState,
    val sourceLabel: String,
    val asOfMillis: Long
)

// ---- P6: Cross-market correlation ------------------------------------------

enum class CorrelationDirection { POSITIVE, NEGATIVE, NEUTRAL }
data class CorrelationSnapshot(
    val referenceInstrumentLabel: String,
    val coefficient: Double?, // Pearson r, -1..1
    val sampleSize: Int,
    val windowDescription: String,
    val direction: CorrelationDirection,
    val sufficientSamples: Boolean,
    val state: DataState,
    val asOfMillis: Long
)

// ---- P7: Historical signal evaluation / backtest ---------------------------

enum class SignalOutcome { TARGET_HIT, STOP_HIT, TIMED_OUT, PENDING, NOT_APPLICABLE }

data class LoggedSignal(
    val timestampMillis: Long,
    val symbol: String,
    val timeframe: String,
    val verdict: AiVerdict,
    val convergencePercent: Double,
    val regime: MarketRegime,
    val entry: Double,
    val stop: Double?,
    val target: Double?,
    val outcome: SignalOutcome,
    val timeToOutcomeMillis: Long?,
    val maxFavorableExcursionPercent: Double?,
    val maxAdverseExcursionPercent: Double?
)

data class PerformanceBucket(val label: String, val sampleSize: Int, val wins: Int, val losses: Int, val noTrades: Int, val avgOutcomePercent: Double?, val expectancy: Double?) {
    val sampleTooSmall: Boolean get() = sampleSize < 20
}

data class BacktestResult(
    val symbol: String,
    val timeframe: String,
    val totalSignals: Int,
    val byRegime: List<PerformanceBucket>,
    val byConvergenceBucket: List<PerformanceBucket>,
    val overall: PerformanceBucket,
    val methodNote: String,
    val sampleTooSmall: Boolean
)

// ---- P8: Macro --------------------------------------------------------------

data class MacroSeriesPoint(val label: String, val value: Double?, val unit: String, val periodLabel: String?, val state: DataState, val sourceLabel: String, val asOfMillis: Long)
data class MacroSnapshot(val points: List<MacroSeriesPoint>)
