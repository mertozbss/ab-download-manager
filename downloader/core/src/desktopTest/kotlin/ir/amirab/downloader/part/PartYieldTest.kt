package ir.amirab.downloader.part

import ir.amirab.downloader.connection.Connection
import ir.amirab.downloader.connection.HttpDownloaderClient
import ir.amirab.downloader.connection.response.HttpResponseInfo
import ir.amirab.downloader.downloaditem.http.HttpDownloadItem
import ir.amirab.downloader.downloaditem.http.IHttpBasedDownloadCredentials
import ir.amirab.downloader.downloaditem.http.IHttpDownloadCredentials
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PartYieldTest {
    /** a server that refuses every connection */
    private class RefusingClient : HttpDownloaderClient() {
        val attempts = AtomicInteger(0)
        override suspend fun actualHead(
            credentials: IHttpDownloadCredentials, start: Long?, end: Long?,
        ): HttpResponseInfo = throw ConnectException("refused")

        override suspend fun actualConnect(
            credentials: IHttpBasedDownloadCredentials, start: Long?, end: Long?,
        ): Connection<HttpResponseInfo> {
            attempts.incrementAndGet()
            throw ConnectException("Connection refused")
        }
    }

    private fun partDownloader(client: HttpDownloaderClient) = HttpPartDownloader(
        credentials = HttpDownloadItem(link = "http://127.0.0.1:1/x", id = 1, folder = ".", name = "x"),
        getDestWriter = { error("no data should be written") },
        part = RangedPart(from = 0, to = 999_999),
        client = client,
        speedLimiters = emptyList(),
        strictMode = true,
        partSplitLock = Any(),
        minPartProvider = { 1024 },
    ).apply {
        onTooManyErrors = {}
    }

    @Test
    fun refusedPartGivesItsSlotBackWhenTheJobAgrees() = runBlocking {
        val client = RefusingClient()
        val asked = mutableListOf<Int>()
        val pd = partDownloader(client).apply {
            shouldYieldAfterFailure = { failuresWithoutData, _ ->
                asked.add(failuresWithoutData)
                failuresWithoutData >= 2
            }
        }
        pd.start()
        withTimeout(5.seconds) {
            while (pd.active) delay(10)
        }
        assertEquals(listOf(1, 2), asked)
        assertEquals(2, client.attempts.get(), "stops right after the job agreed")
        assertEquals(PartDownloadStatus.IDLE, pd.statusFlow.value, "idle, not 'disconnected'")
        assertEquals(0L, pd.part.current, "range is kept for another connection")
        assertTrue(pd.yielding)

        // the job can start it again later (e.g. when another connection becomes free)
        pd.shouldYieldAfterFailure = { _, _ -> false }
        pd.start()
        assertFalse(pd.yielding)
        delay(400)
        assertTrue(pd.active)
        pd.stop()
        pd.join()
    }

    @Test
    fun keepsRetryingWithBackoffWhenTheJobSaysNo() = runBlocking {
        val client = RefusingClient()
        val pd = partDownloader(client).apply {
            shouldYieldAfterFailure = { _, _ -> false }
        }
        pd.start()
        delay(2_000)
        val attempts = client.attempts.get()
        // backoff 250ms, 500ms, 1s, ... -> 4 attempts in 2s (the old fixed 1s delay gave 2)
        assertTrue(attempts in 3..6, "attempts=$attempts")
        assertTrue(pd.active, "still trying")
        assertFalse(pd.yielding)
        pd.stop()
        pd.join()
    }
}
