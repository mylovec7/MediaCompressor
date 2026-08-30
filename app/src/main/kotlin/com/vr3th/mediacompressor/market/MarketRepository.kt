package com.vr3th.mediacompressor.market

import android.content.Context
import android.os.Handler
import android.os.Looper

// =============================================================================
// MARKET MODULE — REPOSITORY / ROUTER (Sections C, G, Q, R)
// =============================================================================
// Single entry point the UI calls into. For each instrument: try providers
// that claim to support it, in order; validate every response; on success,
// persist to cache and return LIVE; on total failure, fall back to the last
// validated cache (CACHED); with nothing cached and no network, OFFLINE;
// with no provider at all for that instrument, UNAVAILABLE. Nothing here
// blends two providers' prices into a fabricated consensus figure.
// =============================================================================

object MarketRepository {

    private val providers: List<MarketProvider> = listOf(CoinGeckoProvider(), StooqProvider())
    private val fxProvider = FrankfurterFxProvider()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** How old a cached quote can be before it's shown as STALE instead of CACHED — tighter for intraday, looser for daily-only data (spec Section 6). */
    private fun staleThresholdMillis(timeframe: String): Long = when (timeframe) {
        "1M", "5M", "15M" -> 15 * 60 * 1000L        // 15 min
        "1H", "4H" -> 4 * 60 * 60 * 1000L           // 4 hours
        else -> 26 * 60 * 60 * 1000L                // 1D — Stooq/CoinGecko daily data, generous 26h window
    }

    /** Async entry point for the UI — runs on [MarketExecutors.io], delivers the result on the main thread.
     * [indonesian] controls the language of the attached AI narrative only — never the numbers themselves. */
    fun fetchQuoteAsync(context: Context, instrument: MarketInstrument, timeframe: String, indonesian: Boolean, onResult: (MarketQuote) -> Unit) {
        val appContext = context.applicationContext
        MarketExecutors.io.execute {
            val result = fetchQuoteBlocking(appContext, instrument, timeframe, indonesian)
            mainHandler.post { onResult(result) }
        }
    }

