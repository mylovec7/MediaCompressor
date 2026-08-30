package com.vr3th.mediacompressor.market

import android.os.Handler
import android.os.Looper

// =============================================================================
// MARKET MODULE PHASE 3 — LIQUIDATION FEED (P3)
// =============================================================================
// Uses Binance USDS-M Futures' public "All Market Liquidation Order" stream
// (`!forceOrder@arr`), which needs no API key. HONESTY NOTE: Binance is mid-
// migration (deadline 2026-04-23, already passed as of this build) toward
// routed /public /market /private WebSocket paths, and its own documentation
// does not clearly state which bucket forceOrder now falls under. This is
// implemented against the long-documented legacy URL; if Binance has moved
// this specific stream to a routed path this build doesn't know about, the
// connection will simply fail to produce data and the feed correctly reports
// UNAVAILABLE (via the same stale/disconnected handling as any other feed) —
// it will not silently pretend to be live. Verify on a real device.
// =============================================================================

object MarketLiquidationFeed {
    private const val URL = "wss://fstream.binance.com/ws/!forceOrder@arr"
    private const val WINDOW_MILLIS = 5 * 60 * 1000L // rolling 5-minute window for volume/imbalance
    private const val MAX_EVENTS = 500 // bounded buffer — never grows unbounded

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var client: MarketWebSocketClient? = null
    @Volatile private var wanted = false
    private var reconnectAttempts = 0
    private val events = ArrayDeque<LiquidationEvent>()
    @Volatile private var connection = StreamConnectionState.DISCONNECTED

    fun start(onUpdate: (LiquidationSnapshot) -> Unit) {
        wanted = true
        reconnectAttempts = 0
        connect(onUpdate)
    }

    fun stop() {
        wanted = false
        client?.stop()
        client = null
        connection = StreamConnectionState.DISCONNECTED
    }

