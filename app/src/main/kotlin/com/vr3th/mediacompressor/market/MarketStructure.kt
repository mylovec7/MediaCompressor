package com.vr3th.mediacompressor.market

import kotlin.math.abs

// =============================================================================
// MARKET MODULE — MARKET STRUCTURE + SUPPORT/RESISTANCE ENGINES
// =============================================================================
// Deterministic, derived only from the candles actually supplied. Uses a
// fractal-style swing detector with a fixed lookback so tiny single-candle
// noise never registers as a "swing" (spec Section 8: avoid unstable signals
// from insignificant fluctuations).
// =============================================================================

object MarketStructureEngine {

    private const val SWING_LOOKBACK = 2          // candles on each side that must be lower/higher
    private const val MIN_CANDLES_FOR_STRUCTURE = 20
    private const val MIN_SWINGS_TO_CLASSIFY = 2   // need at least 2 highs + 2 lows to call a trend

    /** Fractal swing highs/lows: candle[i] is a swing high when its high is the strict max of the [lookback] window on each side (and likewise for lows). */
    fun detectSwings(candles: List<Candle>, lookback: Int = SWING_LOOKBACK): Pair<List<SwingPoint>, List<SwingPoint>> {
        if (candles.size < lookback * 2 + 1) return emptyList<SwingPoint>() to emptyList()
        val highs = ArrayList<SwingPoint>()
        val lows = ArrayList<SwingPoint>()
        for (i in lookback until candles.size - lookback) {
            val window = (i - lookback..i + lookback)
            val c = candles[i]
            if (window.all { j -> j == i || candles[j].high < c.high }) {
                highs.add(SwingPoint(i, c.timeMillis, c.high, SwingType.HIGH))
            }
            if (window.all { j -> j == i || candles[j].low > c.low }) {
                lows.add(SwingPoint(i, c.timeMillis, c.low, SwingType.LOW))
            }
        }
        return highs to lows
    }

    private fun classifyTrend(highs: List<SwingPoint>, lows: List<SwingPoint>): Pair<StructureTrend, Double> {
        if (highs.size < MIN_SWINGS_TO_CLASSIFY || lows.size < MIN_SWINGS_TO_CLASSIFY) return StructureTrend.TRANSITION to 0.0
        val recentHighs = highs.takeLast(3)
        val recentLows = lows.takeLast(3)
        var higherHighs = 0; var lowerHighs = 0
        for (i in 1 until recentHighs.size) if (recentHighs[i].price > recentHighs[i - 1].price) higherHighs++ else lowerHighs++
        var higherLows = 0; var lowerLows = 0
        for (i in 1 until recentLows.size) if (recentLows[i].price > recentLows[i - 1].price) higherLows++ else lowerLows++
        val upVotes = higherHighs + higherLows
        val downVotes = lowerHighs + lowerLows
        val total = (upVotes + downVotes).coerceAtLeast(1)
        return when {
            upVotes > downVotes && higherLows > 0 -> StructureTrend.UPTREND to (upVotes.toDouble() / total)
            downVotes > upVotes && lowerHighs > 0 -> StructureTrend.DOWNTREND to (downVotes.toDouble() / total)
            upVotes == downVotes -> StructureTrend.RANGE to 0.5
            else -> StructureTrend.TRANSITION to (maxOf(upVotes, downVotes).toDouble() / total)
        }
    }

    private fun detectEvent(lastClose: Double, highs: List<SwingPoint>, lows: List<SwingPoint>, priorTrend: StructureTrend): StructureEvent {
        val priorSwingHigh = highs.dropLast(1).lastOrNull()?.price
        val priorSwingLow = lows.dropLast(1).lastOrNull()?.price
        return when {
            priorSwingHigh != null && lastClose > priorSwingHigh && priorTrend == StructureTrend.UPTREND -> StructureEvent.BOS_BULLISH
            priorSwingLow != null && lastClose < priorSwingLow && priorTrend == StructureTrend.DOWNTREND -> StructureEvent.BOS_BEARISH
            priorSwingHigh != null && lastClose > priorSwingHigh && priorTrend == StructureTrend.DOWNTREND -> StructureEvent.CHOCH_BULLISH
            priorSwingLow != null && lastClose < priorSwingLow && priorTrend == StructureTrend.UPTREND -> StructureEvent.CHOCH_BEARISH
            else -> StructureEvent.NONE
        }
    }

