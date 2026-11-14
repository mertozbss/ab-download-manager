package ir.amirab.downloader.downloaditem.http

import ir.amirab.downloader.DownloadManager
import ir.amirab.downloader.DownloadSettings
import ir.amirab.downloader.DownloaderRegistry
import ir.amirab.downloader.connection.OkHttpHttpDownloaderClient
import ir.amirab.downloader.connection.UserAgentProvider
import ir.amirab.downloader.connection.proxy.AutoConfigurableProxyProvider
import ir.amirab.downloader.connection.proxy.NoopSystemProxySelectorProvider
import ir.amirab.downloader.connection.proxy.ProxyStrategy
import ir.amirab.downloader.connection.proxy.ProxyStrategyProvider
import ir.amirab.downloader.db.MemoryDownloadListDB
import ir.amirab.downloader.db.MemoryDownloadPartStatesDB
import ir.amirab.downloader.downloaditem.DownloadJobStatus
import ir.amirab.downloader.testutil.ConnectionLimitedServer
import ir.amirab.downloader.testutil.ConnectionLimitedServer.RefuseMode
import ir.amirab.downloader.utils.EmptyFileCreator
import ir.amirab.downloader.utils.IDiskStat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end: a real [HttpDownloadJob] with OkHttp against a local server that only
 * serves a few connections at a time and refuses the rest (like many file hosts).
 *
 * Before the fix the refused parts kept their ranges and retried every second forever,
 * so they never downloaded anything while the job kept hammering the host. Now a refused
 * part gives its connection slot back, the job lowers its effective connection count to
 * what the host accepts and the healthy connections pick up the refused ranges.
 */
class ConnectionLimitedHostTest {
    private val tempDir: File = Files.createTempDirectory("abdm-conn-test").toFile()
    private val servers = mutableListOf<ConnectionLimitedServer>()

    @AfterTest
    fun cleanUp() {
        servers.forEach { it.close() }
        tempDir.deleteRecursively()
    }

    private class Result(
        val seconds: Double,
        val refused: Int,
        val served: Int,
        val peak: Int,
        val perSecondMiB: List<Double>,
    ) {
        override fun toString(): String {
            return "time=%.2fs refused=%d served=%d peakConcurrent=%d speeds(MiB/s)=%s".format(
                seconds, refused, served, peak, perSecondMiB.joinToString { "%.1f".format(it) }
            )
        }
    }

