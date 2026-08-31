package com.vr3th.mediacompressor.market

import kotlin.math.abs
import java.util.Locale

// =============================================================================
// MARKET MODULE — CONFLUENCE / REGIME / RISK / SCENARIO / AI MARKET BRAIN
// =============================================================================
// This is a deterministic, rule-based expert system over REAL validated data
// (candles + the engines above) — there is no generative LLM in this app, so
// nothing here is a language model; it is called an "AI Market Brain" because
// it performs structured reasoning and produces an explainable verdict, not
// because it's a neural network. This is stated plainly in MARKET_MODULE.md.
//
// Categories are weighted, not equal-voted (spec Section 25): EMA20/50/200
// collapse into ONE "Trend" category instead of three correlated votes.
// Missing categories are simply absent from the vote — never a fake neutral
// or fake bearish/bullish stand-in (spec Section 25 + 43).
// =============================================================================

object MarketRegimeEngine {
    fun classify(structure: MarketStructureSnapshot, volatility: VolatilitySnapshot): MarketRegime {
        if (!structure.sufficientData) return MarketRegime.UNKNOWN
        val elevatedOrHigh = volatility.regime == VolatilityRegime.ELEVATED || volatility.regime == VolatilityRegime.HIGH
        return when (structure.trend) {
            StructureTrend.UPTREND -> if (structure.structureStrength > 0.66 && volatility.regime != VolatilityRegime.LOW) MarketRegime.STRONG_BULL else MarketRegime.WEAK_BULL
            StructureTrend.DOWNTREND -> if (structure.structureStrength > 0.66 && volatility.regime != VolatilityRegime.LOW) MarketRegime.STRONG_BEAR else MarketRegime.WEAK_BEAR
            StructureTrend.RANGE -> if ((structure.breakout == BreakoutState.BREAKOUT || structure.breakout == BreakoutState.BREAKDOWN) && elevatedOrHigh) MarketRegime.BREAKOUT_REGIME else MarketRegime.RANGE
            StructureTrend.TRANSITION -> MarketRegime.TRANSITION
        }
    }
}

object MarketConfluenceEngine {

    /** Combined trend vote — EMA20/50/200 counted ONCE, not three times (spec Section 10 example). */
    private fun trendEvidence(ind: IndicatorSnapshot): CategoryEvidence {
        val aligned = ind.ema20 > ind.ema50 && ind.ema50 > ind.ema200
        val alignedDown = ind.ema20 < ind.ema50 && ind.ema50 < ind.ema200
        val direction = if (aligned) 1 else if (alignedDown) -1 else 0
        val weight = if (aligned || alignedDown) 1.0 else 0.4 // mixed EMA stack = weak/no trend evidence, not a coin-flip
        val note = if (aligned) "EMA20>EMA50>EMA200 (bullish stack)" else if (alignedDown) "EMA20<EMA50<EMA200 (bearish stack)" else "EMA stack mixed — no clear trend alignment"
        return CategoryEvidence(ConfluenceCategory.TREND, direction, weight, note)
    }

    private fun structureEvidence(s: MarketStructureSnapshot): CategoryEvidence? {
        if (!s.sufficientData) return null
        val direction = when {
            s.trend == StructureTrend.UPTREND && (s.lastEvent == StructureEvent.BOS_BULLISH || s.lastEvent == StructureEvent.CHOCH_BULLISH) -> 1
            s.trend == StructureTrend.UPTREND -> 1
            s.trend == StructureTrend.DOWNTREND && (s.lastEvent == StructureEvent.BOS_BEARISH || s.lastEvent == StructureEvent.CHOCH_BEARISH) -> -1
            s.trend == StructureTrend.DOWNTREND -> -1
            else -> 0
        }
        val weight = if (s.lastEvent != StructureEvent.NONE) 1.0 else 0.6
        val note = "Structure: ${s.trend}, last event: ${s.lastEvent}, breakout: ${s.breakout}"
        return CategoryEvidence(ConfluenceCategory.STRUCTURE, direction, weight, note)
    }

    /** RSI+MACD combined, with trend context — not a bare RSI<30/>70 threshold (spec Section 11/12). */
    private fun momentumEvidence(ind: IndicatorSnapshot, trend: StructureTrend): CategoryEvidence {
        var direction = if (ind.macdHist > 0) 1 else -1
        var weight = 0.8
        val note: String
        when {
            ind.rsi14 > 75 && trend == StructureTrend.UPTREND -> { weight = 0.4; note = "RSI ${"%.0f".format(ind.rsi14)} extended within an uptrend — momentum still positive but stretched" }
            ind.rsi14 < 25 && trend == StructureTrend.DOWNTREND -> { weight = 0.4; note = "RSI ${"%.0f".format(ind.rsi14)} extended within a downtrend — momentum still negative but stretched" }
            ind.rsi14 > 70 && trend != StructureTrend.UPTREND -> { direction = -1; note = "RSI ${"%.0f".format(ind.rsi14)} overbought outside a confirmed uptrend" }
            ind.rsi14 < 30 && trend != StructureTrend.DOWNTREND -> { direction = 1; note = "RSI ${"%.0f".format(ind.rsi14)} oversold outside a confirmed downtrend" }
            else -> note = "MACD histogram ${if (ind.macdHist > 0) "positive" else "negative"}, RSI ${"%.0f".format(ind.rsi14)} neutral zone"
        }
        return CategoryEvidence(ConfluenceCategory.MOMENTUM, direction, weight, note)
    }

    private fun volumeEvidence(vol: VolatilitySnapshot, ind: IndicatorSnapshot, priceUp: Boolean): CategoryEvidence? {
        val rvol = vol.rvol ?: ind.volumeDeltaPercent?.let { 1.0 + it / 100.0 } ?: return null
        val direction = when {
            rvol > 1.3 && priceUp -> 1
            rvol > 1.3 && !priceUp -> -1
            else -> 0
        }
        val weight = if (rvol > 1.3 || rvol < 0.7) 0.7 else 0.3
        val note = "Relative volume ${"%.2f".format(rvol)}x recent average"
        return CategoryEvidence(ConfluenceCategory.VOLUME, direction, weight, note)
    }