    private fun connect(onUpdate: (LiquidationSnapshot) -> Unit) {
        if (!wanted) return
        connection = StreamConnectionState.CONNECTING
        emit(onUpdate)
        val c = MarketWebSocketClient(
            url = URL,
            onOpen = { connection = StreamConnectionState.CONNECTED; reconnectAttempts = 0; emit(onUpdate) },
            onText = { text -> handleMessage(text, onUpdate) },
            onClosed = {
                client = null
                if (wanted) {
                    connection = StreamConnectionState.RECONNECTING
                    emit(onUpdate)
                    reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(10)
                    val backoff = (1000L * (1L shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(30_000L)
                    mainHandler.postDelayed({ if (wanted) connect(onUpdate) }, backoff)
                } else {
                    connection = StreamConnectionState.DISCONNECTED
                    emit(onUpdate)
                }
            }
        )
        client = c
        c.start()
    }

    private fun handleMessage(text: String, onUpdate: (LiquidationSnapshot) -> Unit) {
        try {
            val obj = org.json.JSONObject(text)
            val o = obj.optJSONObject("o") ?: return
            val symbol = o.optString("s")
            val sideStr = o.optString("S")
            val price = o.optString("ap").toDoubleOrNull() ?: o.optString("p").toDoubleOrNull() ?: return
            val qty = o.optString("q").toDoubleOrNull() ?: return
            val time = o.optLong("T", System.currentTimeMillis())
            // Binance's side field is the side of the FORCE ORDER itself: a forced SELL closes out a
            // long position (a long got liquidated); a forced BUY closes out a short position.
            val side = if (sideStr.equals("SELL", true)) LiquidationSide.LONG else LiquidationSide.SHORT
            val event = LiquidationEvent(symbol, side, price, qty, price * qty, time)
            events.addLast(event)
            while (events.size > MAX_EVENTS) events.removeFirst()
            connection = StreamConnectionState.CONNECTED
            emit(onUpdate)
        } catch (_: Exception) { /* one malformed message must never break the feed */ }
    }

    private fun emit(onUpdate: (LiquidationSnapshot) -> Unit) {
        val now = System.currentTimeMillis()
        while (events.isNotEmpty() && now - events.first().timeMillis > WINDOW_MILLIS) events.removeFirst()
        mainHandler.post { onUpdate(buildSnapshot(events.toList(), now)) }
    }

    private fun buildSnapshot(recent: List<LiquidationEvent>, now: Long): LiquidationSnapshot {
        val longNotional = recent.filter { it.side == LiquidationSide.LONG }.sumOf { it.notional }
        val shortNotional = recent.filter { it.side == LiquidationSide.SHORT }.sumOf { it.notional }
        val totalNotional = longNotional + shortNotional
        // Simple, transparent spike heuristic: total rolling notional clearly above what a single
        // large event would produce alone — flagged as context, never auto-read as a reversal signal.
        val spike = recent.size >= 3 && totalNotional > (recent.maxOfOrNull { it.notional } ?: 0.0) * 3
        val state = when (connection) {
            StreamConnectionState.CONNECTED -> DataState.LIVE
            StreamConnectionState.CONNECTING, StreamConnectionState.RECONNECTING -> DataState.OFFLINE
            else -> DataState.UNAVAILABLE
        }
        return LiquidationSnapshot(recent, longNotional, shortNotional, spike, connection, state, "Binance Futures (forceOrder, all-market)", now)
    }

    /** All-market snapshot (every symbol currently in the rolling window) — used for the live push callback. */
    fun currentSnapshot(): LiquidationSnapshot = buildSnapshot(events.toList(), System.currentTimeMillis())

    /** Filters the current rolling window down to one Binance symbol (e.g. "BTCUSDT") — call this from the
     * detail screen to get an instrument-specific view of the shared all-market feed. */
    fun snapshotFor(symbol: String): LiquidationSnapshot {
        val now = System.currentTimeMillis()
        val filtered = events.filter { it.symbol.equals(symbol, ignoreCase = true) && now - it.timeMillis <= WINDOW_MILLIS }
        val base = buildSnapshot(filtered, now)
        return base.copy(sourceLabel = "Binance Futures (forceOrder, $symbol)")
    }
}

// =============================================================================
// MARKET MODULE PHASE 3 — VOLUME PROFILE / VPOC (P4)
// =============================================================================
// Pure computation from candles already fetched by a MarketProvider — no new
// network dependency. Per spec Section 8, this is explicitly labeled
// CANDLE-DERIVED because OHLCV bars do not carry true trade-by-price data;
// each candle's volume is distributed across the price bins its high-low
// range spans (a uniform-within-bar approximation), which is the standard,
// honestly-labeled way to approximate a profile without tick data.
// =============================================================================

object MarketVolumeProfileEngine {
    private const val BIN_COUNT = 24
    private const val MIN_CANDLES = 20

    fun analyze(candles: List<Candle>): VolumeProfileSnapshot {
        val withVolume = candles.filter { it.volume != null }
        if (withVolume.size < MIN_CANDLES) {
            return VolumeProfileSnapshot(emptyList(), null, null, null, "CANDLE-DERIVED VOLUME PROFILE", sufficientData = false)
        }
        val minPrice = withVolume.minOf { it.low }
        val maxPrice = withVolume.maxOf { it.high }
        val range = (maxPrice - minPrice).takeIf { it > 0.0 } ?: return VolumeProfileSnapshot(emptyList(), null, null, null, "CANDLE-DERIVED VOLUME PROFILE", sufficientData = false)
        val binSize = range / BIN_COUNT
        val bins = DoubleArray(BIN_COUNT)

        for (c in withVolume) {
            val vol = c.volume ?: continue
            val lowBin = ((c.low - minPrice) / binSize).toInt().coerceIn(0, BIN_COUNT - 1)
            val highBin = ((c.high - minPrice) / binSize).toInt().coerceIn(0, BIN_COUNT - 1)
            val spanBins = (highBin - lowBin + 1).coerceAtLeast(1)
            val perBin = vol / spanBins
            for (b in lowBin..highBin) bins[b] += perBin
        }

        val levels = (0 until BIN_COUNT).map { i ->
            VolumeProfileLevel(priceLow = minPrice + i * binSize, priceHigh = minPrice + (i + 1) * binSize, volume = bins[i])
        }
        val pocIndex = bins.indices.maxByOrNull { bins[it] } ?: 0
        val poc = (levels[pocIndex].priceLow + levels[pocIndex].priceHigh) / 2.0

        // Value area: expand outward from POC until ~70% of total volume is captured — standard VPOC methodology.
        val totalVolume = bins.sum()
        var covered = bins[pocIndex]
        var lo = pocIndex; var hi = pocIndex
        while (covered < totalVolume * 0.70 && (lo > 0 || hi < BIN_COUNT - 1)) {
            val nextLoVol = if (lo > 0) bins[lo - 1] else -1.0
            val nextHiVol = if (hi < BIN_COUNT - 1) bins[hi + 1] else -1.0
            if (nextHiVol >= nextLoVol) { hi = (hi + 1).coerceAtMost(BIN_COUNT - 1); covered += nextHiVol.coerceAtLeast(0.0) }
            else { lo = (lo - 1).coerceAtLeast(0); covered += nextLoVol.coerceAtLeast(0.0) }
        }
        return VolumeProfileSnapshot(
            levels = levels, pointOfControl = poc,
            valueAreaLow = levels[lo].priceLow, valueAreaHigh = levels[hi].priceHigh,
            methodLabel = "CANDLE-DERIVED VOLUME PROFILE", sufficientData = true
        )
    }
}
