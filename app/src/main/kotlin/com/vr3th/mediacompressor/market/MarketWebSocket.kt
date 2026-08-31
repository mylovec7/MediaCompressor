package com.vr3th.mediacompressor.market

import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// =============================================================================
// MARKET MODULE PHASE 3 — WEBSOCKET CLIENT (P1)
// =============================================================================
// IMPORTANT HONESTY NOTE (see MARKET_MODULE.md): this is a minimal, from-scratch
// RFC 6455 client written to keep the project dependency-free, the same way
// every other Market provider uses plain HttpURLConnection instead of a
// networking library. It has been written carefully against the RFC, but it
// has NOT been exercised against a live server in this environment (no
// network access here) — treat it as the least-tested part of this codebase
// and verify it on a real device before relying on it. Every failure mode
// degrades to REST/cache, never a crash and never a fabricated LIVE state.
// =============================================================================

internal object MarketWebSocketExecutors {
    // Each open socket owns one dedicated daemon thread for its blocking read loop —
    // deliberately NOT the shared HTTP executor pool, since a WS connection blocks for
    // its entire lifetime and would otherwise starve ordinary REST calls.
    fun newReaderThread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply { isDaemon = true }
}

/** A single WebSocket connection to one `wss://` URL. Text-frame only (every stream this app
 * uses is JSON text) with ping/pong auto-reply and clean close handling. */