    private fun vwapEvidence(vol: VolatilitySnapshot): CategoryEvidence? {
        val above = vol.priceAboveVwap ?: return null
        return CategoryEvidence(ConfluenceCategory.VWAP, if (above) 1 else -1, 0.5, if (above) "Price above VWAP — intraday bullish bias" else "Price below VWAP — intraday bearish bias")
    }

    private fun srEvidence(levels: List<SRLevel>): CategoryEvidence? {
        val nearest = levels.filter { abs(it.distancePercent) < 2.0 }.minByOrNull { abs(it.distancePercent) } ?: return null
        val direction = if (nearest.type == SRLevelType.RESISTANCE) -1 else 1
        val weight = 0.5 + nearest.strengthScore * 0.3
        val note = "Price is ${"%.1f".format(abs(nearest.distancePercent))}% from a ${if (nearest.strengthScore > 0.5) "strong" else "moderate"} ${nearest.type.name.lowercase()} level"
        return CategoryEvidence(ConfluenceCategory.SUPPORT_RESISTANCE, direction, weight, note)
    }

    private fun divergenceEvidence(d: DivergenceSnapshot): CategoryEvidence? {
        if (!d.sufficientData) return null
        return when {
            d.rsiBearishDivergence || d.macdBearishDivergence -> CategoryEvidence(ConfluenceCategory.DIVERGENCE, -1, 0.9, "Bearish divergence between price and momentum on recent swing highs")
            d.rsiBullishDivergence || d.macdBullishDivergence -> CategoryEvidence(ConfluenceCategory.DIVERGENCE, 1, 0.9, "Bullish divergence between price and momentum on recent swing lows")
            else -> null
        }
    }

    // ---- Phase 2 evidence categories (crypto-only real data — see MarketExtendedProviders.kt) ----

    private fun orderFlowEvidence(ob: OrderBookSnapshot?): CategoryEvidence? {
        if (ob == null || ob.state != DataState.LIVE) return null
        val imbalance = ob.imbalancePercent ?: return null
        val direction = when { imbalance > 10.0 -> 1; imbalance < -10.0 -> -1; else -> 0 }
        val weight = if (abs(imbalance) > 10.0) 0.5 else 0.2
        return CategoryEvidence(ConfluenceCategory.ORDER_FLOW, direction, weight, "Order book imbalance ${"%.1f".format(imbalance)}% (positive = bid-heavy)")
    }

    /** Funding is positioning/crowding context, not a directional call by itself — extreme funding leans mildly
     * contrarian, matching spec Section 12 ("do not automatically translate extreme funding into BUY or SELL"). */
    private fun derivativesEvidence(d: DerivativesSnapshot?): CategoryEvidence? {
        if (d == null || d.state != DataState.LIVE) return null
        val funding = d.fundingRatePercent ?: return null
        val direction = when { funding > 0.05 -> -1; funding < -0.05 -> 1; else -> 0 }
        val weight = if (abs(funding) > 0.05) 0.35 else 0.15
        val note = when {
            funding > 0.05 -> "Funding ${"%.3f".format(funding)}% — crowded longs (mild contrarian caution)"
            funding < -0.05 -> "Funding ${"%.3f".format(funding)}% — crowded shorts (mild contrarian caution)"
            else -> "Funding ${"%.3f".format(funding)}% — neutral positioning"
        }
        return CategoryEvidence(ConfluenceCategory.DERIVATIVES, direction, weight, note)
    }

    /** Market-wide (not per-asset) Fear & Greed — a light contrarian nudge only, per spec Section 19
     * ("do not make sentiment the sole signal"). */
    private fun sentimentEvidence(s: SentimentSnapshot?): CategoryEvidence? {
        if (s == null || s.state != DataState.LIVE) return null
        val v = s.fearGreedValue ?: return null
        val direction = when { v <= 20 -> 1; v >= 80 -> -1; else -> 0 }
        val weight = if (v <= 20 || v >= 80) 0.3 else 0.1
        return CategoryEvidence(ConfluenceCategory.SENTIMENT, direction, weight, "Market-wide Fear & Greed = $v (${s.fearGreedLabel ?: "n/a"})")
    }

    /** Higher-timeframe context weighted well above lower-timeframe confirmation, never equal (spec Section 8).
     * A CONFLICTING alignment contributes no vote here — it is surfaced as a contradiction instead (see below). */
    private fun multiTimeframeEvidence(m: MultiTimeframeSnapshot?): CategoryEvidence? = when (m?.alignment) {
        TimeframeAlignment.ALIGNED_BULLISH -> CategoryEvidence(ConfluenceCategory.MULTI_TIMEFRAME, 1, 1.2, m.note)
        TimeframeAlignment.ALIGNED_BEARISH -> CategoryEvidence(ConfluenceCategory.MULTI_TIMEFRAME, -1, 1.2, m.note)
        else -> null
    }

    // ---- Phase 3 evidence categories ----

    /** Large one-sided liquidations are context (capitulation-style flushes often precede a bounce, per common
     * market observation), never an automatic reversal call — spec Section 7 is explicit about this, hence the
     * low weight and the "context" framing in the note. */
    private fun liquidationsEvidence(l: LiquidationSnapshot?): CategoryEvidence? {
        if (l == null || l.state != DataState.LIVE || l.recent.isEmpty()) return null
        val total = l.rollingLongNotional + l.rollingShortNotional
        if (total <= 0) return null
        val imbalance = (l.rollingLongNotional - l.rollingShortNotional) / total // positive = more LONGS liquidated
        val direction = when { imbalance > 0.3 -> 1; imbalance < -0.3 -> -1; else -> 0 } // heavy long liquidations -> mild contrarian-bullish context
        val weight = if (l.spikeDetected) 0.3 else 0.15
        return CategoryEvidence(ConfluenceCategory.LIQUIDATIONS, direction, weight, "Rolling liquidations skew ${"%.0f".format(imbalance * 100)}% toward ${if (imbalance > 0) "longs" else "shorts"} being liquidated${if (l.spikeDetected) " (spike detected)" else ""}")
    }

