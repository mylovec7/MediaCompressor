package com.vr3th.mediacompressor.market

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// =============================================================================
// MARKET MODULE PHASE 3 — SIGNAL LOGGING + HISTORICAL BACKTEST (P7)
// =============================================================================
// THIS IS NOT A PROMISE OF FUTURE PERFORMANCE. It is a walk-forward
// evaluation using ONLY candles that already existed at each historical
// decision point (spec Section 12 — "no look-ahead bias"), calling the exact
// same MarketAnalysisEngine.quickDecision() pipeline the live app uses — the
// backtest never has its own separate, possibly-overfit decision logic.
//
// Two independent pieces:
//  1. MarketSignalLog — a lightweight, bounded, real-time log of verdicts the
//     live app actually produced, persisted via SharedPreferences (same
//     mechanism as MarketCache), for future outcome resolution as real time
//     passes.
//  2. MarketBacktestEngine — an on-demand (never automatic) walk-forward
//     replay over whatever candle history the current provider happened to
//     return, bounded by the free tier's real history depth. Given how few
//     free-tier candles are available (crypto: ~90; equities: depends on
//     Stooq), sample sizes are usually SMALL — every result carries its
//     sample size and a `sampleTooSmall` flag rather than a false sense of
//     statistical confidence.
// =============================================================================

object MarketSignalLog {
    private const val PREFS = "market_signal_log_v1"
    private const val KEY = "signals"
    private const val MAX_ENTRIES = 300 // bounded — never grows unbounded on-device

    fun append(context: Context, signal: LoggedSignal) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = loadRaw(prefs)
            existing.put(toJson(signal))
            while (existing.length() > MAX_ENTRIES) existing.remove(0)
            prefs.edit().putString(KEY, existing.toString()).apply()
        } catch (_: Exception) { /* logging is best-effort; never crash the app over it */ }
    }

    fun loadAll(context: Context): List<LoggedSignal> {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = loadRaw(prefs)
            (0 until arr.length()).mapNotNull { i -> try { fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null } }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadRaw(prefs: android.content.SharedPreferences): JSONArray =
        try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }

    private fun toJson(s: LoggedSignal): JSONObject = JSONObject().apply {
        put("t", s.timestampMillis); put("symbol", s.symbol); put("tf", s.timeframe)
        put("verdict", s.verdict.name); put("conv", s.convergencePercent); put("regime", s.regime.name)
        put("entry", s.entry); put("stop", s.stop ?: JSONObject.NULL); put("target", s.target ?: JSONObject.NULL)
        put("outcome", s.outcome.name); put("ttoutcome", s.timeToOutcomeMillis ?: JSONObject.NULL)
        put("mfe", s.maxFavorableExcursionPercent ?: JSONObject.NULL); put("mae", s.maxAdverseExcursionPercent ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject): LoggedSignal = LoggedSignal(
        timestampMillis = o.getLong("t"), symbol = o.getString("symbol"), timeframe = o.getString("tf"),
        verdict = AiVerdict.valueOf(o.getString("verdict")), convergencePercent = o.getDouble("conv"),
        regime = MarketRegime.valueOf(o.getString("regime")), entry = o.getDouble("entry"),
        stop = if (o.isNull("stop")) null else o.getDouble("stop"), target = if (o.isNull("target")) null else o.getDouble("target"),
        outcome = SignalOutcome.valueOf(o.getString("outcome")), timeToOutcomeMillis = if (o.isNull("ttoutcome")) null else o.getLong("ttoutcome"),
        maxFavorableExcursionPercent = if (o.isNull("mfe")) null else o.getDouble("mfe"),
        maxAdverseExcursionPercent = if (o.isNull("mae")) null else o.getDouble("mae")
    )
}

object MarketBacktestEngine {
    private const val MIN_HISTORY_FOR_DECISION = 30 // candles needed before the engine can decide anything responsibly
    private const val MAX_LOOKFORWARD = 30 // candles to scan forward for an outcome before calling it TIMED_OUT
    private const val STEP = 2 // evaluate every 2nd candle, not every single one — keeps this fast on a phone

    private data class QuadOutcome(val outcome: SignalOutcome, val timeToOutcomeMillis: Long?, val mfe: Double?, val mae: Double?)

