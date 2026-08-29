package com.vr3th.mediacompressor.market

import org.json.JSONObject

// =============================================================================
// MARKET MODULE — INSTRUMENT DISCOVERY (Sections D/E)
// =============================================================================
// [defaultFavorites] are examples ONLY, per spec — they seed the 3 category
// tabs but never limit what can be searched. [searchCatalog] is a modest
// curated metadata set for equities/ETFs/indices/forex/commodities (Stooq
// has no free symbol-search endpoint, so equities discovery is catalog-based
// here — this is a real, documented limitation, not simulated global
// coverage). Crypto search is genuinely dynamic via CoinGecko's free
// `/search` endpoint, which covers thousands of live coins/exchanges.
// =============================================================================

object MarketInstrumentIndex {

    private fun crypto(symbol: String, name: String, id: String) =
        MarketInstrument(symbol, name, AssetClass.CRYPTO, "Global", "Global", "USD", "coingecko", id)

    private fun usEquity(symbol: String, name: String) =
        MarketInstrument(symbol, name, AssetClass.US_EQUITY, "NASDAQ/NYSE", "US", "USD", "stooq", "${symbol.lowercase()}.us")

    private fun idxEquity(symbol: String, name: String) =
        MarketInstrument(symbol, name, AssetClass.IDX_EQUITY, "IDX", "ID", "IDR", "stooq", "${symbol.lowercase()}.jk")

    private fun index(symbol: String, name: String, stooqSymbol: String, currency: String) =
        MarketInstrument(symbol, name, AssetClass.INDEX, "Index", "Global", currency, "stooq", stooqSymbol)

    private fun commodity(symbol: String, name: String, stooqSymbol: String) =
        MarketInstrument(symbol, name, AssetClass.COMMODITY, "Commodity", "Global", "USD", "stooq", stooqSymbol)

    private fun forexPair(symbol: String, name: String, stooqSymbol: String) =
        MarketInstrument(symbol, name, AssetClass.FOREX, "FX", "Global", "USD", "stooq", stooqSymbol)

    // Default/favorite examples (spec Section D) — NOT a coverage limit.
    val cryptoFavorites = listOf(
        crypto("BTC", "Bitcoin", "bitcoin"),
        crypto("ETH", "Ethereum", "ethereum"),
        crypto("SOL", "Solana", "solana"),
        crypto("BNB", "BNB", "binancecoin"),
        crypto("XRP", "XRP", "ripple"),
        crypto("DOGE", "Dogecoin", "dogecoin"),
        crypto("ADA", "Cardano", "cardano")
    )

    val usEquityFavorites = listOf(
        usEquity("NVDA", "NVIDIA Corp."),
        usEquity("AAPL", "Apple Inc."),
        usEquity("TSLA", "Tesla Inc."),
        usEquity("MSFT", "Microsoft Corp.")
    )

    val idxEquityFavorites = listOf(
        idxEquity("BBCA", "Bank Central Asia"),
        idxEquity("BBRI", "Bank Rakyat Indonesia"),
        idxEquity("TLKM", "Telkom Indonesia"),
        idxEquity("ASII", "Astra International"),
        idxEquity("GOTO", "GoTo Gojek Tokopedia"),
        idxEquity("BREN", "Barito Renewables")
    )

    fun favoritesFor(category: MarketCategory): List<MarketInstrument> = when (category) {
        MarketCategory.CRYPTO -> cryptoFavorites
        MarketCategory.US_STOCKS -> usEquityFavorites
        MarketCategory.IDX -> idxEquityFavorites
    }

    // Broader local catalog used for instant offline search (Section E) —
    // covers common indices/ETFs/commodities/forex beyond the 3 tabs.
    private val extraCatalog: List<MarketInstrument> = listOf(
        crypto("BTC", "Bitcoin", "bitcoin"), crypto("ETH", "Ethereum", "ethereum"),
        crypto("SOL", "Solana", "solana"), crypto("BNB", "BNB", "binancecoin"),
        crypto("XRP", "XRP", "ripple"), crypto("DOGE", "Dogecoin", "dogecoin"),
        crypto("ADA", "Cardano", "cardano"),
        usEquity("NVDA", "NVIDIA Corp."), usEquity("AAPL", "Apple Inc."),
        usEquity("TSLA", "Tesla Inc."), usEquity("MSFT", "Microsoft Corp."),
        usEquity("AMZN", "Amazon.com Inc."), usEquity("GOOGL", "Alphabet Inc."),
        usEquity("META", "Meta Platforms Inc."),
        idxEquity("BBCA", "Bank Central Asia"), idxEquity("BBRI", "Bank Rakyat Indonesia"),
        idxEquity("TLKM", "Telkom Indonesia"), idxEquity("ASII", "Astra International"),
        idxEquity("GOTO", "GoTo Gojek Tokopedia"), idxEquity("BREN", "Barito Renewables"),
        index("SPX", "S&P 500", "^spx", "USD"),
        index("DJI", "Dow Jones Industrial Average", "^dji", "USD"),
        index("NDQ", "NASDAQ Composite", "^ndq", "USD"),
        commodity("XAUUSD", "Gold", "xauusd"),
        commodity("XAGUSD", "Silver", "xagusd"),
        commodity("CL", "Crude Oil (WTI)", "cl.f"),
        forexPair("EURUSD", "Euro / US Dollar", "eurusd"),
        forexPair("GBPUSD", "British Pound / US Dollar", "gbpusd"),
        forexPair("USDJPY", "US Dollar / Japanese Yen", "usdjpy")
    )

    /** Instant local filter — never a network call per keystroke, per spec Section E. */
    fun searchLocal(query: String): List<MarketInstrument> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        return extraCatalog.filter {
            it.symbol.lowercase().contains(q) || it.name.lowercase().contains(q) ||
                it.exchange.lowercase().contains(q) || it.country.lowercase().contains(q)
        }.distinctBy { it.symbol + it.assetClass }
    }

    /** Genuine dynamic remote discovery — CoinGecko's free `/search`, only used when local
     * results are thin and the query looks crypto-like; debounced by the caller (never per-keystroke). */
    fun searchCoinGeckoRemote(query: String): List<MarketInstrument> {
        if (query.isBlank()) return emptyList()
        val res = MarketHttp.get("https://api.coingecko.com/api/v3/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
        if (res !is MarketHttp.HttpResult.Ok) return emptyList()
        return try {
            val coins = JSONObject(res.body).optJSONArray("coins") ?: return emptyList()
            val out = ArrayList<MarketInstrument>()
            for (i in 0 until min(coins.length(), 8)) {
                val c = coins.getJSONObject(i)
                out.add(crypto(c.optString("symbol").uppercase(), c.optString("name"), c.optString("id")))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b
}