    /** Put/call OI ratio as positioning context only — spec Section 6 explicitly forbids letting options alone
     * determine BUY/SELL, hence the low weight and framing as positioning, not direction. */
    private fun optionsEvidence(o: OptionsSnapshot?): CategoryEvidence? {
        if (o == null || o.state != DataState.LIVE) return null
        val ratio = o.putCallOiRatioDerived ?: return null
        val direction = when { ratio > 1.3 -> 1; ratio < 0.7 -> -1; else -> 0 } // heavy put OI -> mild contrarian-bullish context
        val weight = if (ratio > 1.3 || ratio < 0.7) 0.25 else 0.1
        return CategoryEvidence(ConfluenceCategory.OPTIONS, direction, weight, "Put/Call OI ratio ${"%.2f".format(ratio)} (DERIVED from ${o.contractsConsidered} contracts)")
    }

    private const val TOTAL_POSSIBLE_CATEGORIES = 13.0 // Trend, Structure, Momentum, Volume, VWAP, S/R, Divergence, Order Flow, Derivatives, Sentiment, MTF, Liquidations, Options (Volatility is context, not a vote)

    fun analyze(
        quote: MarketQuote, structure: MarketStructureSnapshot, sr: List<SRLevel>, volatility: VolatilitySnapshot, divergence: DivergenceSnapshot,
        orderBook: OrderBookSnapshot? = null, derivatives: DerivativesSnapshot? = null, sentiment: SentimentSnapshot? = null, multiTimeframe: MultiTimeframeSnapshot? = null,
        liquidations: LiquidationSnapshot? = null, options: OptionsSnapshot? = null
    ): ConfluenceResult {
        val ind = quote.indicators
        val evidences = ArrayList<CategoryEvidence>()
        if (ind != null) {
            evidences.add(trendEvidence(ind))
            evidences.add(momentumEvidence(ind, structure.trend))
            volumeEvidence(volatility, ind, quote.changePercent >= 0)?.let { evidences.add(it) }
        }
        structureEvidence(structure)?.let { evidences.add(it) }
        vwapEvidence(volatility)?.let { evidences.add(it) }
        srEvidence(sr)?.let { evidences.add(it) }
        divergenceEvidence(divergence)?.let { evidences.add(it) }
        orderFlowEvidence(orderBook)?.let { evidences.add(it) }
        derivativesEvidence(derivatives)?.let { evidences.add(it) }
        sentimentEvidence(sentiment)?.let { evidences.add(it) }
        multiTimeframeEvidence(multiTimeframe)?.let { evidences.add(it) }
        liquidationsEvidence(liquidations)?.let { evidences.add(it) }
        optionsEvidence(options)?.let { evidences.add(it) }

        val totalWeight = evidences.sumOf { it.weight }
        val netScore = if (totalWeight > 0) (evidences.sumOf { it.direction * it.weight } / totalWeight).coerceIn(-1.0, 1.0) else 0.0

        val contradictions = ArrayList<String>()
        if (ind != null) {
            if (structure.trend == StructureTrend.UPTREND && ind.rsi14 > 75) contradictions.add("Trend remains bullish, but momentum is extended (RSI ${"%.0f".format(ind.rsi14)})")
            if (structure.trend == StructureTrend.DOWNTREND && ind.rsi14 < 25) contradictions.add("Trend remains bearish, but momentum is extended (RSI ${"%.0f".format(ind.rsi14)})")
        }
        if (structure.trend == StructureTrend.UPTREND && (volatility.rvol != null && volatility.rvol < 0.8)) contradictions.add("Uptrend not confirmed by volume — relative volume is below average")
        if (structure.trend == StructureTrend.DOWNTREND && (volatility.rvol != null && volatility.rvol < 0.8)) contradictions.add("Downtrend not confirmed by volume — relative volume is below average")
        sr.filter { abs(it.distancePercent) < 1.5 }.forEach { level ->
            if (structure.trend == StructureTrend.UPTREND && level.type == SRLevelType.RESISTANCE) contradictions.add("Resistance nearby (%.2f) limits immediate upside room".format(level.price))
            if (structure.trend == StructureTrend.DOWNTREND && level.type == SRLevelType.SUPPORT) contradictions.add("Support nearby (%.2f) limits immediate downside room".format(level.price))
        }
        if (divergence.sufficientData) {
            if (structure.trend == StructureTrend.UPTREND && (divergence.rsiBearishDivergence || divergence.macdBearishDivergence)) contradictions.add("Bearish momentum divergence conflicts with the prevailing uptrend")
            if (structure.trend == StructureTrend.DOWNTREND && (divergence.rsiBullishDivergence || divergence.macdBullishDivergence)) contradictions.add("Bullish momentum divergence conflicts with the prevailing downtrend")
        }
        if (multiTimeframe?.alignment == TimeframeAlignment.CONFLICTING) {
            contradictions.add("Higher and lower timeframes disagree: ${multiTimeframe.higherTrend} on ${multiTimeframe.higherTimeframeLabel} vs ${multiTimeframe.lowerTrend} on ${multiTimeframe.lowerTimeframeLabel}")
        }
        val fundingRate = derivatives?.fundingRatePercent
        if (derivatives?.state == DataState.LIVE && fundingRate != null && abs(fundingRate) > 0.05 && sr.any { abs(it.distancePercent) < 1.5 }) {
            contradictions.add("Extreme funding (crowded positioning) coincides with a nearby key level — reversal risk is elevated")
        }
        val putCallRatio = options?.putCallOiRatioDerived
        if (options?.state == DataState.LIVE && putCallRatio != null) {
            if (structure.trend == StructureTrend.UPTREND && putCallRatio > 1.3) contradictions.add("Bullish trend, but options positioning shows elevated put OI (DERIVED put/call ${"%.2f".format(putCallRatio)})")
            if (structure.trend == StructureTrend.DOWNTREND && putCallRatio < 0.7) contradictions.add("Bearish trend, but options positioning shows elevated call OI (DERIVED put/call ${"%.2f".format(putCallRatio)})")
        }

        val dataCompleteness = ((evidences.size / TOTAL_POSSIBLE_CATEGORIES) * 100.0).coerceIn(0.0, 100.0)
        val magnitude = abs(netScore)
        val baseConfidence = 50.0 + magnitude * 45.0
        val completenessFactor = (dataCompleteness / 100.0).coerceIn(0.35, 1.0)
        val convergence = (50.0 + (baseConfidence - 50.0) * completenessFactor - contradictions.size * 4.0).coerceIn(30.0, 95.0)

        return ConfluenceResult(evidences, netScore, convergence, contradictions, dataCompleteness)
    }
}

