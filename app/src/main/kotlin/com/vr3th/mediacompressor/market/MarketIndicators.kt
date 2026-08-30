package com.vr3th.mediacompressor.market

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// =============================================================================
// MARKET MODULE — ON-DEVICE TECHNICAL ANALYSIS
// =============================================================================
// Pure Kotlin, deterministic, computed only from the [Candle] list a
// MarketProvider actually returned. No random number ever enters this file.
// This is the ONLY thing Section 4 of the spec (APEX QUANT engine) needed —
// it does not fetch, cache, or render anything.
// =============================================================================

object MarketIndicators {

    /** Minimum candle count below which longer-period readings (EMA200 etc.)
     * are not considered meaningful — the UI shows "INSUFFICIENT HISTORICAL DATA"
     * instead of a misleading number. */
    private const val MIN_CANDLES_FOR_FULL_ANALYSIS = 35

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period.coerceAtLeast(1) + 1)
        var e = values.first()
        for (i in 1 until values.size) e = values[i] * k + e * (1 - k)
        return e
    }

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period.coerceAtLeast(1) + 1)
        val out = ArrayList<Double>(values.size)
        var e = values.first()
        out.add(e)
        for (i in 1 until values.size) { e = values[i] * k + e * (1 - k); out.add(e) }
        return out
    }

    private fun rsi14(values: List<Double>): Double {
        if (values.size < 2) return 50.0
        val period = min(14, values.size - 1)
        var gains = 0.0
        var losses = 0.0
        val start = values.size - period
        for (i in start until values.size) {
            val diff = values[i] - values[i - 1]
            if (diff >= 0) gains += diff else losses -= diff
        }
        val avgGain = gains / period
        val avgLoss = losses / period
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun bollinger(values: List<Double>, period: Int = 20): Triple<Double, Double, Double> {
        val window = values.takeLast(period.coerceAtMost(values.size).coerceAtLeast(1))
        val mid = window.average()
        val variance = window.sumOf { (it - mid) * (it - mid) } / window.size
        val sd = sqrt(variance)
        return Triple(mid + 2 * sd, mid, mid - 2 * sd)
    }

    /** Classic floor-trader pivot from the most recent candle. Returns null when there is no candle to derive it from. */
    private fun pivotLevels(last: Candle?): Triple<Double, Double, Double> {
        if (last == null) return Triple(0.0, 0.0, 0.0)
        val p = (last.high + last.low + last.close) / 3.0
        val s1 = (2 * p) - last.high
        val r1 = (2 * p) - last.low
        return Triple(p, s1, r1)
    }

    /** Recent-vs-older average volume, only when the provider actually supplied per-candle volume for every point checked. */
    private fun volumeDeltaPercent(candles: List<Candle>): Double? {
        val volumes = candles.map { it.volume }
        if (volumes.any { it == null }) return null
        val vals = volumes.map { it!! }
        val recent = vals.takeLast(8)
        val olderChunk = vals.dropLast(8).takeLast(8)
        if (recent.isEmpty() || olderChunk.isEmpty()) return null
        val recentAvg = recent.average()
        val olderAvg = olderChunk.average()
        return if (olderAvg > 0) ((recentAvg - olderAvg) / olderAvg) * 100.0 else null
    }

    /** Full RSI(14) time series (Wilder-style rolling average), one value per candle from index [period] onward is meaningful; earlier points use whatever history is available. Used only by the Divergence Engine — never displayed as a single "the" RSI value. */
    fun rsiSeries(closes: List<Double>, period: Int = 14): List<Double> {
        if (closes.size < 2) return closes.map { 50.0 }
        val out = ArrayList<Double>(closes.size)
        out.add(50.0)
        var avgGain = 0.0; var avgLoss = 0.0
        for (i in 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            if (i <= period) {
                avgGain = ((avgGain * (i - 1)) + gain) / i
                avgLoss = ((avgLoss * (i - 1)) + loss) / i
            } else {
                avgGain = ((avgGain * (period - 1)) + gain) / period
                avgLoss = ((avgLoss * (period - 1)) + loss) / period
            }
            val rsi = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + (avgGain / avgLoss)))
            out.add(rsi)
        }
        return out
    }

    /** Full MACD histogram series (12/26/9), same math as [compute] but every point instead of just the last — used only by the Divergence Engine. */
    fun macdHistSeries(closes: List<Double>): List<Double> {
        if (closes.isEmpty()) return emptyList()
        val ema12s = emaSeries(closes, 12)
        val ema26s = emaSeries(closes, 26)
        val macdLine = ema12s.indices.map { ema12s[it] - ema26s[it] }
        val signalLine = emaSeries(macdLine, 9)
        return macdLine.indices.map { macdLine[it] - signalLine[it] }
    }

    /** RSI(14), EMA(20/50/200), MACD(12/26/9), Bollinger(20,2), Volume Delta and Pivot/S1/R1 — all from the supplied candles only. */
    fun compute(candles: List<Candle>): IndicatorSnapshot? {
        if (candles.size < 5) return null // truly not enough to say anything responsible
        val closes = candles.map { it.close }
        val ema12s = emaSeries(closes, 12)
        val ema26s = emaSeries(closes, 26)
        val macdLine = ema12s.indices.map { ema12s[it] - ema26s[it] }
        val macdSignalSeries = emaSeries(macdLine, 9)
        val macd = macdLine.lastOrNull() ?: 0.0
        val macdSignal = macdSignalSeries.lastOrNull() ?: 0.0
        val (bbU, bbM, bbL) = bollinger(closes)
        val (p, s1, r1) = pivotLevels(candles.lastOrNull())
        return IndicatorSnapshot(
            rsi14 = rsi14(closes),
            ema20 = ema(closes, min(20, closes.size)),
            ema50 = ema(closes, min(50, closes.size)),
            ema200 = ema(closes, min(200, closes.size)),
            macd = macd,
            macdSignal = macdSignal,
            macdHist = macd - macdSignal,
            bbUpper = bbU, bbMid = bbM, bbLower = bbL,
            volumeDeltaPercent = volumeDeltaPercent(candles),
            pivot = p, support1 = s1, resistance1 = r1,
            sufficientHistory = candles.size >= MIN_CANDLES_FOR_FULL_ANALYSIS
        )
    }

    /** Multi-indicator convergence vote (RSI / EMA trend / EMA long-trend / MACD / Bollinger position) -> signal + confidence + risk/reward. */
    fun verdict(lastPrice: Double, ind: IndicatorSnapshot): QuantVerdict {
        val total = 5
        var bullish = 0
        bullish += if (ind.rsi14 < 35) 1 else if (ind.rsi14 > 65) -1 else 0
        bullish += if (ind.ema20 > ind.ema50) 1 else -1
        bullish += if (ind.ema50 > ind.ema200) 1 else -1
        bullish += if (ind.macdHist > 0) 1 else -1
        bullish += if (lastPrice < ind.bbMid) 1 else -1
        val bullishVotes = (((bullish + total) / 2.0).roundToInt()).coerceIn(0, total)
        val signal = when (bullishVotes) {
            5 -> QuantSignal.STRONG_BUY
            4 -> QuantSignal.BUY
            3, 2 -> QuantSignal.NEUTRAL
            1 -> QuantSignal.SELL
            else -> QuantSignal.STRONG_SELL
        }
        val confidence = (50.0 + (abs(bullishVotes - 2.5) / 2.5) * 45.0).coerceIn(50.0, 99.0)
        val riskReward = 1.2 + (bullishVotes.toDouble() / total) * 1.8
        return QuantVerdict(signal, confidence, bullishVotes, total, riskReward)
    }
}