internal class MarketWebSocketClient(
    private val url: String,
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: (reason: String) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = MarketWebSocketExecutors.newReaderThread("market-ws-${System.identityHashCode(this)}") { runLoop() }
        thread?.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun runLoop() {
        try {
            val parsed = parseWssUrl(url) ?: run { onClosed("BAD_URL"); return }
            val raw = Socket()
            raw.connect(InetSocketAddress(parsed.host, parsed.port), 8000)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, parsed.host, parsed.port, true) as SSLSocket
            ssl.soTimeout = 15 * 60 * 1000 // generous read timeout; Binance pings every 3 min so this only trips on a truly dead connection
            ssl.startHandshake()
            socket = ssl

            val key = generateSecWebSocketKey()
            val output = ssl.outputStream
            val input = BufferedInputStream(ssl.inputStream)
            sendHandshake(output, parsed.host, parsed.path, key)
            if (!readHandshakeResponse(input)) { onClosed("HANDSHAKE_FAILED"); safeClose(ssl); return }

            onOpen()
            readFrameLoop(input, output)
        } catch (e: Exception) {
            onClosed(e.message ?: e.javaClass.simpleName)
        } finally {
            running = false
            safeClose(socket)
        }
    }

    private fun safeClose(s: Socket?) { try { s?.close() } catch (_: Exception) { } }

    private data class HostPortPath(val host: String, val port: Int, val path: String)
    private fun parseWssUrl(u: String): HostPortPath? {
        if (!u.startsWith("wss://")) return null
        val rest = u.removePrefix("wss://")
        val slashIdx = rest.indexOf('/')
        val hostPort = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
        val path = if (slashIdx >= 0) rest.substring(slashIdx) else "/"
        val colonIdx = hostPort.indexOf(':')
        val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
        val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443
        return HostPortPath(host, port, path)
    }

    private fun generateSecWebSocketKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun sendHandshake(output: OutputStream, host: String, path: String, key: String) {
        val req = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        output.write(req.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Reads only the HTTP status line + headers up to the blank line; does not consume any frame bytes after it. */
    private fun readHandshakeResponse(input: InputStream): Boolean {
        val sb = StringBuilder()
        val lastFour = IntArray(4) { -1 }
        while (true) {
            val b = input.read()
            if (b == -1) return false
            sb.append(b.toChar())
            lastFour[0] = lastFour[1]; lastFour[1] = lastFour[2]; lastFour[2] = lastFour[3]; lastFour[3] = b
            if (lastFour[0] == '\r'.code && lastFour[1] == '\n'.code && lastFour[2] == '\r'.code && lastFour[3] == '\n'.code) break
            if (sb.length > 16384) return false // guard against a runaway/garbage response
        }
        val statusLine = sb.lineSequence().firstOrNull() ?: return false
        return statusLine.contains(" 101 ")
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) throw java.io.EOFException("Socket closed mid-frame")
            read += n
        }
    }

    private fun readFrameLoop(input: InputStream, output: OutputStream) {
        val messageBuffer = java.io.ByteArrayOutputStream()
        while (running) {
            val b0 = input.read()
            if (b0 == -1) { onClosed("EOF"); return }
            val b1 = input.read()
            if (b1 == -1) { onClosed("EOF"); return }
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0 // server frames are never masked per spec, but read defensively
            var payloadLen = (b1 and 0x7F).toLong()
            if (payloadLen == 126L) {
                val ext = ByteArray(2); readFully(input, ext, 2)
                payloadLen = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
            } else if (payloadLen == 127L) {
                val ext = ByteArray(8); readFully(input, ext, 8)
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (ext[i].toLong() and 0xFF)
                payloadLen = v
            }
            if (payloadLen > 4_000_000L) { onClosed("FRAME_TOO_LARGE"); return } // guard against a runaway allocation
            val maskKey = if (masked) ByteArray(4).also { readFully(input, it, 4) } else null
            val payload = ByteArray(payloadLen.toInt())
            readFully(input, payload, payloadLen.toInt())
            if (maskKey != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()

            when (opcode) {
                0x1, 0x0 -> { // text or continuation — bytes are accumulated raw and decoded only once complete,
                    // so a multi-byte UTF-8 character split across two frames is never corrupted mid-decode.
                    messageBuffer.write(payload)
                    if (fin) {
                        onText(String(messageBuffer.toByteArray(), Charsets.UTF_8))
                        messageBuffer.reset()
                    }
                }
                0x8 -> { onClosed("SERVER_CLOSE"); return } // close frame
                0x9 -> writeFrame(output, 0xA, payload) // ping -> pong (echo payload)
                0xA -> { /* pong received, nothing to do */ }
                else -> { /* binary or reserved opcode — this app never expects these, ignore safely */ }
            }
        }
    }

    /** Client-to-server frames MUST be masked per RFC 6455 — a random mask is generated per frame. */
    private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        val header = ArrayList<Byte>()
        header.add((0x80 or opcode).toByte()) // FIN=1, opcode
        val len = masked.size
        when {
            len <= 125 -> header.add((0x80 or len).toByte())
            len <= 65535 -> { header.add((0x80 or 126).toByte()); header.add(((len shr 8) and 0xFF).toByte()); header.add((len and 0xFF).toByte()) }
            else -> { header.add((0x80 or 127).toByte()); for (i in 7 downTo 0) header.add(((len.toLong() shr (8 * i)) and 0xFF).toByte()) }
        }
        mask.forEach { header.add(it) }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }
}

/**
 * Lifecycle-aware real-time manager for one crypto instrument. Subscribes to Binance Spot's
 * combined `bookTicker` + `trade` streams (no key needed), updates an in-memory
 * [RealtimeQuoteState], and reconnects with capped exponential backoff. REST/cache remain the
 * source of truth for everything else — this only ever feeds the live bid/ask/last-trade badge.
 */
object MarketRealtimeManager {
    private const val MAX_BACKOFF_MS = 30_000L
    private const val STALE_AFTER_MS = 20_000L // no message for 20s on a "connected" stream -> treat as stale, not LIVE

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var client: MarketWebSocketClient? = null
    private var reconnectAttempts = 0
    @Volatile private var wanted = false // becomes false the instant the detail screen is left — stops any further reconnects

    @Volatile private var state = RealtimeQuoteState(null, null, null, null, StreamConnectionState.DISCONNECTED, 0L, true)

    fun currentState(): RealtimeQuoteState = state