object MarketRiskEngine {
    /** Real, level-derived plan — falls back to measured ATR only when no structural level exists on that side; never a formula driven by signal strength (spec Section 33). */
    fun plan(entry: Double, direction: Int, sr: List<SRLevel>, atr: Double?): RiskPlan? {
        if (direction == 0) return null
        val supports = sr.filter { it.type == SRLevelType.SUPPORT && it.price < entry }.sortedByDescending { it.price }
        val resistances = sr.filter { it.type == SRLevelType.RESISTANCE && it.price > entry }.sortedBy { it.price }

        val stop: Double; val target: Double; val basis: String
        if (direction > 0) {
            val structuralStop = supports.firstOrNull()?.price
            val structuralTarget = resistances.firstOrNull()?.price
            stop = structuralStop ?: (atr?.let { entry - 1.5 * it })  ?: return null
            target = structuralTarget ?: (atr?.let { entry + 2.0 * it }) ?: return null
            basis = "Stop: ${if (structuralStop != null) "nearest swing/pivot support" else "1.5x ATR (no structural support in range)"}; Target: ${if (structuralTarget != null) "nearest swing/pivot resistance" else "2x ATR"}"
        } else {
            val structuralStop = resistances.firstOrNull()?.price
            val structuralTarget = supports.firstOrNull()?.price
            stop = structuralStop ?: (atr?.let { entry + 1.5 * it }) ?: return null
            target = structuralTarget ?: (atr?.let { entry - 2.0 * it }) ?: return null
            basis = "Stop: ${if (structuralStop != null) "nearest swing/pivot resistance" else "1.5x ATR (no structural resistance in range)"}; Target: ${if (structuralTarget != null) "nearest swing/pivot support" else "2x ATR"}"
        }
        val risk = abs(entry - stop)
        val reward = abs(target - entry)
        if (risk <= 0.0 || reward <= 0.0) return null
        return RiskPlan(entry, stop, target, risk, reward, reward / risk, basis, valid = true)
    }
}

object MarketScenarioEngine {
    fun build(entry: Double, sr: List<SRLevel>, verdict: AiVerdict, indonesian: Boolean): ScenarioSet {
        val resistance = sr.filter { it.type == SRLevelType.RESISTANCE }.minByOrNull { it.distancePercent }
        val support = sr.filter { it.type == SRLevelType.SUPPORT }.maxByOrNull { it.distancePercent }
        fun bi(id: String, en: String) = if (indonesian) id else en

        val bullish = if (resistance != null)
            bi("Kelanjutan bullish membutuhkan penutupan yang jelas di atas ${"%.2f".format(resistance.price)} disertai volume yang kuat.",
               "Bullish continuation needs a confirmed close above ${"%.2f".format(resistance.price)} with strong volume.")
        else bi("Kelanjutan bullish membutuhkan momentum dan struktur yang tetap positif; belum ada level resistance jelas dari data yang tersedia.",
               "Bullish continuation needs momentum and structure to stay positive; no clear resistance level from the available data.")

        val bearish = if (support != null)
            bi("Kelanjutan bearish membutuhkan penutupan yang jelas di bawah ${"%.2f".format(support.price)}.",
               "Bearish continuation needs a confirmed close below ${"%.2f".format(support.price)}.")
        else bi("Kelanjutan bearish membutuhkan momentum dan struktur yang tetap negatif; belum ada level support jelas dari data yang tersedia.",
               "Bearish continuation needs momentum and structure to stay negative; no clear support level from the available data.")

        val invalidation = when {
            verdict == AiVerdict.STRONG_BUY || verdict == AiVerdict.BUY -> support?.let { bi("Penutupan di bawah ${"%.2f".format(it.price)} membatalkan tesis bullish.", "A close below ${"%.2f".format(it.price)} invalidates the bullish thesis.") }
                ?: bi("Tidak ada level struktural jelas untuk invalidasi dari data yang tersedia.", "No clear structural invalidation level from the available data.")
            verdict == AiVerdict.STRONG_SELL || verdict == AiVerdict.SELL -> resistance?.let { bi("Penutupan di atas ${"%.2f".format(it.price)} membatalkan tesis bearish.", "A close above ${"%.2f".format(it.price)} invalidates the bearish thesis.") }
                ?: bi("Tidak ada level struktural jelas untuk invalidasi dari data yang tersedia.", "No clear structural invalidation level from the available data.")
            else -> bi("Tunggu penutupan yang jelas di luar rentang saat ini untuk konfirmasi arah.", "Wait for a confirmed close outside the current range for directional confirmation.")
        }

        val wait = if (verdict == AiVerdict.NEUTRAL_WAIT || verdict == AiVerdict.NO_TRADE)
            bi("Konfirmasi tambahan diperlukan — breakout tervalidasi volume atau retest yang bertahan sebelum entry.", "Additional confirmation needed — a volume-validated breakout or a held retest before entry.")
        else null

        return ScenarioSet(bullish, bearish, invalidation, wait)
    }
}

object MarketAnalysisEngine {

