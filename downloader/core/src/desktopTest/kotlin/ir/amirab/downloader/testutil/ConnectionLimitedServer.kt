package ir.amirab.downloader.testutil

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A tiny HTTP/1.1 file server for tests that behaves like a host which limits
 * concurrent connections per client (common on file hosts / CDNs):
 *
 * - serves [size] bytes of deterministic content ([byteAt]) with `Range` support
 * - at most [maxConcurrent] requests are served at the same time; extra requests are
 *   refused with `503` ([RefuseMode.Http503]) or by dropping the socket without any
 *   response ([RefuseMode.Reset])
 * - every connection is throttled to [bytesPerSecondPerConnection], so total throughput
 *   is proportional to the number of connections that actually get served
 *
 * Every response uses `Connection: close`, so one request == one TCP connection and
 * [peakConcurrent] is exactly the number of simultaneously served downloads.
 */
class ConnectionLimitedServer(
    val size: Long,
    maxConcurrent: Int,
    private val bytesPerSecondPerConnection: Long,
    private val refuseMode: RefuseMode = RefuseMode.Http503,
    private val penalty: Penalty? = null,
) : Closeable {
    /**
     * Anti-hammering, as many hosts do: a client that got more than [maxRefusals] refusals within
     * [windowMs] is throttled to 1/[slowdownFactor] of the normal speed until it calms down.
     */
    data class Penalty(
        val maxRefusals: Int,
        val windowMs: Long,
        val slowdownFactor: Int,
    )

    enum class RefuseMode { Http503, Reset }

    private val serverSocket = ServerSocket(0, 512, InetAddress.getLoopbackAddress())
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "test-http-server").apply { isDaemon = true }
    }
    private val active = AtomicInteger(0)

    /** can be changed while running, e.g. to simulate a host that accepts more connections again */
    @Volatile
    var maxConcurrent: Int = maxConcurrent

    /** requests that were refused because the connection limit was reached */
    val refused = AtomicInteger(0)

    /** requests that got a 200/206 response */
    val served = AtomicInteger(0)

    /** highest number of simultaneously served requests */
    val peakConcurrent = AtomicInteger(0)

    private val refusalTimes = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    /** true while the client is being punished for too many refused connections */
    private fun isPenalized(): Boolean {
        val p = penalty ?: return false
        val now = System.currentTimeMillis()
        while (true) {
            val first = refusalTimes.peekFirst() ?: break
            if (now - first > p.windowMs) refusalTimes.pollFirst() else break
        }
        return refusalTimes.size > p.maxRefusals
    }

    val url: String get() = "http://127.0.0.1:${serverSocket.localPort}/file.bin"

    @Volatile
    private var closed = false

    private val acceptor = thread(isDaemon = true, name = "test-http-acceptor") {
        while (!closed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            executor.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val headers = readRequestHeaders(input) ?: return
                val range = headers["range"]?.let(::parseRange)
                val output = socket.getOutputStream()

                val nowActive = active.incrementAndGet()
                if (nowActive > maxConcurrent) {
                    active.decrementAndGet()
                    refused.incrementAndGet()
                    refusalTimes.addLast(System.currentTimeMillis())
                    when (refuseMode) {
                        RefuseMode.Http503 -> {
                            output.write(
                                ("HTTP/1.1 503 Service Unavailable\r\n" +
                                        "Content-Length: 0\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray()
                            )
                            output.flush()
                        }

                        RefuseMode.Reset -> {
                            // drop the connection without a response
                            socket.setSoLinger(true, 0)
                        }
                    }
                    return
                }
                try {
                    served.incrementAndGet()
                    peakConcurrent.accumulateAndGet(nowActive, ::maxOf)
                    serve(output, range)
                } finally {
                    active.decrementAndGet()
                }
            } catch (_: IOException) {
                // client went away (e.g. the part was split or the download was paused)
            }
        }
    }

    private fun serve(output: OutputStream, range: LongRange?) {
        val start = range?.first ?: 0L
        val end = range?.last?.coerceAtMost(size - 1) ?: (size - 1)
        val length = end - start + 1
        val head = buildString {
            if (range != null) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: bytes $start-$end/$size\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
            }
            append("Content-Type: application/octet-stream\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray())
        val chunk = ByteArray(16 * 1024)
        val startedAt = System.nanoTime()
        var sent = 0L
        var budgetNanos = 0L
        while (sent < length && !closed) {
            val n = minOf(chunk.size.toLong(), length - sent).toInt()
            for (i in 0 until n) chunk[i] = byteAt(start + sent + i)
            output.write(chunk, 0, n)
            sent += n
            // throttle: never be ahead of the allowed speed
            val chunkNanos = n * 1_000_000_000L / bytesPerSecondPerConnection
            budgetNanos += if (isPenalized()) chunkNanos * penalty!!.slowdownFactor else chunkNanos
            val aheadNanos = budgetNanos - (System.nanoTime() - startedAt)
            if (aheadNanos > 0) {
                Thread.sleep(aheadNanos / 1_000_000, (aheadNanos % 1_000_000).toInt())
            }
        }
        output.flush()
    }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        executor.shutdownNow()
        acceptor.join(1000)
    }

    companion object {
        fun byteAt(position: Long): Byte {
            return (position xor (position ushr 8) xor (position ushr 17) xor 0x5A).toByte()
        }

        private fun readRequestHeaders(input: InputStream): Map<String, String>? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return null
                if (c == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.clear()
                    if (text.isEmpty()) break
                    lines.add(text)
                } else {
                    line.append(c.toChar())
                }
            }
            return lines.drop(1).mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
        }

        private fun parseRange(header: String): LongRange? {
            val spec = header.removePrefix("bytes=").split(",").first().trim()
            val (a, b) = spec.split("-", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val start = a.toLongOrNull() ?: return null
            val end = b.toLongOrNull() ?: Long.MAX_VALUE
            return start..end
        }
    }
}