    private fun download(
        size: Long,
        maxConcurrent: Int,
        bytesPerSecondPerConnection: Long,
        connections: Int = 8,
        refuseMode: RefuseMode = RefuseMode.Http503,
        penalty: ConnectionLimitedServer.Penalty? = null,
        raiseLimitAfterMs: Long? = null,
        raisedLimit: Int = maxConcurrent,
    ): Result = runBlocking {
        val server = ConnectionLimitedServer(
            size = size,
            maxConcurrent = maxConcurrent,
            bytesPerSecondPerConnection = bytesPerSecondPerConnection,
            refuseMode = refuseMode,
            penalty = penalty,
        ).also { servers.add(it) }

        val settings = DownloadSettings(
            defaultThreadCount = connections,
            dynamicPartCreationMode = true,
            maxDownloadRetryCount = 3,
            minPartSize = DownloadSettings.DEFAULT_MIN_PART_SIZE,
            useSparseFileAllocation = false,
        )
        val okHttp = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = Int.MAX_VALUE
                maxRequestsPerHost = Int.MAX_VALUE
            })
            .build()
        val client = OkHttpHttpDownloaderClient(
            okHttpClient = okHttp,
            customUserAgentProvider = object : UserAgentProvider {
                override fun getUserAgent(): String? = null
            },
            proxyStrategyProvider = object : ProxyStrategyProvider {
                override fun getProxyStrategyFor(url: String) = ProxyStrategy.Direct
            },
            systemProxySelectorProvider = NoopSystemProxySelectorProvider(),
            autoConfigurableProxyProvider = AutoConfigurableProxyProvider.NoOp(),
        )
        val downloadManager = DownloadManager(
            dlListDb = MemoryDownloadListDB(),
            partListDb = MemoryDownloadPartStatesDB(),
            settings = settings,
            emptyFileCreator = EmptyFileCreator(
                diskStat = object : IDiskStat {
                    override fun getRemainingSpace(path: File): Long = Long.MAX_VALUE
                },
                useSparseFile = { false },
            ),
            downloaderRegistry = DownloaderRegistry(),
            downloadDataFolder = tempDir,
        )
        val item = HttpDownloadItem(
            link = server.url,
            id = 1,
            folder = tempDir.absolutePath,
            name = "file.bin",
        )
        val job = HttpDownloadJob(item, downloadManager, client)
        job.boot()

        val speeds = mutableListOf<Double>()
        val startedAt = System.nanoTime()
        val sampler = launch {
            var last = 0L
            while (true) {
                delay(1000)
                val now = job.getDownloadedSize()
                speeds.add((now - last) / (1024.0 * 1024.0))
                last = now
            }
        }
        val limitRaiser = raiseLimitAfterMs?.let {
            launch {
                delay(it)
                server.maxConcurrent = raisedLimit
            }
        }
        try {
            job.resume()
            withTimeout(90.seconds) {
                val final = job.status.first {
                    it is DownloadJobStatus.Finished || it is DownloadJobStatus.Canceled
                }
                assertTrue(final is DownloadJobStatus.Finished, "download did not finish: $final")
            }
        } finally {
            sampler.cancel()
            limitRaiser?.cancel()
            job.pause()
            job.close()
            okHttp.dispatcher.executorService.shutdownNow()
            okHttp.connectionPool.evictAll()
        }
        val seconds = (System.nanoTime() - startedAt) / 1e9

        val file = File(tempDir, "file.bin")
        assertEquals(size, file.length(), "output size")
        file.inputStream().buffered(1 shl 16).use { input ->
            var pos = 0L
            while (true) {
                val b = input.read()
                if (b == -1) break
                if (b.toByte() != ConnectionLimitedServer.byteAt(pos)) {
                    throw AssertionError("content mismatch at byte $pos")
                }
                pos++
            }
        }
        Result(
            seconds = seconds,
            refused = server.refused.get(),
            served = server.served.get(),
            peak = server.peakConcurrent.get(),
            perSecondMiB = speeds,
        ).also { println("[ConnectionLimitedHostTest] maxConcurrent=$maxConcurrent mode=$refuseMode $it") }
    }

    private val mib = 1024L * 1024L

    @Test
    fun hostRefusingExtraConnectionsWith503() {
        // host serves 3 connections at 2 MiB/s each -> best possible is 6 MiB/s, 48 MiB in 8s
        val r = download(size = 48 * mib, maxConcurrent = 3, bytesPerSecondPerConnection = 2 * mib)
        assertThroughputHeld(r, idealSeconds = 8.0)
    }

    @Test
    fun hostDroppingExtraConnectionsWithoutResponse() {
        val r = download(
            size = 48 * mib, maxConcurrent = 3, bytesPerSecondPerConnection = 2 * mib,
            refuseMode = RefuseMode.Reset,
        )
        assertThroughputHeld(r, idealSeconds = 8.0)
    }

    @Test
    fun hostThatThrottlesClientsWhichKeepHammering() {
        // like many file hosts: more than 15 refused connections within 10s and the client is
        // slowed down to a quarter. Retrying refused parts every second forever triggers that.
        val r = download(
            size = 48 * mib, maxConcurrent = 3, bytesPerSecondPerConnection = 2 * mib,
            penalty = ConnectionLimitedServer.Penalty(maxRefusals = 20, windowMs = 10_000, slowdownFactor = 4),
        )
        assertThroughputHeld(r, idealSeconds = 8.0)
    }

    @Test
    fun usesMoreConnectionsAgainWhenTheHostAllowsIt() {
        // host accepts 3 connections at first and 8 from second 2 on: after the probe
        // interval the download must climb back up instead of staying at 3 forever
        val r = download(
            size = 96 * mib, maxConcurrent = 3, bytesPerSecondPerConnection = 2 * mib,
            raiseLimitAfterMs = 2_000, raisedLimit = 8,
        )
        assertTrue(r.peak >= 6, "did not use the extra connections: $r")
        // staying at 3 connections would take 16s
        assertTrue(r.seconds < 14.0, "too slow to recover: $r")
        assertTrue(r.refused <= 25, "kept hammering the host: $r")
    }

    @Test
    fun unlimitedHostStillUsesAllConnections() {
        // no connection limit: 8 connections x 2 MiB/s = 16 MiB/s -> 32 MiB in 2s
        val r = download(size = 32 * mib, maxConcurrent = 100, bytesPerSecondPerConnection = 2 * mib)
        assertEquals(0, r.refused)
        assertEquals(8, r.peak, "all 8 connections should be used when the host allows it")
        assertTrue(r.seconds < 2.0 * 2.5, "took too long: $r")
    }

    private fun assertThroughputHeld(r: Result, idealSeconds: Double) {
        // the host's limit must be respected and used
        assertEquals(3, r.peak, "peak concurrency: $r")
        // throughput must stay close to what the host allows (no collapse)
        assertTrue(r.seconds < idealSeconds * 1.5, "throughput collapsed: $r")
        // and we must not keep hammering the host with connections it refuses
        // (5 surplus connections x 2 attempts at start + a few probes)
        assertTrue(r.refused <= 25, "kept hammering the host: $r")
    }
}