    /** Section 27: data quality (freshness + completeness) must cap confidence, not just netScore. A stale/cached
     * quote can never be called HIGH quality, however clean the technical picture looks. */
    internal fun signalQuality(confluence: ConfluenceResult, risk: RiskPlan?, dataState: DataState): SignalQuality {
        if (confluence.dataCompletenessPercent < 25.0) return SignalQuality.NO_VALID_SETUP
        val freshEnoughForHigh = dataState == DataState.LIVE || dataState == DataState.DELAYED
        return when {
            confluence.convergencePercent >= 75.0 && confluence.contradictions.isEmpty() && risk?.valid == true && risk.riskRewardRatio >= 1.5 && freshEnoughForHigh -> SignalQuality.HIGH
            confluence.convergencePercent >= 60.0 -> SignalQuality.MEDIUM
            else -> SignalQuality.LOW
        }
    }

    internal fun rawVerdict(netScore: Double): AiVerdict = when {
        netScore >= 0.6 -> AiVerdict.STRONG_BUY
        netScore >= 0.2 -> AiVerdict.BUY
        netScore <= -0.6 -> AiVerdict.STRONG_SELL
        netScore <= -0.2 -> AiVerdict.SELL
        else -> AiVerdict.NEUTRAL_WAIT
    }

    /** Section 30/31: contradictions or a low-data setup can downgrade a raw threshold verdict — the system must be able to say WAIT or NO TRADE. */
    internal fun finalVerdict(raw: AiVerdict, quality: SignalQuality, hasContradictions: Boolean): AiVerdict {
        if (quality == SignalQuality.NO_VALID_SETUP) return AiVerdict.NO_TRADE
        if (!hasContradictions) return raw
        return when (raw) {
            AiVerdict.STRONG_BUY -> AiVerdict.BUY
            AiVerdict.BUY -> AiVerdict.NEUTRAL_WAIT
            AiVerdict.STRONG_SELL -> AiVerdict.SELL
            AiVerdict.SELL -> AiVerdict.NEUTRAL_WAIT
            else -> raw
        }
    }

    internal fun verdictDirection(v: AiVerdict): Int = when (v) {
        AiVerdict.STRONG_BUY, AiVerdict.BUY -> 1
        AiVerdict.STRONG_SELL, AiVerdict.SELL -> -1
        else -> 0
    }

    private fun verdictLabel(v: AiVerdict): String = when (v) {
        AiVerdict.STRONG_BUY -> "AKUMULASI KUAT / STRONG BUY"
        AiVerdict.BUY -> "AKUMULASI / BUY"
        AiVerdict.NEUTRAL_WAIT -> "NETRAL — TUNGGU / NEUTRAL — WAIT & SEE"
        AiVerdict.SELL -> "DISTRIBUSI / SELL"
        AiVerdict.STRONG_SELL -> "DISTRIBUSI KUAT / STRONG SELL"
        AiVerdict.NO_TRADE -> "TIDAK ADA POSISI / NO TRADE — KONFIRMASI TIDAK CUKUP"
    }

    /** Section 25: connect the evidence into prose instead of dumping indicator values — "RSI is 61, MACD is
     * positive" is explicitly the BAD example the spec gives; this produces the GOOD-example style instead. */
    private fun buildReasoningParagraph(
        structure: MarketStructureSnapshot, confluence: ConfluenceResult, multiTimeframe: MultiTimeframeSnapshot?,
        verdict: AiVerdict, indonesian: Boolean
    ): String {
        fun bi(id: String, en: String) = if (indonesian) id else en
        val trendPhrase = when {
            !structure.sufficientData -> bi("Data historis belum cukup untuk menilai struktur pasar secara andal", "There isn't yet enough history to assess market structure reliably")
            structure.trend == StructureTrend.UPTREND -> bi("Struktur pasar tetap bullish, dengan pola higher-high dan higher-low yang konsisten", "Market structure remains bullish, with a consistent pattern of higher highs and higher lows")
            structure.trend == StructureTrend.DOWNTREND -> bi("Struktur pasar tetap bearish, dengan pola lower-high dan lower-low yang konsisten", "Market structure remains bearish, with a consistent pattern of lower highs and lower lows")
            structure.trend == StructureTrend.RANGE -> bi("Pasar sedang bergerak menyamping dalam rentang tanpa arah yang jelas", "The market is moving sideways within a range, with no clear directional bias")
            else -> bi("Struktur pasar sedang dalam masa transisi dan belum jelas arahnya", "Market structure is in transition and not yet clearly directional")
        }
        val supportNotes = confluence.evidences.filter { it.direction == if (verdict == AiVerdict.STRONG_SELL || verdict == AiVerdict.SELL) -1 else 1 }
            .sortedByDescending { it.weight }.take(2).map { it.note }
        val supportingPhrase = if (supportNotes.isNotEmpty())
            bi(", sementara ${supportNotes.joinToString(" dan ")} turut menegaskan arah ini", ", while ${supportNotes.joinToString(" and ")} reinforce this direction")
        else ""
        val mtfPhrase = when (multiTimeframe?.alignment) {
            TimeframeAlignment.ALIGNED_BULLISH -> bi(" Kerangka waktu lebih tinggi dan lebih rendah sama-sama bullish, memperkuat keyakinan.", " Both the higher and lower timeframes are bullish, reinforcing conviction.")
            TimeframeAlignment.ALIGNED_BEARISH -> bi(" Kerangka waktu lebih tinggi dan lebih rendah sama-sama bearish, memperkuat keyakinan.", " Both the higher and lower timeframes are bearish, reinforcing conviction.")
            TimeframeAlignment.CONFLICTING -> bi(" Namun kerangka waktu lebih tinggi dan lebih rendah saling bertentangan, sehingga sinyal ini belum selaras di semua kerangka waktu.", " However, the higher and lower timeframes disagree, so this signal is not yet aligned across timeframes.")
            else -> ""
        }
        val caveat = confluence.contradictions.firstOrNull()?.let { bi("Namun, $it. ", "However, $it. ") } ?: ""
        val conclusion = when (verdict) {
            AiVerdict.STRONG_BUY -> bi("Ini adalah setup bullish yang kuat dengan konfirmasi luas dari beberapa kategori bukti independen.", "This is a strong bullish setup with broad confirmation across several independent evidence categories.")
            AiVerdict.BUY -> bi("Setup ini bullish, tetapi belum cukup kuat untuk entry yang agresif — konfirmasi tambahan akan memperkuat keyakinan.", "The setup is bullish, but not yet strong enough for an aggressive entry — additional confirmation would strengthen conviction.")
            AiVerdict.NEUTRAL_WAIT -> bi("Bukti yang ada saat ini bercampur atau belum cukup — menunggu konfirmasi lebih lanjut adalah pendekatan yang lebih bijak daripada memaksakan posisi.", "The evidence right now is mixed or insufficient — waiting for further confirmation is wiser than forcing a position.")
            AiVerdict.SELL -> bi("Setup ini bearish, tetapi belum cukup kuat untuk entry yang agresif — konfirmasi tambahan akan memperkuat keyakinan.", "The setup is bearish, but not yet strong enough for an aggressive entry — additional confirmation would strengthen conviction.")
            AiVerdict.STRONG_SELL -> bi("Ini adalah setup bearish yang kuat dengan konfirmasi luas dari beberapa kategori bukti independen.", "This is a strong bearish setup with broad confirmation across several independent evidence categories.")
            AiVerdict.NO_TRADE -> bi("Kualitas data atau kekuatan sinyal saat ini tidak cukup untuk merekomendasikan posisi apa pun secara bertanggung jawab.", "Data quality or signal strength right now is not sufficient to responsibly recommend any position.")
        }
        return "$trendPhrase$supportingPhrase.$mtfPhrase $caveat$conclusion"
    }

