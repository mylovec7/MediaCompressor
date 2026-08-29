package com.vr3th.mediacompressor.market

// =============================================================================
// MARKET MODULE PHASE 3 — SEC EDGAR FUNDAMENTALS (P5)
// =============================================================================
// data.sec.gov's XBRL "companyconcept" API is official, free, and keyless —
// confirmed against SEC's own EDGAR API documentation. The ONLY requirement
// is a descriptive User-Agent identifying the app (SEC returns 403 without
// one); there is no API key of any kind to configure. US-listed equities
// only — for IDX/non-US tickers this correctly returns null (no equivalent
// official free source exists, see MARKET_MODULE.md).
//
// Ticker->CIK resolution uses a small curated map (the full SEC
// company_tickers.json is ~1-2MB and refetching it for a handful of
// favorites would be wasteful on a lightweight/mobile app) — anything not
// in the map degrades to UNAVAILABLE rather than downloading the whole file.
// =============================================================================

object MarketFundamentalsProvider {

    private const val USER_AGENT = "MediaCompressorMarket contact:market-module@example-app (SEC EDGAR fair-access compliant)"

    private val curatedCik = mapOf(
        "AAPL" to "0000320193", "MSFT" to "0000789019", "TSLA" to "0001318605",
        "NVDA" to "0001045810", "AMZN" to "0001018724", "GOOGL" to "0001652044", "META" to "0001326801"
    )

    private fun cikFor(instrument: MarketInstrument): String? {
        if (instrument.assetClass != AssetClass.US_EQUITY) return null
        return curatedCik[instrument.symbol.trim().uppercase()]
    }

    /** Fetches one XBRL concept (e.g. "Revenues") for a company. Missing/unreported concepts return null — SEC filers don't all use the same tag names, so a miss here is routine, not an error. */
    private fun fetchConcept(cik: String, concept: String): Pair<Double, String?>? {
        val url = "https://data.sec.gov/api/xbrl/companyconcept/CIK$cik/us-gaap/$concept.json"
        val res = MarketHttp.get(url, mapOf("User-Agent" to USER_AGENT))
        if (res !is MarketHttp.HttpResult.Ok) return null
        return try {
            val root = org.json.JSONObject(res.body)
            val units = root.optJSONObject("units") ?: return null
            val usd = units.optJSONArray("USD") ?: units.optJSONArray("shares") ?: return null
            if (usd.length() == 0) return null
            // Prefer the most recent 10-K (annual) value; fall back to the most recent entry of any form.
            var best: org.json.JSONObject? = null
            for (i in usd.length() - 1 downTo 0) {
                val entry = usd.getJSONObject(i)
                if (entry.optString("form") == "10-K") { best = entry; break }
            }
            if (best == null) best = usd.getJSONObject(usd.length() - 1)
            val value = best.optDouble("val", Double.NaN)
            if (value.isNaN()) null else {
                val fy = best.optString("fy", "")
                val fp = best.optString("fp", "")
                val periodLabel = if (fy.isNotEmpty()) "$fy$fp" else null
                value to periodLabel
            }
        } catch (_: Exception) { null }
    }

    fun fetchFundamentals(instrument: MarketInstrument): FundamentalsSnapshot? {
        val cik = cikFor(instrument) ?: return null
        val now = System.currentTimeMillis()
        val facts = ArrayList<FundamentalFact>()

        val revenue = fetchConcept(cik, "Revenues") ?: fetchConcept(cik, "RevenueFromContractWithCustomerExcludingAssessedTax")
        revenue?.let { (v, period) -> facts.add(FundamentalFact("Revenue", v, "USD", period, null, FundamentalBasis.REPORTED)) }

        val netIncome = fetchConcept(cik, "NetIncomeLoss")
        netIncome?.let { (v, period) -> facts.add(FundamentalFact("Net Income", v, "USD", period, null, FundamentalBasis.REPORTED)) }

        val assets = fetchConcept(cik, "Assets")
        assets?.let { (v, period) -> facts.add(FundamentalFact("Total Assets", v, "USD", period, null, FundamentalBasis.REPORTED)) }

        val liabilities = fetchConcept(cik, "Liabilities")
        liabilities?.let { (v, period) -> facts.add(FundamentalFact("Total Liabilities", v, "USD", period, null, FundamentalBasis.REPORTED)) }

        val equity = fetchConcept(cik, "StockholdersEquity")
        equity?.let { (v, period) -> facts.add(FundamentalFact("Stockholders' Equity", v, "USD", period, null, FundamentalBasis.REPORTED)) }

        val ocf = fetchConcept(cik, "NetCashProvidedByUsedInOperatingActivities")
        ocf?.let { (v, period) -> facts.add(FundamentalFact("Operating Cash Flow", v, "USD", period, null, FundamentalBasis.REPORTED)) }

        val capex = fetchConcept(cik, "PaymentsToAcquirePropertyPlantAndEquipment")
        capex?.let { (v, period) -> facts.add(FundamentalFact("Capital Expenditure", v, "USD", period, null, FundamentalBasis.REPORTED)) }

        // FCF = Operating Cash Flow - Capital Expenditure — CALCULATED here, explicitly labeled, per spec Section 9.
        if (ocf != null && capex != null) {
            facts.add(FundamentalFact("Free Cash Flow (OCF minus CapEx)", ocf.first - capex.first, "USD", ocf.second, null, FundamentalBasis.CALCULATED))
        }

        val shares = fetchConcept(cik, "CommonStockSharesOutstanding") ?: fetchConcept(cik, "EntityCommonStockSharesOutstanding")
        shares?.let { (v, period) -> facts.add(FundamentalFact("Shares Outstanding", v, "shares", period, null, FundamentalBasis.REPORTED)) }

        if (facts.isEmpty()) return FundamentalsSnapshot(instrument.symbol, cik, emptyList(), DataState.UNAVAILABLE, "SEC EDGAR", now)
        return FundamentalsSnapshot(instrument.symbol, cik, facts, DataState.LIVE, "SEC EDGAR (data.sec.gov)", now)
    }
}
