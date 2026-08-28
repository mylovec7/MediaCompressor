package com.vr3th.mediacompressor.market

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// =============================================================================
// MARKET MODULE — NETWORK LAYER
// =============================================================================
// Native android/java.net only, per scope: no OkHttp / Retrofit / Volley.
// Every call in [MarketHttp] is BLOCKING and must only run on [MarketExecutors.io]
// (or another background thread) — never on the main thread, never inside
// a View.onDraw().
// =============================================================================

/** Bounded background pool for all Market networking. Daemon threads only —
 * never keeps the process alive, never a foreground/always-on service. */
internal object MarketExecutors {
    val io: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "market-io").apply { isDaemon = true }
    }
}

internal object MarketHttp {
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000
    // Guards against an unexpectedly huge/streaming body; every provider used
    // here returns small JSON/CSV payloads, so this is a generous ceiling.
    private const val MAX_BYTES = 3_000_000

    sealed class HttpResult {
        data class Ok(val body: String, val serverDateMillis: Long?) : HttpResult()
        data class HttpError(val code: Int) : HttpResult()
        data class NetworkError(val message: String) : HttpResult()
    }

    /** Blocking GET with a fixed connect/read timeout. Call only from a background thread. */
    fun get(urlString: String, headers: Map<String, String> = emptyMap()): HttpResult {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MediaCompressorMarket/1.0 (Android; +offline-first app)")
                setRequestProperty("Accept", "application/json,text/csv,text/plain,*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code == 429) {
                conn.errorStream?.close()
                return HttpResult.HttpError(429) // rate-limited — caller treats as a soft failure, tries next provider/cache
            }
            if (code !in 200..299) {
                conn.errorStream?.close()
                return HttpResult.HttpError(code)
            }
            val serverDate = conn.getHeaderFieldDate("Date", -1L).let { if (it > 0) it else null }
            val body = readBounded(conn)
            HttpResult.Ok(body, serverDate)
        } catch (e: java.net.SocketTimeoutException) {
            HttpResult.NetworkError("TIMEOUT")
        } catch (e: Exception) {
            HttpResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBounded(conn: HttpURLConnection): String {
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        val sb = StringBuilder()
        val buf = CharArray(4096)
        var total = 0
        reader.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                sb.append(buf, 0, n)
                if (total > MAX_BYTES) break
            }
        }
        return sb.toString()
    }
}