    private fun buildNarrative(
        quote: MarketQuote, regime: MarketRegime, structure: MarketStructureSnapshot, sr: List<SRLevel>,
        volatility: VolatilitySnapshot, confluence: ConfluenceResult, quality: SignalQuality, risk: RiskPlan?,
        verdict: AiVerdict, indonesian: Boolean, orderBook: OrderBookSnapshot?, derivatives: DerivativesSnapshot?,
        sentiment: SentimentSnapshot?, multiTimeframe: MultiTimeframeSnapshot?, liquidations: LiquidationSnapshot?,
        options: OptionsSnapshot?, volumeProfile: VolumeProfileSnapshot?, fundamentals: FundamentalsSnapshot?,
        correlation: CorrelationSnapshot?, macro: MacroSnapshot?
    ): String {
        fun bi(id: String, en: String) = if (indonesian) id else en
        val sb = StringBuilder()
        sb.append(bi("VERDIKT: ", "VERDICT: ")).append(verdictLabel(verdict)).append('\n')
        sb.append(bi("KONVERGENSI: ", "CONVERGENCE: ")).append(String.format(Locale.US, "%.0f%%", confluence.convergencePercent))
            .append(bi(" (kelengkapan data ", " (data completeness ")).append(String.format(Locale.US, "%.0f%%)", confluence.dataCompletenessPercent)).append('\n')
        if (quote.state == DataState.STALE || quote.state == DataState.CACHED) {
            sb.append(bi("CATATAN KUALITAS DATA: harga bukan LIVE (", "DATA QUALITY NOTE: price is not LIVE ("))
                .append(quote.state.name).append(bi(") — keyakinan diturunkan.\n", ") — confidence has been reduced.\n"))
        }
        sb.append(bi("REZIM PASAR: ", "MARKET REGIME: ")).append(regime.name.replace('_', ' ')).append('\n')
        sb.append(bi("STRUKTUR: ", "STRUCTURE: ")).append(if (structure.sufficientData) "${structure.trend} — ${structure.lastEvent}, ${structure.breakout}" else bi("data historis tidak cukup", "insufficient historical data")).append('\n')
        val nearestSupport = sr.filter { it.type == SRLevelType.SUPPORT }.minByOrNull { abs(it.distancePercent) }
        val nearestResistance = sr.filter { it.type == SRLevelType.RESISTANCE }.minByOrNull { abs(it.distancePercent) }
        sb.append(bi("SUPPORT TERDEKAT: ", "NEAREST SUPPORT: ")).append(nearestSupport?.let { String.format(Locale.US, "%.4g", it.price) } ?: bi("tidak tersedia", "unavailable")).append('\n')
        sb.append(bi("RESISTANCE TERDEKAT: ", "NEAREST RESISTANCE: ")).append(nearestResistance?.let { String.format(Locale.US, "%.4g", it.price) } ?: bi("tidak tersedia", "unavailable")).append('\n')
        if (volumeProfile != null && volumeProfile.sufficientData) {
            sb.append(bi("VPOC (DERIVED, dari candle): ", "VPOC (DERIVED, candle-based): ")).append(volumeProfile.pointOfControl?.let { String.format(Locale.US, "%.4g", it) } ?: bi("tidak tersedia", "unavailable")).append('\n')
        }
        sb.append(bi("VOLATILITAS (ATR): ", "VOLATILITY (ATR): ")).append(volatility.atrPercentOfPrice?.let { String.format(Locale.US, "%.1f%% dari harga — rezim %s", it, volatility.regime.name) } ?: bi("tidak tersedia", "unavailable")).append('\n')
        sb.append("VWAP: ").append(volatility.vwap?.let { bi(if (volatility.priceAboveVwap == true) "harga di atas VWAP" else "harga di bawah VWAP", if (volatility.priceAboveVwap == true) "price above VWAP" else "price below VWAP") } ?: bi("tidak tersedia (tanpa data volume per-candle)", "unavailable (no per-candle volume data)")).append('\n')
        if (multiTimeframe != null) {
            sb.append(bi("KESELARASAN MULTI-TIMEFRAME: ", "MULTI-TIMEFRAME ALIGNMENT: ")).append(multiTimeframe.alignment.name.replace('_', ' ')).append('\n')
        }
        if (orderBook != null && orderBook.state == DataState.LIVE) {
            sb.append(bi("ORDER BOOK (OBSERVED): ", "ORDER BOOK (OBSERVED): ")).append(orderBook.imbalancePercent?.let { String.format(Locale.US, "imbalance %+.1f%% (%s)", it, orderBook.sourceLabel) } ?: bi("tidak lengkap", "incomplete")).append('\n')
        } else {
            sb.append(bi("ORDER BOOK: TIDAK TERSEDIA\n", "ORDER BOOK: UNAVAILABLE\n"))
        }
        if (derivatives != null && derivatives.state == DataState.LIVE) {
            sb.append(bi("DERIVATIF (OBSERVED): ", "DERIVATIVES (OBSERVED): ")).append(derivatives.fundingRatePercent?.let { String.format(Locale.US, "funding %.3f%%, OI %s (%s)", it, derivatives.openInterest?.let { oi -> String.format(Locale.US, "%.0f", oi) } ?: "?", derivatives.sourceLabel) } ?: bi("tidak lengkap", "incomplete")).append('\n')
        } else {
            sb.append(bi("DERIVATIF: TIDAK TERSEDIA\n", "DERIVATIVES: UNAVAILABLE\n"))
        }
        if (liquidations != null && liquidations.state == DataState.LIVE) {
            sb.append(bi("LIKUIDASI (OBSERVED, 5 mnt bergulir): ", "LIQUIDATIONS (OBSERVED, rolling 5-min): "))
                .append(String.format(Locale.US, "long \$%.0f / short \$%.0f%s (%s)", liquidations.rollingLongNotional, liquidations.rollingShortNotional, if (liquidations.spikeDetected) bi(" — LONJAKAN", " — SPIKE") else "", liquidations.sourceLabel)).append('\n')
        } else {
            sb.append(bi("LIKUIDASI: TIDAK TERSEDIA\n", "LIQUIDATIONS: UNAVAILABLE\n"))
        }
        if (options != null && options.state == DataState.LIVE) {
            sb.append(bi("OPSI (DERIVED, ", "OPTIONS (DERIVED, ")).append("${options.contractsConsidered} contracts): ")
                .append(String.format(Locale.US, "P/C OI %s, IV ctx %s",
                    options.putCallOiRatioDerived?.let { "%.2f".format(it) } ?: "n/a",
                    options.atmIvContextDerived?.let { "%.0f%%".format(it) } ?: "n/a")).append('\n')
        } else {
            sb.append(bi("OPSI: TIDAK TERSEDIA\n", "OPTIONS: UNAVAILABLE\n"))
        }
        if (sentiment != null && sentiment.state == DataState.LIVE && sentiment.fearGreedValue != null) {
            sb.append(bi("SENTIMEN (OBSERVED, Fear & Greed, pasar luas): ", "SENTIMENT (OBSERVED, Fear & Greed, market-wide): ")).append("${sentiment.fearGreedValue} (${sentiment.fearGreedLabel}) — Alternative.me\n")
        } else {
            sb.append(bi("SENTIMEN: TIDAK TERSEDIA\n", "SENTIMENT: UNAVAILABLE\n"))
        }
        if (fundamentals != null && fundamentals.state == DataState.LIVE && fundamentals.facts.isNotEmpty()) {
            sb.append(bi("FUNDAMENTAL (REPORTED via SEC EDGAR, konteks — bukan sinyal jangka pendek): ", "FUNDAMENTALS (REPORTED via SEC EDGAR, context only — not a short-term signal): "))
            sb.append(fundamentals.facts.take(3).joinToString("; ") { "${it.label}=${"%.3g".format(it.value ?: 0.0)}${if (it.basis == FundamentalBasis.CALCULATED) " [CALCULATED]" else ""}" }).append('\n')
        } else {
            sb.append(bi("FUNDAMENTAL: TIDAK TERSEDIA\n", "FUNDAMENTALS: UNAVAILABLE\n"))
        }
        if (correlation != null && correlation.state == DataState.LIVE && correlation.sufficientSamples) {
            sb.append(bi("KORELASI vs ${correlation.referenceInstrumentLabel} (DERIVED, n=${correlation.sampleSize}): ", "CORRELATION vs ${correlation.referenceInstrumentLabel} (DERIVED, n=${correlation.sampleSize}): "))
                .append(correlation.coefficient?.let { "r=%.2f (%s)".format(it, correlation.direction.name) } ?: "n/a").append('\n')
        } else {
            sb.append(bi("KORELASI LINTAS PASAR: TIDAK TERSEDIA / SAMPEL TERLALU KECIL\n", "CROSS-MARKET CORRELATION: UNAVAILABLE / SAMPLE TOO SMALL\n"))
        }
        if (macro != null && macro.points.any { it.state == DataState.LIVE }) {
            sb.append(bi("MAKRO (OBSERVED, konteks): ", "MACRO (OBSERVED, context): "))
            sb.append(macro.points.filter { it.state == DataState.LIVE }.joinToString("; ") { "${it.label}=${it.value?.let { v -> "%.2f".format(v) } ?: "n/a"}${it.unit}" }).append('\n')
        } else {
            sb.append(bi("MAKRO: TIDAK TERSEDIA\n", "MACRO: UNAVAILABLE\n"))
        }
        sb.append(bi("BERITA / ON-CHAIN: TIDAK TERSEDIA (tidak ada sumber gratis yang andal terhubung)\n", "NEWS / ON-CHAIN: UNAVAILABLE (no reliable free source connected)\n"))
        sb.append(bi("KUALITAS SINYAL: ", "SIGNAL QUALITY: ")).append(quality.name.replace('_', ' ')).append('\n')
        if (risk != null) {
            sb.append(bi("RISIKO/IMBALAN: ", "RISK/REWARD: ")).append(String.format(Locale.US, "1 : %.2f (%s)", risk.riskRewardRatio, risk.basis)).append('\n')
        } else {
            sb.append(bi("RISIKO/IMBALAN: ", "RISK/REWARD: ")).append(bi("tidak dihitung — tidak ada arah yang valid atau level nyata tidak cukup", "not calculated — no valid direction or insufficient real levels")).append('\n')
        }
        sb.append('\n').append(buildReasoningParagraph(structure, confluence, multiTimeframe, verdict, indonesian))
        if (confluence.contradictions.size > 1) {
            sb.append(bi("\n\nBukti bertentangan lainnya:\n", "\n\nOther conflicting evidence:\n"))
            confluence.contradictions.drop(1).forEach { sb.append("* ").append(it).append('\n') }
        }
        return sb.toString()
    }

