package com.abdownloadmanager.integration

import com.abdownloadmanager.integration.model.AddDownloadsFromIntegration
import com.abdownloadmanager.integration.model.ApiQueueModel
import com.abdownloadmanager.integration.model.NewDownloadTask
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

private class RecordingHandler : IntegrationHandler {
    val added = mutableListOf<AddDownloadsFromIntegration>()
    override suspend fun addDownloadByGui(request: AddDownloadsFromIntegration) {
        added += request
    }
    override fun listQueues(): List<ApiQueueModel> = emptyList()
    override suspend fun addDownload(task: NewDownloadTask): Long = 0
}

/** The exact body an attacking web page would send (silent add + silent start). */
private const val MALICIOUS_BODY =
    """{"items":[{"type":"http","link":"https://evil.example/payload.exe"}],"options":{"silentAdd":true,"silentStart":true}}"""

class LocalRequestGuardTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun runApp(block: suspend ApplicationTestBuilder.(RecordingHandler) -> Unit) {
        val handler = RecordingHandler()
        testApplication {
            application {
                // default app settings: API key NOT enforced
                setupRouting(json, handler, IntegrationSettings(port = 15151, apiKeyEnforced = false, apiKey = "k"))
            }
            block(handler)
        }
    }

    private suspend fun ApplicationTestBuilder.postAdd(host: String, origin: String?) =
        client.post("/add") {
            header(HttpHeaders.Host, host)
            if (origin != null) header(HttpHeaders.Origin, origin)
            contentType(ContentType.Text.Plain) // CORS "simple request": no preflight
            setBody(MALICIOUS_BODY)
        }

    @Test
    fun `web page origin is rejected and nothing is downloaded`() = runApp { handler ->
        val r = postAdd("localhost:15151", "https://evil.example")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, handler.added.size)
    }

    @Test
    fun `null origin (sandboxed iframe or file page) is rejected`() = runApp { handler ->
        val r = postAdd("127.0.0.1:15151", "null")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, handler.added.size)
    }

    @Test
    fun `dns rebinding host is rejected`() = runApp { handler ->
        val r = postAdd("attacker.example:15151", null)
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, handler.added.size)
    }

    @Test
    fun `chrome extension origin is accepted`() = runApp { handler ->
        val r = postAdd("localhost:15151", "chrome-extension://bbobopahenonfdgjgaleledndnnfhooj")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(1, handler.added.size)
    }

    @Test
    fun `firefox extension origin is accepted`() = runApp { handler ->
        val r = postAdd("127.0.0.1:15151", "moz-extension://0d1e2f3a-aaaa-bbbb-cccc-1234567890ab")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(1, handler.added.size)
    }

    @Test
    fun `native client without origin is accepted`() = runApp { handler ->
        val r = postAdd("localhost:15151", null)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(1, handler.added.size)
    }

    @Test
    fun `rules unit checks`() {
        assertNull(LocalRequestRules.rejectReason("[::1]:15151", null))
        assertNotNull(LocalRequestRules.rejectReason("localhost.evil.example:15151", null))
        assertNotNull(LocalRequestRules.rejectReason(null, null))
        assertNotNull(LocalRequestRules.rejectReason("localhost:15151", "http://localhost:3000"))
    }
}