    /** Synchronous — only ever call from a background thread (used internally and by [fetchQuoteAsync]). */
    fun fetchQuoteBlocking(context: Context, instrument: MarketInstrument, timeframe: String, indonesian: Boolean): MarketQuote {
        val candidates = providers.filter { it.supports(instrument) }
        if (candidates.isEmpty()) {
            return withAnalysis(cachedOrUnavailable(context, instrument, timeframe, "NO_PROVIDER_FOR_ASSET_CLASS"), indonesian)
        }
        var sawFailure = false
        for (provider in candidates) {
            val result = try { provider.fetchQuote(instrument, timeframe) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "EXCEPTION") }
            when (result) {
                is ProviderResult.Success -> {
                    val ind = MarketIndicators.compute(result.candles)
                    val verdict = ind?.let { MarketIndicators.verdict(result.lastPrice, it) }
                    // Section 7: never claim real-time if the provider is inherently delayed — Stooq's
                    // free endpoint is end-of-day CSV, not a live feed, so it's honestly DELAYED, not LIVE.
                    val state = if (provider.id == "stooq") DataState.DELAYED else DataState.LIVE
                    val live = MarketQuote(
                        instrument = instrument, state = state, sourceLabel = result.sourceLabel,
                        asOfMillis = result.asOfMillis, lastPrice = result.lastPrice, changePercent = result.changePercent,
                        high = result.high, low = result.low, volume = result.volume, candles = result.candles,
                        indicators = ind, verdict = verdict
                    )
                    MarketCache.save(context, instrument, timeframe, live)
                    return withAnalysis(live, indonesian)
                }
                is ProviderResult.Unavailable -> continue // this provider legitimately doesn't carry it — try the next one
                is ProviderResult.Failure -> { sawFailure = true; continue } // network/parse hiccup — try the next provider before giving up
            }
        }
        // Every candidate provider failed or doesn't carry it: fall back to the last validated cache,
        // and only call it a hard ERROR (vs. a plain UNAVAILABLE) when a real failure occurred and there's nothing cached.
        val reason = if (sawFailure) "PROVIDER_ERROR" else "ALL_PROVIDERS_UNAVAILABLE"
        return withAnalysis(cachedOrUnavailable(context, instrument, timeframe, reason, isError = sawFailure), indonesian)
    }

    private fun cachedOrUnavailable(context: Context, instrument: MarketInstrument, timeframe: String, reason: String, isError: Boolean = false): MarketQuote {
        val cached = MarketCache.load(context, instrument, timeframe) ?: return if (isError) MarketQuote.unavailable(instrument, reason).copy(state = DataState.ERROR) else MarketQuote.unavailable(instrument, reason)
        val age = System.currentTimeMillis() - cached.asOfMillis
        return if (age > staleThresholdMillis(timeframe)) cached.copy(state = DataState.STALE) else cached
    }

    private fun withAnalysis(quote: MarketQuote, indonesian: Boolean): MarketQuote {
        if (!quote.state.isUsable() || quote.candles.isEmpty()) return quote
        val analysis = try { MarketAnalysisEngine.analyze(quote, indonesian) } catch (_: Exception) { null }
        return if (analysis != null) quote.copy(analysis = analysis) else quote
    }

    /** Async FX fetch for the IDR/USD/EUR/JPY toggle (Section P). Delivers cached-but-stale rates immediately
     * if present, then a live update if the network call succeeds — never fabricates a rate. */
    fun fetchFxRatesAsync(context: Context, onResult: (FxRates?) -> Unit) {
        val appContext = context.applicationContext
        MarketExecutors.io.execute {
            val live = try { fxProvider.fetchRates() } catch (_: Exception) { null }
            if (live != null) {
                MarketCache.saveFx(appContext, live)
                mainHandler.post { onResult(live) }
            } else {
                val cached = MarketCache.loadFx(appContext)
                mainHandler.post { onResult(cached) } // null here correctly means "FX RATE UNAVAILABLE"
            }
        }
    }

    fun convert(usd: Double, currency: DisplayCurrency, rates: FxRates?): Double? = when (currency) {
        DisplayCurrency.USD -> usd
        DisplayCurrency.IDR -> rates?.usdToIdr?.let { usd * it }
        DisplayCurrency.EUR -> rates?.usdToEur?.let { usd * it }
        DisplayCurrency.JPY -> rates?.usdToJpy?.let { usd * it }
    }

    /** "Rp 1.045.500" / "$1,234.56" / "€1,234.56" / "¥1,234". Returns null (→ UI shows "FX RATE UNAVAILABLE") when [convert] can't produce a value. */
    fun formatCurrencyOrNull(usd: Double, currency: DisplayCurrency, rates: FxRates?): String? {
        val value = convert(usd, currency, rates) ?: return null
        return when (currency) {
            DisplayCurrency.IDR -> "Rp " + groupDigits(Math.round(value), '.')
            DisplayCurrency.USD -> "$" + String.format(java.util.Locale.US, "%,.2f", value)
            DisplayCurrency.EUR -> "€" + String.format(java.util.Locale.US, "%,.2f", value)
            DisplayCurrency.JPY -> "¥" + groupDigits(Math.round(value), ',')
        }
    }

    fun defaultCurrencyForLocale(isIndonesian: Boolean): DisplayCurrency =
        if (isIndonesian) DisplayCurrency.IDR else DisplayCurrency.USD

    /**
     * Phase 2/3 "deep dive" — fetches order book, derivatives, sentiment, multi-timeframe alignment,
     * options, fundamentals, cross-market correlation, and macro context for a single instrument, and
     * re-runs [MarketAnalysisEngine] with all of it attached. Deliberately NOT called from the
     * favorites list: each of these is an extra network round trip, so it only runs when the user has
     * opened one specific instrument (spec Section 37 — don't request every dataset repeatedly).
     * Order book/derivatives/sentiment/options are crypto-only; fundamentals is US-equity-only;
     * correlation compares against a fixed reference instrument already in the catalog. Real-time
     * streaming (bid/ask/trade) and the live liquidation feed are handled separately by
     * [MarketRealtimeManager] / [MarketLiquidationFeed] since those are persistent WebSocket
     * subscriptions, not one-shot fetches.
     */
    fun fetchDeepDiveAsync(context: Context, instrument: MarketInstrument, baseQuote: MarketQuote, indonesian: Boolean, onResult: (MarketQuote) -> Unit) {
        val appContext = context.applicationContext
        MarketExecutors.io.execute {
            val orderBook = try { MarketOrderFlowProvider.fetchOrderBook(instrument) } catch (_: Exception) { null }
            val derivatives = try { MarketDerivativesProvider.fetchDerivatives(instrument) } catch (_: Exception) { null }
            val sentiment = try { MarketSentimentProvider.fetchFearGreed() } catch (_: Exception) { null }
            val mtf = try { MarketMultiTimeframeEngine.analyze(appContext, instrument) } catch (_: Exception) { null }
            val options = try { MarketOptionsProvider.fetchOptions(instrument) } catch (_: Exception) { null }
            val fundamentals = try { MarketFundamentalsProvider.fetchFundamentals(instrument) } catch (_: Exception) { null }
            val correlation = try { fetchCorrelationFor(appContext, instrument, baseQuote) } catch (_: Exception) { null }
            val macro = try { MarketMacroProvider.fetchMacro() } catch (_: Exception) { null }
            val enriched = try {
                MarketAnalysisEngine.analyze(baseQuote, indonesian, orderBook, derivatives, sentiment, mtf, null, options, fundamentals, correlation, macro)
            } catch (_: Exception) { null }
            val result = if (enriched != null) baseQuote.copy(analysis = enriched) else baseQuote
            mainHandler.post { onResult(result) }
        }
    }

    /** Picks a fixed, sensible reference instrument per asset class: crypto correlates against the S&P 500
     * (a widely-used "risk asset" proxy already in the catalog); other asset classes are skipped since a good
     * default reference isn't obvious and guessing one would be arbitrary, not principled. */
    private fun fetchCorrelationFor(context: Context, instrument: MarketInstrument, baseQuote: MarketQuote): CorrelationSnapshot? {
        if (instrument.assetClass != AssetClass.CRYPTO) return null
        val reference = MarketInstrumentIndex.searchLocal("SPX").firstOrNull { it.assetClass == AssetClass.INDEX } ?: return null
        return MarketCorrelationEngine.analyze(context, baseQuote, "S&P 500", reference)
    }

    private fun groupDigits(n: Long, sep: Char): String {
        val s = kotlin.math.abs(n).toString()
        val sb = StringBuilder()
        for ((i, c) in s.reversed().withIndex()) {
            if (i > 0 && i % 3 == 0) sb.append(sep)
            sb.append(c)
        }
        return (if (n < 0) "-" else "") + sb.reverse().toString()
    }
}
