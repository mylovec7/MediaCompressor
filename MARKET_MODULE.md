# MARKET MODULE — File Map & Scope Notes

This document describes the changes made to satisfy `market_only_prompt.txt`:
replace the old **fabricated** market data with a real, isolated Market
feature, without touching the media-compression engine's actual logic.

## What was wrong before

The previous "Quant Market" feature lived as `object QuantMarketEngine` at
the bottom of `MediaEngine.kt` (a media-processing file) and generated all
prices via a **seeded random walk** — no network call, no real data. That
entire block has been deleted.

## New isolated package: `com.vr3th.mediacompressor.market`

Nothing in this package imports or is imported by `MediaEngine.kt` /
`GifEncoder.kt`. It is a self-contained feature module.

| File | Responsibility |
|---|---|
| `MarketModels.kt` | Shared data classes/enums: `MarketInstrument`, `Candle`, `MarketQuote`, `DataState` (LIVE/CACHED/OFFLINE/UNAVAILABLE), indicator/verdict types. No logic. |
| `MarketNetwork.kt` | Native `HttpURLConnection` client + a small daemon-thread executor. Every network call is blocking and must run off the main thread. |
| `MarketIndicators.kt` | Pure, deterministic on-device technical analysis: RSI(14), EMA(20/50/200), MACD(12/26/9), Bollinger(20,2), pivot/S1/R1, volume-delta (or `null` if the provider has no per-candle volume). |
| `MarketProviders.kt` | Provider adapters — see table below. Each one only normalizes a real response; on anything it can't validate, it reports `Unavailable`/`Failure` instead of guessing. |
| `MarketInstrumentIndex.kt` | Default favorites for the 3 tabs (examples only, not a coverage limit) + a local search catalog + live CoinGecko `/search` for dynamic crypto discovery. |
| `MarketCache.kt` | SharedPreferences cache, keyed by provider+symbol+timeframe, plus the watchlist. Only a *validated* LIVE fetch is ever persisted. |
| `MarketRepository.kt` | The router: tries each supporting provider in order, validates the response, saves to cache on success, falls back to cache on failure, and formats currency conversion (never inventing an FX rate). |
| `MarketCharts.kt` | Native Canvas views: sparkline + a pannable OHLC candlestick chart. No charting library. |

`MainActivity.kt` keeps only thin UI glue for the Market screen (search bar,
tabs, cards, expand/detail view) using its existing styling helpers, exactly
like every other screen in the app — all data/provider/cache/indicator logic
lives in the files above.

## Providers actually wired, and why

| Asset class | Provider | Notes |
|---|---|---|
| Crypto | **CoinGecko** public API (no key) | Real OHLC + 24h ticker. Free-tier OHLC candle spacing is fixed by CoinGecko (not us) — see comments in `CoinGeckoProvider`. |
| US equities / ETFs / indices / commodities | **Stooq** CSV (no key) | Real daily OHLCV. Stooq's free endpoint is daily-only, so all UI timeframes for this asset class resolve to the daily series. |
| Indonesian IDX (BBCA, BBRI, etc.) | **Stooq, best-effort** | Stooq's documented exchange-suffix list does not confirm Jakarta coverage. The app attempts it and validates the response; if Stooq doesn't actually carry it, the UI correctly shows **DATA UNAVAILABLE** rather than a fabricated price. No other free, keyless, legitimate IDX provider was found during research — swap in a real one here if you have API access. |
| FX (IDR/USD/EUR/JPY toggle) | **Frankfurter** (ECB reference rates, no key) | Used only for currency display conversion, never for instrument pricing. |
| Order book / Level-2, sentiment/funding, fundamentals, news, options, futures, bonds | **None wired** | No legitimate free/keyless provider was integrated for these. The UI shows `DATA UNAVAILABLE` for them rather than faking or hiding the section. |

## Manifest / build changes

- **AndroidManifest.xml**: none needed — `INTERNET` and `ACCESS_NETWORK_STATE`
  were already declared.
- **app/build.gradle.kts**: no new dependency needed — networking uses
  `HttpURLConnection` (built-in) and JSON parsing uses `org.json` (built into
  Android), matching the "no heavy dependencies" constraint of this project.
- **proguard-rules.pro**: unchanged — no reflection is used by the new code.

## What changed in existing files

