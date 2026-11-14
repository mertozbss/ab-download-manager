package com.abdownloadmanager.integration

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respondText

/**
 * Security guard for the local integration API (mertozbss fork).
 *
 * The API listens on 127.0.0.1 and, by default, has no API key. Without this guard
 * any web page could POST to http://localhost:<port>/add (a "simple" CORS request
 * needs no preflight) and silently queue/start downloads (silentAdd/silentStart).
 *
 * Rules:
 *  1. Host header must be localhost / 127.0.0.1 / [::1]  -> blocks DNS-rebinding.
 *  2. If an Origin header is present it must be a browser-extension origin
 *     (web pages always send Origin on POST/fetch; the extension sends its own).
 *     Requests without Origin (CLI tools, native clients) are allowed.
 */
internal val LocalRequestGuard = createApplicationPlugin(name = "LocalRequestGuard") {
    onCall { call ->
        val host = call.request.headers[HttpHeaders.Host]
        val origin = call.request.headers[HttpHeaders.Origin]
        val reason = LocalRequestRules.rejectReason(host, origin)
        if (reason != null) {
            call.respondText(reason, status = HttpStatusCode.Forbidden)
        }
    }
}

object LocalRequestRules {
    private val allowedHosts = setOf("localhost", "127.0.0.1", "[::1]")
    private val extensionOriginPrefixes = listOf(
        "chrome-extension://",
        "moz-extension://",
        "safari-web-extension://",
        "extension://",
    )

    fun hostWithoutPort(host: String): String {
        val h = host.trim().lowercase()
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            return if (end > 0) h.substring(0, end + 1) else h
        }
        return h.substringBefore(':')
    }

    /** @return null if allowed, otherwise a short reason */
    fun rejectReason(host: String?, origin: String?): String? {
        if (host == null || hostWithoutPort(host) !in allowedHosts) {
            return "Forbidden host"
        }
        if (origin != null) {
            val o = origin.trim().lowercase()
            if (extensionOriginPrefixes.none { o.startsWith(it) }) {
                return "Forbidden origin"
            }
        }
        return null
    }
}
