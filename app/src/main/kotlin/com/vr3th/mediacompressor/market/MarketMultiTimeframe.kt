package com.vr3th.mediacompressor.market

import android.content.Context

// =============================================================================
// MARKET MODULE PHASE 2 — MULTI-TIMEFRAME ALIGNMENT ENGINE
// =============================================================================
// Section 7 of the spec requires this to use GENUINE distinct timeframes, not
// the same data relabeled. CoinGecko's free OHLC endpoint only offers 3 real
// candle spacings (fixed by the `days` window, not by us): ~30m, ~4h, ~4day.
// We use the two most separated of those (~30m "lower" vs ~4day "higher") so
// the two anchors are unambiguously different real data, not a mislabeled
// duplicate. Stooq (equities/IDX) only has one real granularity — daily — so
// there is no second genuine timeframe to compare, and MTF is correctly
// UNAVAILABLE for those asset classes rather than faked.
//
// Reuses [MarketRepository]'s existing fetch/cache path (by calling it with
// the "1M" and "1D" timeframe labels, which already map to those two
// spacings in [CoinGeckoProvider]) instead of duplicating any network code,
// and piggybacks on whatever the user already has cached for those chips.
// =============================================================================

object MarketMultiTimeframeEngine {

    private const val LOWER_LABEL = "1M"   // -> CoinGeckoProvider.daysFor("1M") = 1  -> ~30-minute real candles
    private const val HIGHER_LABEL = "1D"  // -> CoinGeckoProvider.daysFor("1D") = 90 -> ~4-day real candles

    /** BLOCKING — performs up to two network fetches (or hits cache). Call only from a background thread. */
    fun analyze(context: Context, instrument: MarketInstrument): MultiTimeframeSnapshot? {
        if (instrument.assetClass != AssetClass.CRYPTO) return null // no second genuine granularity available (spec Section 7)

        val higher = MarketRepository.fetchQuoteBlocking(context, instrument, HIGHER_LABEL, indonesian = false)
        val lower = MarketRepository.fetchQuoteBlocking(context, instrument, LOWER_LABEL, indonesian = false)
        if (!higher.state.isUsable() || !lower.state.isUsable() || higher.candles.size < 20 || lower.candles.size < 20) return null

        val higherStructure = MarketStructureEngine.analyze(higher.candles)
        val lowerStructure = MarketStructureEngine.analyze(lower.candles)
        if (!higherStructure.sufficientData || !lowerStructure.sufficientData) {
            return MultiTimeframeSnapshot(HIGHER_LABEL, LOWER_LABEL, null, null, TimeframeAlignment.INSUFFICIENT, "Insufficient structure data on one or both timeframes")
        }

        val alignment = when {
            higherStructure.trend == StructureTrend.UPTREND && lowerStructure.trend == StructureTrend.UPTREND -> TimeframeAlignment.ALIGNED_BULLISH
            higherStructure.trend == StructureTrend.DOWNTREND && lowerStructure.trend == StructureTrend.DOWNTREND -> TimeframeAlignment.ALIGNED_BEARISH
            (higherStructure.trend == StructureTrend.UPTREND && lowerStructure.trend == StructureTrend.DOWNTREND) ||
                (higherStructure.trend == StructureTrend.DOWNTREND && lowerStructure.trend == StructureTrend.UPTREND) -> TimeframeAlignment.CONFLICTING
            else -> TimeframeAlignment.INSUFFICIENT
        }
        val note = "Higher timeframe (~4-day candles): ${higherStructure.trend}; Lower timeframe (~30-min candles): ${lowerStructure.trend}"
        return MultiTimeframeSnapshot(HIGHER_LABEL, LOWER_LABEL, higherStructure.trend, lowerStructure.trend, alignment, note)
    }
}