    /** Starts streaming for [instrument] if it's crypto and has a Binance symbol; otherwise reports UNSUPPORTED once, synchronously. */
    fun start(instrument: MarketInstrument, onUpdate: (RealtimeQuoteState) -> Unit) {
        val symbol = BinanceSymbolMap.symbolFor(instrument)
        if (symbol == null) {
            state = RealtimeQuoteState(null, null, null, null, StreamConnectionState.UNSUPPORTED, System.currentTimeMillis(), true)
            onUpdate(state)
            return
        }
        wanted = true
        reconnectAttempts = 0
        connect(symbol.lowercase(), onUpdate)
    }

    /** MUST be called when the detail screen is left — stops the socket and prevents any further reconnect attempts. */
    fun stop() {
        wanted = false
        client?.stop()
        client = null
        state = RealtimeQuoteState(null, null, null, null, StreamConnectionState.DISCONNECTED, System.currentTimeMillis(), true)
    }

    private fun connect(lowerSymbol: String, onUpdate: (RealtimeQuoteState) -> Unit) {
        if (!wanted) return
        state = state.copy(connection = StreamConnectionState.CONNECTING)
        mainHandler.post { onUpdate(state) }
        val url = "wss://stream.binance.com:9443/stream?streams=$lowerSymbol@bookTicker/$lowerSymbol@trade"
        val c = MarketWebSocketClient(
            url = url,
            onOpen = {
                reconnectAttempts = 0
                state = state.copy(connection = StreamConnectionState.CONNECTED, lastMessageMillis = System.currentTimeMillis(), isStale = false)
                mainHandler.post { onUpdate(state) }
            },
            onText = { text -> handleMessage(text, onUpdate) },
            onClosed = { _ ->
                client = null
                if (wanted) scheduleReconnect(lowerSymbol, onUpdate) else {
                    state = state.copy(connection = StreamConnectionState.DISCONNECTED)
                    mainHandler.post { onUpdate(state) }
                }
            }
        )
        client = c
        c.start()
    }

    private fun scheduleReconnect(lowerSymbol: String, onUpdate: (RealtimeQuoteState) -> Unit) {
        state = state.copy(connection = StreamConnectionState.RECONNECTING)
        mainHandler.post { onUpdate(state) }
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(10) // capped — never an unbounded reconnect storm
        val backoff = (1000L * (1L shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(MAX_BACKOFF_MS)
        mainHandler.postDelayed({ if (wanted) connect(lowerSymbol, onUpdate) }, backoff)
    }

    private fun handleMessage(text: String, onUpdate: (RealtimeQuoteState) -> Unit) {
        try {
            val obj = org.json.JSONObject(text)
            val data = obj.optJSONObject("data") ?: obj
            val eventType = data.optString("e", "")
            val now = System.currentTimeMillis()
            when {
                data.has("b") && data.has("a") && eventType.isEmpty() -> { // bookTicker payload (no "e" field on this stream)
                    val bid = data.optString("b").toDoubleOrNull()
                    val ask = data.optString("a").toDoubleOrNull()
                    state = state.copy(bestBid = bid ?: state.bestBid, bestAsk = ask ?: state.bestAsk, connection = StreamConnectionState.CONNECTED, lastMessageMillis = now, isStale = false)
                }
                eventType == "trade" -> {
                    val price = data.optString("p").toDoubleOrNull()
                    val qty = data.optString("q").toDoubleOrNull()
                    state = state.copy(lastTradePrice = price ?: state.lastTradePrice, lastTradeQty = qty ?: state.lastTradeQty, connection = StreamConnectionState.CONNECTED, lastMessageMillis = now, isStale = false)
                }
                else -> return
            }
            mainHandler.post { onUpdate(state) }
        } catch (_: Exception) { /* a single malformed message must never break the stream */ }
    }

    /** Call periodically (e.g. once per UI refresh tick) to flip [RealtimeQuoteState.isStale] if no message has
     * arrived recently, even though the socket itself hasn't reported a close. */
    fun refreshStaleness(onUpdate: (RealtimeQuoteState) -> Unit) {
        if (state.connection == StreamConnectionState.CONNECTED && System.currentTimeMillis() - state.lastMessageMillis > STALE_AFTER_MS) {
            state = state.copy(isStale = true)
            onUpdate(state)
        }
    }
}
