package com.vr3th.mediacompressor.market

import kotlin.math.abs
import kotlin.math.max

// =============================================================================
// MARKET MODULE — VOLATILITY ENGINE + DIVERGENCE ENGINE
// =============================================================================
// ATR/VWAP/RVOL are computed only where the provider actually supplied the
// inputs they need (VWAP/RVOL require real per-candle volume — CoinGecko's
// free OHLC endpoint has none, so both are correctly null for crypto rather
// than estimated). Divergence compares real swing points, never adjacent
// candles, per spec Section 17.
// =============================================================================

object MarketVolatilityEngine {

    private fun trueRanges(candles: List<Candle>): List<Double> {
        val out = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            val c = candles[i]; val prevClose = candles[i - 1].close
            out.add(maxOf(c.high - c.low, abs(c.high - prevClose), abs(c.low - prevClose)))
        }
        return out
    }

    private fun atr(trs: List<Double>, period: Int): Double? {
        if (trs.size < period) return null
        // Wilder smoothing
        var atr = trs.take(period).average()
        for (i in period until trs.size) atr = ((atr * (period - 1)) + trs[i]) / period
        return atr
    }

    private fun classifyRegime(currentAtrPct: Double, baselineAtrPct: Double): VolatilityRegime = when {
        baselineAtrPct <= 0.0 -> VolatilityRegime.UNKNOWN
        currentAtrPct < baselineAtrPct * 0.7 -> VolatilityRegime.LOW
        currentAtrPct > baselineAtrPct * 2.0 -> VolatilityRegime.HIGH
        currentAtrPct > baselineAtrPct * 1.3 -> VolatilityRegime.ELEVATED
        else -> VolatilityRegime.NORMAL
    }

    /** VWAP + RVOL over the supplied window — null whenever ANY candle in the window lacks volume (never partially estimated). */
    private fun vwapAndRvol(candles: List<Candle>): Pair<Double?, Double?> {
        val window = candles.takeLast(20)
        if (window.any { it.volume == null }) return null to null
        val vwapNum = window.sumOf { ((it.high + it.low + it.close) / 3.0) * (it.volume ?: 0.0) }
        val vwapDen = window.sumOf { it.volume ?: 0.0 }
        val vwap = if (vwapDen > 0) vwapNum / vwapDen else null

        val volumes = candles.mapNotNull { it.volume }
        val rvol = if (candles.size >= 6 && candles.all { it.volume != null }) {
            val latest = candles.last().volume ?: return vwap to null
            val priorAvg = candles.dropLast(1).takeLast(20).mapNotNull { it.volume }.average()
            if (priorAvg > 0) latest / priorAvg else null
        } else null
        return vwap to rvol
    }

    fun analyze(candles: List<Candle>): VolatilitySnapshot {
        if (candles.size < 15) return VolatilitySnapshot(null, null, VolatilityRegime.UNKNOWN, null, null, null)
        val trs = trueRanges(candles)
        val atr14 = atr(trs, 14)
        val lastPrice = candles.last().close
        val atrPct = if (atr14 != null && lastPrice > 0) (atr14 / lastPrice) * 100.0 else null
        val baselineAtr = atr(trs, max(14, trs.size / 2).coerceAtMost(trs.size))
        val baselinePct = if (baselineAtr != null && lastPrice > 0) (baselineAtr / lastPrice) * 100.0 else null
        val regime = if (atrPct != null && baselinePct != null) classifyRegime(atrPct, baselinePct) else VolatilityRegime.UNKNOWN
        val (vwap, rvol) = vwapAndRvol(candles)
        val aboveVwap = vwap?.let { lastPrice > it }
        return VolatilitySnapshot(atr14, atrPct, regime, vwap, aboveVwap, rvol)
    }
}

object MarketDivergenceEngine {

    /** Compares the two most recent PRICE swing highs/lows against the RSI/MACD value at those exact candle indices — never adjacent-candle noise. */
    fun analyze(candles: List<Candle>, structure: MarketStructureSnapshot): DivergenceSnapshot {
        if (!structure.sufficientData || candles.size < 20) {
            return DivergenceSnapshot(false, false, false, false, sufficientData = false)
        }
        val closes = candles.map { it.close }
        val rsi = MarketIndicators.rsiSeries(closes)
        val macdHist = MarketIndicators.macdHistSeries(closes)

        val highs = structure.swingHighs.takeLast(2)
        val lows = structure.swingLows.takeLast(2)

        var rsiBear = false; var macdBear = false
        if (highs.size == 2) {
            val (i1, i2) = highs[0].index to highs[1].index
            if (i1 < rsi.size && i2 < rsi.size) {
                val priceHigher = highs[1].price > highs[0].price
                if (priceHigher && rsi[i2] < rsi[i1]) rsiBear = true
                if (priceHigher && i1 < macdHist.size && i2 < macdHist.size && macdHist[i2] < macdHist[i1]) macdBear = true
            }
        }
        var rsiBull = false; var macdBull = false
        if (lows.size == 2) {
            val (i1, i2) = lows[0].index to lows[1].index
            if (i1 < rsi.size && i2 < rsi.size) {
                val priceLower = lows[1].price < lows[0].price
                if (priceLower && rsi[i2] > rsi[i1]) rsiBull = true
                if (priceLower && i1 < macdHist.size && i2 < macdHist.size && macdHist[i2] > macdHist[i1]) macdBull = true
            }
        }
        return DivergenceSnapshot(rsiBull, rsiBear, macdBull, macdBear, sufficientData = true)
    }
}
