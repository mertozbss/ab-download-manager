package ir.amirab.downloader.downloaditem.ytdlp

import java.net.URI
import java.net.URLDecoder

/**
 * Turns any supported YouTube URL into the canonical `https://www.youtube.com/watch?v=<ID>` form.
 * Playlist / timestamp / tracking parameters (list=, t=, si=, ...) are dropped.
 * Returns null for anything that is not a single, valid YouTube video URL.
 */
object YouTubeUrlNormalizer {
    private val videoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    fun isValidVideoId(id: String?): Boolean = id != null && videoIdRegex.matches(id)

    fun normalize(url: String): String? {
        return extractVideoId(url)?.let(::watchUrlOf)
    }

    fun watchUrlOf(videoId: String): String {
        require(isValidVideoId(videoId)) { "invalid video id" }
        return "https://www.youtube.com/watch?v=$videoId"
    }

    fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("-")) return null
        // no whitespace / control characters anywhere
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (!YtDlpSupportedSites.isAllowedHost(host)) return null

        val pathSegments = (uri.rawPath ?: "")
            .split('/')
            .filter { it.isNotEmpty() }
            .map { decode(it) ?: return null }

        val candidate: String? = if (host == "youtu.be") {
            pathSegments.firstOrNull()
        } else {
            when (pathSegments.firstOrNull()) {
                "watch" -> if (pathSegments.size == 1) queryParam(uri.rawQuery, "v") else null
                "shorts", "embed", "live", "v" -> pathSegments.getOrNull(1)
                else -> null
            }
        }
        return candidate?.takeIf(::isValidVideoId)
    }

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrEmpty()) return null
        val values = rawQuery.split('&').mapNotNull { pair ->
            val key = decode(pair.substringBefore('=')) ?: return@mapNotNull null
            if (key != name || !pair.contains('=')) return@mapNotNull null
            decode(pair.substringAfter('='))
        }
        // ambiguous URLs (v= given twice with different values) are rejected
        return values.distinct().singleOrNull()
    }

    private fun decode(s: String): String? {
        return runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()
    }
}