    /**
     * Full pipeline: structure -> S/R -> volume profile -> volatility -> divergence -> confluence ->
     * risk -> regime -> verdict -> scenarios -> narrative. Returns null only when there are too few
     * candles to say anything responsible at all.
     *
     * Phase 2/3 datasets ([orderBook]/[derivatives]/[sentiment]/[multiTimeframe]/[liquidations]/
     * [options]/[fundamentals]/[correlation]/[macro]) are all optional — the instrument detail
     * screen fetches them and passes them in; the favorites list does not, to keep the list
     * lightweight (spec Section 37). Fundamentals/correlation/macro are informational context only
     * and never enter the confluence vote (per spec Sections 9, 20, and repeated warnings against
     * treating any single non-technical dataset as a direct BUY/SELL signal).
     */
    fun analyze(
        quote: MarketQuote, indonesian: Boolean,
        orderBook: OrderBookSnapshot? = null, derivatives: DerivativesSnapshot? = null,
        sentiment: SentimentSnapshot? = null, multiTimeframe: MultiTimeframeSnapshot? = null,
        liquidations: LiquidationSnapshot? = null, options: OptionsSnapshot? = null,
        fundamentals: FundamentalsSnapshot? = null, correlation: CorrelationSnapshot? = null, macro: MacroSnapshot? = null
    ): MarketAnalysis? {
        val candles = quote.candles
        if (candles.size < 15) return null

        val structure = MarketStructureEngine.analyze(candles)
        val sr = MarketSupportResistanceEngine.analyze(candles, structure, quote.indicators)
        val volumeProfile = MarketVolumeProfileEngine.analyze(candles)
        val volatility = MarketVolatilityEngine.analyze(candles)
        val divergence = MarketDivergenceEngine.analyze(candles, structure)
        val confluence = MarketConfluenceEngine.analyze(quote, structure, sr, volatility, divergence, orderBook, derivatives, sentiment, multiTimeframe, liquidations, options)
        val regime = MarketRegimeEngine.classify(structure, volatility)

        val raw = rawVerdict(confluence.netScore)
        val direction = verdictDirection(raw)
        val risk = MarketRiskEngine.plan(quote.lastPrice, direction, sr, volatility.atr14)
        val finalQuality = signalQuality(confluence, risk, quote.state)
        val verdict = finalVerdict(raw, finalQuality, confluence.contradictions.isNotEmpty())
        val scenarios = if (structure.sufficientData) MarketScenarioEngine.build(quote.lastPrice, sr, verdict, indonesian) else null
        val narrative = buildNarrative(
            quote, regime, structure, sr, volatility, confluence, finalQuality, if (verdictDirection(verdict) != 0) risk else null,
            verdict, indonesian, orderBook, derivatives, sentiment, multiTimeframe, liquidations, options, volumeProfile, fundamentals, correlation, macro
        )

        return MarketAnalysis(
            regime = regime, structure = structure, supportResistance = sr, volatility = volatility,
            divergence = divergence, confluence = confluence, signalQuality = finalQuality,
            risk = if (verdictDirection(verdict) != 0) risk else null, verdict = verdict, scenarios = scenarios, narrative = narrative,
            orderBook = orderBook, derivatives = derivatives, sentiment = sentiment, multiTimeframe = multiTimeframe,
            options = options, liquidations = liquidations, volumeProfile = volumeProfile,
            fundamentals = fundamentals, correlation = correlation, macro = macro
        )
    }

