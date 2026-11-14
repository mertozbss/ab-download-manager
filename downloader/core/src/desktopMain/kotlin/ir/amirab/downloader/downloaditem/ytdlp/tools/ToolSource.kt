package ir.amirab.downloader.downloaditem.ytdlp.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Where helper binaries may come from (mertozbss fork): official GitHub releases only.
 * `release-assets.githubusercontent.com` is where GitHub currently redirects release downloads.
 * `api.github.com` is only used to read release metadata (JSON), never to download binaries.
 */
object ToolSourcePolicy {
    val DOWNLOAD_HOSTS: Set<String> = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )
    val METADATA_HOSTS: Set<String> = setOf("api.github.com")

    /** hard cap so a hostile/broken server can't fill the disk */
    const val MAX_DOWNLOAD_BYTES: Long = 400L * 1024 * 1024
    const val MAX_TEXT_BYTES: Long = 2L * 1024 * 1024

    fun isAllowedDownloadUrl(url: String): Boolean = isAllowed(url, DOWNLOAD_HOSTS)
    fun isAllowedMetadataUrl(url: String): Boolean = isAllowed(url, METADATA_HOSTS)

    fun requireAllowedDownloadUrl(url: String) {
        if (!isAllowedDownloadUrl(url)) throw ToolSourceNotAllowedException(url)
    }

    private fun isAllowed(url: String, hosts: Set<String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return host in hosts
    }
}

class ToolSourceNotAllowedException(url: String) :
    SecurityException("Download source is not allowed: ${url.take(200)}")

interface ToolHttpClient {
    /**
     * Downloads [url] into [destination]. Every redirect hop must pass [ToolSourcePolicy].
     */
    fun download(url: String, destination: File, onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> })

    /** small text resources: checksum files (download hosts) or release JSON (metadata hosts) */
    fun getText(url: String): String
}

class OkHttpToolHttpClient(
    baseClient: OkHttpClient = OkHttpClient(),
) : ToolHttpClient {
    private val client = baseClient.newBuilder()
        // we follow redirects ourselves so every hop is checked against the allow-list
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun open(url: String, allowMetadata: Boolean): okhttp3.Response {
        var current = url
        repeat(10) {
            val allowed = ToolSourcePolicy.isAllowedDownloadUrl(current) ||
                    (allowMetadata && ToolSourcePolicy.isAllowedMetadataUrl(current))
            if (!allowed) throw ToolSourceNotAllowedException(current)
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", "ABDownloadManager-ToolInstaller")
                .apply {
                    if (ToolSourcePolicy.isAllowedMetadataUrl(current)) {
                        header("Accept", "application/vnd.github+json")
                    }
                }
                .build()
            val response = client.newCall(request).execute()
            if (response.isRedirect) {
                val location = response.header("Location")
                response.close()
                location ?: throw IOException("redirect without location")
                current = URI(current).resolve(location).toString()
            } else {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw IOException("HTTP $code for ${current.take(200)}")
                }
                return response
            }
        }
        throw IOException("too many redirects")
    }

    override fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
        open(url, allowMetadata = false).use { response ->
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            if (total != null && total > ToolSourcePolicy.MAX_DOWNLOAD_BYTES) throw IOException("file too large")
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        if (downloaded > ToolSourcePolicy.MAX_DOWNLOAD_BYTES) throw IOException("file too large")
                        output.write(buffer, 0, read)
                        onProgress(downloaded, total)
                    }
                }
            }
        }
    }

    override fun getText(url: String): String {
        open(url, allowMetadata = true).use { response ->
            val body = response.body ?: throw IOException("empty body")
            val bytes = body.byteStream().use { it.readNBytes((ToolSourcePolicy.MAX_TEXT_BYTES + 1).toInt()) }
            if (bytes.size > ToolSourcePolicy.MAX_TEXT_BYTES) throw IOException("response too large")
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