- `MediaEngine.kt`: the fake `QuantMarketEngine` block (everything after the
  real media/crypto code) was deleted. Nothing above it was touched.
- `MainActivity.kt`: imports added for the `market` package; the old
  `QuantSparklineView` was removed (replaced by `MarketSparklineView` in the
  isolated module); the Market screen's data-wiring was replaced to call
  `MarketRepository` asynchronously instead of the deleted fake engine. Every
  existing visual style helper (`topBar`, `heading`, `text`, `mono`, `shape`,
  `glassCard`, colors, etc.) is reused unchanged.

## Known scope limits (stated, not hidden)

- IDX equity coverage depends on whether Stooq actually carries the symbol —
  verify with a live network test once you build this; it degrades to
  `DATA UNAVAILABLE` cleanly either way.
- Intraday timeframes (1M/5M/15M/1H/4H) are only meaningfully supported for
  crypto; equities/IDX use Stooq's daily series regardless of the timeframe
  chip selected, since that's what the free data source can actually provide.
- This was written and statically checked (brace/paren balance, signature
  matching against every helper it calls, import audit) in an environment
  without network access or the Android SDK, so it has **not** been run
  through `./gradlew assembleRelease` or a live device. Please run the CI
  build / a local build once, and let me know if anything surfaces.

---

## Upgrade 2 — Market Intelligence Engine (post-build audit + hardening)

This second pass, driven by `MARKET_ONLY_MASTER_UPGRADE_PROMPT.txt`, is a
Market-only audit and upgrade — nothing in `MediaEngine.kt`/`GifEncoder.kt`
was touched.

### Audit summary (before upgrading)
The original Market feature (from Upgrade 1) had real data and a working
UI, but the "intelligence" layer was thin: a flat 5-indicator equal-vote
confluence that double-counted trend (EMA20/50/200 as 3 separate votes), a
Risk/Reward figure derived from vote count rather than real price levels, no
Market Structure/Support-Resistance engine, no explicit STALE/DELAYED/ERROR
data states, and no explainable output — just a label and a percentage.

### New files (all under `com.vr3th.mediacompressor.market`, still fully
isolated from the media engine)