    internal data class QuickDecision(val verdict: AiVerdict, val convergencePercent: Double, val regime: MarketRegime, val entry: Double, val stop: Double?, val target: Double?)

    /**
     * Same decision pipeline as [analyze], computed ONLY from the [candles] passed in — used by
     * [MarketBacktestEngine] so a historical decision point can never see data past its own index
     * (no look-ahead). Skips narrative/scenario text generation since a backtest loop calls this
     * many times and that string work isn't needed for scoring an outcome.
     */
    internal fun quickDecision(candles: List<Candle>): QuickDecision? {
        if (candles.size < 15) return null
        val indicators = MarketIndicators.compute(candles) ?: return null
        val last = candles.last()
        val prev = candles[candles.size - 2]
        val changePercent = if (prev.close != 0.0) ((last.close - prev.close) / prev.close) * 100.0 else 0.0
        val pseudoInstrument = MarketInstrument("BACKTEST", "Backtest", AssetClass.CRYPTO, "n/a", "n/a", "USD", "backtest", "backtest")
        val pseudoQuote = MarketQuote(
            instrument = pseudoInstrument, state = DataState.CACHED, sourceLabel = "backtest", asOfMillis = last.timeMillis,
            lastPrice = last.close, changePercent = changePercent, high = last.high, low = last.low, volume = last.volume,
            candles = candles, indicators = indicators, verdict = null
        )
        val structure = MarketStructureEngine.analyze(candles)
        val sr = MarketSupportResistanceEngine.analyze(candles, structure, indicators)
        val volatility = MarketVolatilityEngine.analyze(candles)
        val divergence = MarketDivergenceEngine.analyze(candles, structure)
        val confluence = MarketConfluenceEngine.analyze(pseudoQuote, structure, sr, volatility, divergence)
        val regime = MarketRegimeEngine.classify(structure, volatility)
        val raw = rawVerdict(confluence.netScore)
        val direction = verdictDirection(raw)
        val risk = MarketRiskEngine.plan(last.close, direction, sr, volatility.atr14)
        val quality = signalQuality(confluence, risk, pseudoQuote.state)
        val verdict = finalVerdict(raw, quality, confluence.contradictions.isNotEmpty())
        val finalDirection = verdictDirection(verdict)
        return QuickDecision(verdict, confluence.convergencePercent, regime, last.close, if (finalDirection != 0) risk?.stopLoss else null, if (finalDirection != 0) risk?.takeProfit else null)
    }
}