    private fun detectBreakout(candles: List<Candle>, highs: List<SwingPoint>, lows: List<SwingPoint>): BreakoutState {
        if (candles.size < 3) return BreakoutState.NONE
        val nearestResistance = highs.lastOrNull()?.price
        val nearestSupport = lows.lastOrNull()?.price
        val last = candles.last()
        val prev = candles[candles.size - 2]
        return when {
            nearestResistance != null && prev.close <= nearestResistance && last.close > nearestResistance -> BreakoutState.BREAKOUT
            nearestSupport != null && prev.close >= nearestSupport && last.close < nearestSupport -> BreakoutState.BREAKDOWN
            nearestResistance != null && last.close < nearestResistance && prev.high > nearestResistance && prev.close < nearestResistance -> BreakoutState.FAILED_BREAKOUT
            nearestSupport != null && last.close > nearestSupport && prev.low < nearestSupport && prev.close > nearestSupport -> BreakoutState.FAILED_BREAKDOWN
            nearestResistance != null && abs(last.close - nearestResistance) / nearestResistance < 0.01 && last.close < nearestResistance -> BreakoutState.RETEST
            else -> BreakoutState.NONE
        }
    }

    fun analyze(candles: List<Candle>): MarketStructureSnapshot {
        if (candles.size < MIN_CANDLES_FOR_STRUCTURE) {
            return MarketStructureSnapshot(StructureTrend.TRANSITION, StructureEvent.NONE, BreakoutState.NONE, emptyList(), emptyList(), 0.0, sufficientData = false)
        }
        val (highs, lows) = detectSwings(candles)
        val (trend, strength) = classifyTrend(highs, lows)
        val event = detectEvent(candles.last().close, highs, lows, trend)
        val breakout = detectBreakout(candles, highs, lows)
        return MarketStructureSnapshot(trend, event, breakout, highs, lows, strength, sufficientData = highs.isNotEmpty() && lows.isNotEmpty())
    }
}

object MarketSupportResistanceEngine {

    /**
     * Combines swing points, the classic pivot, and EMA context into a ranked
     * level list. [touches] and [strengthScore] come only from how many times
     * price actually reacted near that level — never an arbitrary count.
     */
    fun analyze(candles: List<Candle>, structure: MarketStructureSnapshot, indicators: IndicatorSnapshot?): List<SRLevel> {
        if (candles.size < 10) return emptyList()
        val lastPrice = candles.last().close
        val levels = LinkedHashMap<Double, MutableList<Double>>() // rounded bucket price -> raw touch prices

        fun bucketOf(price: Double): Double {
            // group nearby swing/pivot points within ~0.5% of price into one level, so we don't report 6 near-duplicate lines
            val tolerance = (lastPrice * 0.005).coerceAtLeast(0.0001)
            val existingKey = levels.keys.firstOrNull { abs(it - price) <= tolerance }
            return existingKey ?: price
        }

        (structure.swingHighs.map { it.price } + structure.swingLows.map { it.price }).forEach { p ->
            levels.getOrPut(bucketOf(p)) { mutableListOf() }.add(p)
        }
        if (indicators != null) {
            levels.getOrPut(bucketOf(indicators.pivot)) { mutableListOf() }.add(indicators.pivot)
            levels.getOrPut(bucketOf(indicators.support1)) { mutableListOf() }.add(indicators.support1)
            levels.getOrPut(bucketOf(indicators.resistance1)) { mutableListOf() }.add(indicators.resistance1)
        }

        return levels.entries.map { (bucketPrice, touches) ->
            val avgPrice = touches.average()
            val type = if (avgPrice >= lastPrice) SRLevelType.RESISTANCE else SRLevelType.SUPPORT
            val distancePct = ((avgPrice - lastPrice) / lastPrice) * 100.0
            val recentlyBroken = when (type) {
                SRLevelType.RESISTANCE -> candles.takeLast(5).any { it.close > avgPrice }
                SRLevelType.SUPPORT -> candles.takeLast(5).any { it.close < avgPrice }
            }
            val emaConfluence = indicators != null && (abs(indicators.ema50 - avgPrice) / avgPrice < 0.01 || abs(indicators.ema200 - avgPrice) / avgPrice < 0.01)
            val strength = ((touches.size.coerceAtMost(5) / 5.0) * 0.7 + if (emaConfluence) 0.3 else 0.0).coerceIn(0.0, 1.0)
            SRLevel(avgPrice, type, touches.size, strength, distancePct, recentlyBroken, flippedRole = false)
        }.sortedBy { abs(it.distancePercent) }.take(6)
    }
}