| File | Adds |
|---|---|
| `MarketStructure.kt` | Fractal swing detection, trend classification (HH/HL vs LH/LL), BOS/CHoCH, breakout/breakdown/retest/failed-breakout, and a Support/Resistance engine that clusters swing points + pivot + EMA confluence into ranked levels with a real touch-count-based strength score. |
| `MarketVolatility.kt` | ATR(14) + a volatility regime classifier (LOW/NORMAL/ELEVATED/HIGH, relative to the instrument's own recent baseline — never a fixed % threshold), VWAP + RVOL (both `null` whenever the provider has no per-candle volume — never estimated), and a Divergence Engine that compares RSI/MACD at real swing-point indices (not adjacent candles). |
| `MarketAnalysisEngine.kt` | Confluence Engine v2 (7 weighted categories, correlated evidence collapsed instead of equal-voted, missing categories excluded rather than faked), Market Regime classifier, a real level-derived Risk Engine (stop/target from actual swing S/R, falling back to measured ATR only when no structural level exists — never from signal strength), a Scenario Engine (bullish/bearish/invalidation/wait-condition), and the "AI Market Brain": a deterministic, rule-based expert system (not a generative LLM — stated explicitly, since this app has no LLM integration) that produces a structured, explainable, bilingual narrative and can output `NO_TRADE` when confirmation is insufficient.

### Modified files
- `MarketModels.kt`: `DataState` extended from `{LIVE, CACHED, OFFLINE,
  UNAVAILABLE}` to add `DELAYED`, `STALE`, `ERROR` (Section 6 of the spec).
  Added the full structure/S-R/volatility/divergence/confluence/risk/verdict
  model set. `MarketQuote` gained an optional `analysis: MarketAnalysis?`
  field (default `null`, so every existing call site kept compiling
  unchanged).
- `MarketIndicators.kt`: added `rsiSeries()`/`macdHistSeries()` — full
  time-series versions of the existing point-in-time RSI/MACD, needed only
  by the new Divergence Engine. The original `compute()`/`verdict()` API is
  untouched.
- `MarketCache.kt`: now also persists `DELAYED` quotes (previously only
  `LIVE` was cached — needed once Stooq's data was honestly reclassified as
  delayed, see below).
- `MarketRepository.kt`: Stooq-sourced quotes are now `DELAYED` instead of
  `LIVE` (Stooq's free endpoint is end-of-day CSV, not a real-time feed —
  claiming LIVE for it was inaccurate per spec Section 7). Cached data past
  a timeframe-aware freshness threshold (15 min intraday / 4h for 1H-4H /
  26h for daily) is now surfaced as `STALE` instead of silently `CACHED`. A
  genuine provider exception with no cache to fall back on is now `ERROR`,
  distinct from `UNAVAILABLE` (provider confirmed it doesn't carry the
  data). Every usable quote now gets `MarketAnalysisEngine.analyze()`
  attached.
- `MainActivity.kt`: the list card and the instrument detail screen both
  now show Market Regime, Structure, nearest Support/Resistance, ATR/
  volatility regime, VWAP, RVOL, Divergence (when present), Signal Quality,
  a real Entry/Stop/Target risk plan, the explainable narrative, and
  Invalidation/Wait-condition scenarios — falling back to the old
  `QuantVerdict` display only for the rare case where there's too little
  history (5-14 candles) for the new engine but just enough for the old one.
  Order Book, Derivatives, Fundamentals, and News/Sentiment are listed as
  `UNAVAILABLE` rather than omitted, per Section 43 (no fake completeness,
  but also no silently hiding what's missing).

### What's still `UNAVAILABLE` (P2 in the spec's own priority order — correctly deprioritized)
Order book/Level-2, funding/OI/liquidations, options/IV, equity
fundamentals, news, sentiment, and macro — no legitimate free/keyless
provider is wired for any of these. Multi-timeframe *simultaneous* analysis
(the spec's Section 18) is also not implemented in this pass — the engine
analyzes whichever single timeframe the user has selected; extending it to
fetch and reconcile 2-3 timeframes at once is a reasonable next increment
but was left out to avoid multiplying API calls per instrument without a
clear UI for showing the alignment (spec Section 44: don't overengineer
past P0/P1).

---

## Upgrade 3 — Phase 2: Data Coverage + AI Market Brain Completion

Driven by `MARKET_ONLY_PHASE_2_DATA_AI_UPGRADE.txt`. Everything from
Upgrade 1 and Upgrade 2 was kept intact — this pass only adds new files and
extends existing ones with optional, default-`null` parameters, so every
previous call site kept compiling unchanged. `MediaEngine.kt`/
`GifEncoder.kt` were not touched.

### New providers (all verified free, keyless, public market-data endpoints)

| Data | Endpoint | Scope |
|---|---|---|
| Order book (bids/asks, spread, depth imbalance) | Binance Spot `GET /api/v3/depth` | Crypto only |
| Funding rate, mark/index price | Binance USDS-M Futures `GET /fapi/v1/premiumIndex` | Crypto perpetuals only |
| Open interest | Binance USDS-M Futures `GET /fapi/v1/openInterest` | Crypto perpetuals only |
| Long/short account ratio | Binance USDS-M Futures `GET /futures/data/globalLongShortAccountRatio` | Crypto perpetuals only |
| Market-wide Fear & Greed sentiment | Alternative.me `GET /fng/` (per their terms, the source is credited in the UI/narrative wherever this value is shown) | Crypto, market-wide (not per-asset) |

Binance's own "Market Data Only" documentation states these endpoints need
no API key. All four crypto-only datasets are implemented in the new
`MarketExtendedProviders.kt`, each independently validated: an unrecognized
symbol or a malformed response degrades to `null`/`UNAVAILABLE`, never a
guessed number. A small curated CoinGecko-id → Binance-ticker map covers the
existing favorites, with a generic `SYMBOL+"USDT"` fallback for anything
else found via search — validated at call time, never assumed to succeed.

**Investigated and intentionally left `UNAVAILABLE` (provider-ready, not implemented):** equity fundamentals, news, macro (rates/inflation/GDP), and on-chain data. No free, keyless, sufficiently reliable source was found for any of these — the realistic free options either require an API key (FRED, most news APIs), are unofficial/fragile scraping targets (Yahoo Finance's undocumented endpoints), or don't exist in a general-purpose free form (on-chain analytics). Rather than hardcode a key or scrape an undocumented endpoint, these stay `UNAVAILABLE`, and the architecture (`MarketProvider`-style interfaces, the `MarketAnalysis` model's room for more `CategoryEvidence` categories) is ready to accept a real provider later without another rewrite.

### Multi-Timeframe (Section 7 — high priority)

Implemented honestly within the real constraints of the free data available:
CoinGecko's free OHLC endpoint only has **three** real candle spacings
(fixed by their `days` window, not chosen by this app): ~30 minutes, ~4
hours, ~4 days. `MarketMultiTimeframeEngine` (new file) compares the two
most-separated real anchors — ~30-min "lower" vs ~4-day "higher" — and
classifies them as `ALIGNED_BULLISH` / `ALIGNED_BEARISH` / `CONFLICTING` /
`INSUFFICIENT`. This is **crypto-only**: Stooq (equities/IDX) only has one
real granularity, daily, so there is no second genuine timeframe to compare
and MTF correctly reports `UNAVAILABLE` there rather than a relabeled
duplicate. Alignment is weighted (1.2, the highest of any category) when
timeframes agree, and a conflict is surfaced as an explicit contradiction
rather than a vote — matching the spec's "higher timeframe = context, lower
timeframe = confirmation, never weighted equally."

### AI Market Brain — Phase 2

Still the same deterministic, rule-based expert system (no LLM was added —
Section 23 explicitly asked to keep it that way). What changed:
- **Confluence Engine extended** from 7 to 11 possible evidence categories
  (added Order Flow, Derivatives, Sentiment, Multi-Timeframe), each only
  counted when genuinely available — a missing category is simply absent
  from both the vote and the denominator's numerator, never a fake neutral.
- **New contradiction checks**: multi-timeframe conflict, and extreme
  funding coinciding with a nearby key level.
- **Confidence now factors data freshness** (Section 27): a `STALE`/`CACHED`
  quote can no longer be rated `SignalQuality.HIGH`, however clean the
  technical picture looks, and the narrative explicitly says so.
- **Reasoning is now connected prose**, not a value dump (Section 25) — a
  new `buildReasoningParagraph()` synthesizes the trend, the top 1-2
  supporting factors, the multi-timeframe relationship, the leading
  contradiction (if any), and a graded conclusion into a short paragraph, in
  the style of the spec's own "GOOD" example, in addition to the existing
  scannable line-by-line summary (kept for at-a-glance reading on a small
  screen).
- Every new dataset is shown with its own status line in the narrative
  (`ORDER BOOK: ...` / `DERIVATIVES: ...` / `SENTIMENT: ...` /
  `FUNDAMENTALS / NEWS / MACRO / ON-CHAIN: UNAVAILABLE (...)`), so the AI
  never implies completeness it doesn't have.

### Performance / scope control (Section 37)

Order book, derivatives, sentiment, and multi-timeframe are fetched **only**
from the single-instrument detail screen (`openInstrumentDetail`), never
from the favorites list — opening one instrument can trigger up to ~7 extra
network calls (order book, 3 derivatives endpoints, sentiment, and up to 2
MTF quote fetches, the latter often already cached). The list view's
behavior and call volume are unchanged from Upgrade 2. The multi-timeframe
engine reuses `MarketRepository`'s existing fetch/cache path instead of
duplicating network code, so it shares a cache entry with the "1D"/"1M"
timeframe chips if the user has already viewed them.

### Testing

Same method as Upgrades 1-2: static verification only (brace/paren balance
across all 16 Kotlin files, every new constructor call cross-checked
field-by-field against its data class, every function call checked against
its signature, all `%` /`%%` string-formatting call sites re-audited after
catching and fixing two instances of the same literal-`%%`-in-plain-
interpolation bug from the previous pass). The three new public endpoints
(Binance depth/premiumIndex/openInterest/long-short-ratio, Alternative.me
fng) were verified via web search against their own documentation to
confirm they are free and keyless, but their JSON response shapes were
**not** hit live from this sandbox (no network access here) — please
smoke-test the detail screen for a crypto favorite once built, and let me
know if any field comes back unexpectedly empty.



---

## Upgrade 4 — Phase 3: Professional Market Upgrade (P1-P8)

Driven by `MARKET_ONLY_PHASE_3_PROFESSIONAL_MARKET_UPGRADE.txt`. All of Phase
1/2 was kept intact — every extension used optional/default-`null`
parameters, so no previous call site broke. `MediaEngine.kt`/`GifEncoder.kt`
were not touched. **9 new files**, ~2,300 new lines.

### P1 — Real-time streaming — ⚠️ HIGHEST RISK, LEAST TESTED PART OF THIS CODEBASE

Implemented as a **hand-rolled RFC 6455 WebSocket client** (`MarketWebSocket.kt`)
using raw `Socket`/`SSLSocket` — no OkHttp or any WebSocket library was added,
keeping the project dependency-free the same way every other provider uses
plain `HttpURLConnection`. It handles the HTTP upgrade handshake, masked
outgoing frames, unmasked incoming frames (small/medium/large length
encoding), ping/pong, and clean close. `MarketRealtimeManager` subscribes to
Binance Spot's public `bookTicker`+`trade` streams (no key) for the
currently-open crypto instrument, with capped exponential-backoff reconnect
and an explicit staleness check (no message for 20s → the LIVE badge
downgrades, never claims real-time when the stream has gone quiet).

**This was written carefully against the RFC but has not been exercised
against a live server in this sandbox (no network access here).** Every
failure mode degrades to the existing REST/cache pipeline rather than
crashing, but please smoke-test this specifically on a real device before
relying on it — it is, by a wide margin, the least-proven code in this
project.

### P2 — Options (Deribit)

`MarketOptions.kt` — Deribit's `public/get_book_summary_by_currency` (free,
keyless, confirmed against Deribit's own docs). BTC/ETH only (Deribit
doesn't list other underlyings). Put/call OI ratio, volume-weighted IV
context, and top-OI strike concentrations are all explicitly labeled
**DERIVED** (computed here from raw per-contract data), never presented as
something Deribit returned directly. Used only as low-weight positioning
context in the confluence engine, per the spec's explicit warning against
letting options alone determine BUY/SELL.

### P3 — Liquidations

`MarketLiquidations.kt`'s `MarketLiquidationFeed` uses Binance Futures'
public `!forceOrder@arr` all-market WebSocket stream (via the same
hand-rolled client) and filters the shared rolling window down to the
open instrument's symbol. **Honesty note**: Binance's WebSocket migration
deadline (2026-04-23) has already passed as of this build, and Binance's own
docs don't clearly state which routing bucket `forceOrder` now falls under.
This is implemented against the long-documented legacy URL; if it's been
moved, the feed will simply fail to receive data and correctly report
`UNAVAILABLE` rather than pretending to be live. A rolling 5-minute
long/short liquidation notional and a transparent spike heuristic are shown
as market **context**, never as an automatic reversal signal.

### P4 — Volume Profile / VPOC

`MarketLiquidations.kt`'s `MarketVolumeProfileEngine` — pure computation
from candles already fetched, no new network dependency. Explicitly labeled
**"CANDLE-DERIVED VOLUME PROFILE"** everywhere it's shown, since OHLCV bars
don't carry true tick-by-price data; each candle's volume is spread evenly
across the price bins its high-low range touches (the standard honest
approximation without tick data). Point of Control + 70% value area
computed with the usual expand-from-POC methodology.

### P5 — Fundamentals (SEC EDGAR)

`MarketFundamentals.kt` — official, free, keyless `data.sec.gov` XBRL API.
Only requirement is a descriptive `User-Agent` (SEC returns 403 without
one) — no API key of any kind. US-listed equities only, via a small curated
ticker→CIK map (the full SEC ticker file is 1-2MB — not worth downloading
for a handful of favorites). Revenue, net income, assets, liabilities,
equity, operating cash flow, capex, and shares outstanding are all
**REPORTED** (straight from the filing); Free Cash Flow (OCF − CapEx) is
explicitly labeled **CALCULATED**. Shown as narrative context only, never
folded into the short-term technical confluence vote, per the spec's own
instruction.

### P6 — Cross-market correlation

`MarketCorrelationMacro.kt`'s `MarketCorrelationEngine` reuses the existing
provider/cache path (no new network provider type) to fetch a fixed
reference instrument (S&P 500, already in the catalog) and computes a real
Pearson correlation. Crypto and equity candles have different real
granularities (documented earlier in this file), so points are matched by
**nearest timestamp within a 2-day tolerance**, not naive index-pairing —
and that method is stated in the result, never hidden. Requires ≥15 matched
samples or reports `UNAVAILABLE`. Context only, never a trading signal.

### P7 — Historical signal evaluation / backtest

`MarketBacktest.kt` — two independent pieces:
- **`MarketSignalLog`**: a bounded (300-entry), SharedPreferences-based log
  of every verdict the live app actually produces, for future outcome
  tracking as real time passes.
- **`MarketBacktestEngine`**: an on-demand (never automatic — triggered by a
  "Run Historical Evaluation" button) **walk-forward** replay over whatever
  candle history the current provider returned. At each decision point, it
  calls `MarketAnalysisEngine.quickDecision()` — the exact same deterministic
  pipeline the live app uses, refactored to be reusable rather than
  duplicated — using **only** candles up to that index (verified: `candles
  = candles.subList(0, i + 1)`), then scans strictly forward within the same
  already-fetched array to see whether target or stop was hit first. This
  is genuinely no-look-ahead, but bounded by the free tier's real history
  depth (crypto: ~90 candles; equities: whatever Stooq returns) — sample
  sizes are usually small, and every result carries `sampleTooSmall` (true
  below n=20) rather than a false sense of statistical confidence. **This is
  not a promise of future performance.**

### P8 — Macro

`MarketCorrelationMacro.kt`'s `MarketMacroProvider` — ECB Data Portal
(`data-api.ecb.europa.eu`) is genuinely free and keyless (confirmed against
ECB's own docs): the Eurozone deposit facility rate and HICP inflation
(YoY). **These specific SDMX series keys were not hit live from this
sandbox** — if ECB has changed the key format, this degrades to
`UNAVAILABLE` rather than crashing or guessing, but hasn't been verified
against a real response. FRED (US macro) requires a free-registration API
key; per the spec's explicit instruction ("never hardcode, treat as optional
configuration"), FRED support is wired but **off by default** — the key
constant is intentionally blank, so it correctly reports `UNAVAILABLE`
until someone supplies their own key. Shown as narrative context only.

### Confluence Engine extended again

7 → 13 possible categories (added Liquidations, Options; Multi-Timeframe,
Order Flow, Derivatives, Sentiment were already added in Phase 2).
Fundamentals/Correlation/Macro deliberately do **not** enter the vote at
all — they're narrative-only context, per the spec's repeated warnings
against letting any single non-technical dataset drive BUY/SELL.

### Performance / scope control

Options, fundamentals, correlation, and macro are fetched only from the
`fetchDeepDiveAsync` path (single-instrument detail screen), same as
Phase 2's order book/derivatives/sentiment/MTF — never from the list.
Real-time streaming and the liquidation feed are separate, explicit
start/stop lifecycles: they start when a crypto instrument's detail screen
opens and are stopped both by the in-app back action (including the system
back gesture, which routes through the same handler) **and** by
`MainActivity.onPause()` as a backstop, so leaving the app entirely (home
button, app switch) while the detail screen is open can never leave a
socket running in the background. The backtest is opt-in (a button), never
automatic, and runs on a background thread over data already in memory (no
network).

### Known minor limitation

If the app is paused (e.g. home button) while the detail screen is open and
then resumed, the real-time badge can show a stale "CONNECTED" state for a
moment since `onPause()` stops the socket but the on-screen badge isn't
rebuilt until the next update — the underlying REST/cache data is
unaffected; this is a cosmetic display lag, not a data-integrity issue.

### Testing

Same method as every prior phase: static verification only — brace/paren
balance across all 23 Kotlin files (confirmed with a byte-level counter,
with several benign false positives manually traced to literal parentheses
inside display-text strings), every new constructor call cross-checked
field-by-field against its data class, every function call checked against
its signature, another instance of the literal-`%%`-in-plain-interpolation
bug caught and fixed. **No live network or Android SDK is available in this
environment, so none of the new endpoints (Binance WebSocket/depth/futures,
Deribit, SEC EDGAR, ECB, Alternative.me) were exercised against a real
response, and the WebSocket client has not been run against a real socket
at all.** This is the most build-untested phase of the project so far,
proportional to how much of it (P1, P3) required protocols this environment
cannot simulate. Please build and smoke-test before relying on any of it,
especially the real-time and liquidation streams.
