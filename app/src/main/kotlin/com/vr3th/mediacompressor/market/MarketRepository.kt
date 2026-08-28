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

    /** Async entry point for the UI — runs on [MarketExecutors.io], delivers the result on the main thread. */
    fun fetchQuoteAsync(context: Context, instrument: MarketInstrument, timeframe: String, onResult: (MarketQuote) -> Unit) {
        val appContext = context.applicationContext
        MarketExecutors.io.execute {
            val result = fetchQuoteBlocking(appContext, instrument, timeframe)
            mainHandler.post { onResult(result) }
        }
    }

    /** Synchronous — only ever call from a background thread (used internally and by [fetchQuoteAsync]). */
    fun fetchQuoteBlocking(context: Context, instrument: MarketInstrument, timeframe: String): MarketQuote {
        val candidates = providers.filter { it.supports(instrument) }
        if (candidates.isEmpty()) {
            return MarketCache.load(context, instrument, timeframe)
                ?: MarketQuote.unavailable(instrument, "NO_PROVIDER_FOR_ASSET_CLASS")
        }
        for (provider in candidates) {
            val result = try { provider.fetchQuote(instrument, timeframe) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "EXCEPTION") }
            when (result) {
                is ProviderResult.Success -> {
                    val ind = MarketIndicators.compute(result.candles)
                    val verdict = ind?.let { MarketIndicators.verdict(result.lastPrice, it) }
                    val live = MarketQuote(
                        instrument = instrument, state = DataState.LIVE, sourceLabel = result.sourceLabel,
                        asOfMillis = result.asOfMillis, lastPrice = result.lastPrice, changePercent = result.changePercent,
                        high = result.high, low = result.low, volume = result.volume, candles = result.candles,
                        indicators = ind, verdict = verdict
                    )
                    MarketCache.save(context, instrument, timeframe, live)
                    return live
                }
                is ProviderResult.Unavailable -> continue // this provider legitimately doesn't carry it — try the next one
                is ProviderResult.Failure -> continue // network/parse hiccup — try the next provider before giving up
            }
        }
        // Every candidate provider failed or doesn't carry it: fall back to the last validated cache.
        return MarketCache.load(context, instrument, timeframe)
            ?: MarketQuote.unavailable(instrument, "ALL_PROVIDERS_UNAVAILABLE")
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
