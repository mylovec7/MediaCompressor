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