    /** Pure CPU, no network — safe to call on a background thread for a candle list already in memory. */
    fun run(symbol: String, timeframe: String, candles: List<Candle>): BacktestResult {
        val signals = ArrayList<LoggedSignal>()
        if (candles.size > MIN_HISTORY_FOR_DECISION + 5) {
            var i = MIN_HISTORY_FOR_DECISION
            while (i < candles.size - 1) {
                val window = candles.subList(0, i + 1) // ONLY candles up to and including index i — no look-ahead
                val decision = MarketAnalysisEngine.quickDecision(window)
                if (decision != null) {
                    val outcome = simulateOutcome(candles, i, decision)
                    signals.add(
                        LoggedSignal(
                            timestampMillis = candles[i].timeMillis, symbol = symbol, timeframe = timeframe,
                            verdict = decision.verdict, convergencePercent = decision.convergencePercent, regime = decision.regime,
                            entry = decision.entry, stop = decision.stop, target = decision.target,
                            outcome = outcome.outcome, timeToOutcomeMillis = outcome.timeToOutcomeMillis,
                            maxFavorableExcursionPercent = outcome.mfe, maxAdverseExcursionPercent = outcome.mae
                        )
                    )
                }
                i += STEP
            }
        }

        val byRegime = MarketRegime.values().mapNotNull { regime ->
            val bucket = signals.filter { it.regime == regime }
            if (bucket.isEmpty()) null else bucketFor(regime.name, bucket)
        }
        val convBuckets = listOf(50 to 59, 60 to 69, 70 to 79, 80 to 89, 90 to 100)
        val byConvergence = convBuckets.mapNotNull { (lo, hi) ->
            val bucket = signals.filter { it.convergencePercent >= lo && it.convergencePercent <= hi }
            if (bucket.isEmpty()) null else bucketFor("$lo-$hi", bucket)
        }
        val overall = bucketFor("ALL", signals)

        return BacktestResult(
            symbol = symbol, timeframe = timeframe, totalSignals = signals.size,
            byRegime = byRegime, byConvergenceBucket = byConvergence, overall = overall,
            methodNote = "Walk-forward over ${candles.size} already-fetched candles (real provider history only); each decision uses only candles up to its own index; evaluated every $STEP candles.",
            sampleTooSmall = signals.size < 20
        )
    }

    private fun bucketFor(label: String, signals: List<LoggedSignal>): PerformanceBucket {
        val directional = signals.filter { it.verdict != AiVerdict.NEUTRAL_WAIT && it.verdict != AiVerdict.NO_TRADE }
        val wins = directional.count { it.outcome == SignalOutcome.TARGET_HIT }
        val losses = directional.count { it.outcome == SignalOutcome.STOP_HIT }
        val noTrades = signals.size - directional.size
        val resolved = directional.filter { it.outcome == SignalOutcome.TARGET_HIT || it.outcome == SignalOutcome.STOP_HIT }
        val avgOutcome = if (resolved.isNotEmpty()) {
            val v = resolved.mapNotNull { it.maxFavorableExcursionPercent }
            if (v.isNotEmpty()) v.average() else null
        } else null
        val expectancy = if (wins + losses > 0) {
            val winRate = wins.toDouble() / (wins + losses)
            val winList = resolved.filter { it.outcome == SignalOutcome.TARGET_HIT }.mapNotNull { it.maxFavorableExcursionPercent }
            val lossList = resolved.filter { it.outcome == SignalOutcome.STOP_HIT }.mapNotNull { it.maxAdverseExcursionPercent }
            val avgWin = if (winList.isNotEmpty()) winList.average() else 0.0
            val avgLoss = if (lossList.isNotEmpty()) lossList.average() else 0.0
            (winRate * avgWin) - ((1 - winRate) * kotlin.math.abs(avgLoss))
        } else null
        return PerformanceBucket(label, signals.size, wins, losses, noTrades, avgOutcome, expectancy)
    }

    /** Scans forward from index [i] within the SAME already-fetched candle array (never new/future-fetched data)
     * to see whether target or stop was hit first, tracking max favorable/adverse excursion along the way. */
    private fun simulateOutcome(candles: List<Candle>, i: Int, decision: MarketAnalysisEngine.QuickDecision): QuadOutcome {
        if (decision.stop == null || decision.target == null) return QuadOutcome(SignalOutcome.NOT_APPLICABLE, null, null, null)
        val isLong = decision.target > decision.entry
        var mfe = 0.0; var mae = 0.0
        val end = (i + 1 + MAX_LOOKFORWARD).coerceAtMost(candles.size)
        for (j in (i + 1) until end) {
            val c = candles[j]
            val favorableExcursion = if (isLong) (c.high - decision.entry) / decision.entry * 100.0 else (decision.entry - c.low) / decision.entry * 100.0
            val adverseExcursion = if (isLong) (decision.entry - c.low) / decision.entry * 100.0 else (c.high - decision.entry) / decision.entry * 100.0
            if (favorableExcursion > mfe) mfe = favorableExcursion
            if (adverseExcursion > mae) mae = adverseExcursion
            val hitTarget = if (isLong) c.high >= decision.target else c.low <= decision.target
            val hitStop = if (isLong) c.low <= decision.stop else c.high >= decision.stop
            if (hitStop && hitTarget) return QuadOutcome(SignalOutcome.STOP_HIT, candles[j].timeMillis - candles[i].timeMillis, mfe, mae) // conservative: assume stop hit first if both occur in the same bar
            if (hitTarget) return QuadOutcome(SignalOutcome.TARGET_HIT, candles[j].timeMillis - candles[i].timeMillis, mfe, mae)
            if (hitStop) return QuadOutcome(SignalOutcome.STOP_HIT, candles[j].timeMillis - candles[i].timeMillis, mfe, mae)
        }
        return QuadOutcome(if (end < candles.size) SignalOutcome.TIMED_OUT else SignalOutcome.PENDING, null, mfe, mae)
    }
}
