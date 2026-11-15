package ir.amirab.downloader.downloaditem.ytdlp

import ir.amirab.downloader.downloaditem.ytdlp.tools.YtDlpToolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds

/** runs `yt-dlp -J --no-playlist -- <url>` for the quality window */
class YtDlpInfoFetcher(
    private val toolManager: YtDlpToolManager,
) {
    suspend fun fetch(url: String): YtDlpVideoInfo = withContext(Dispatchers.IO) {
        val tools = toolManager.ensureReady()
        val args = YtDlpArgsBuilder.infoArgs(tools, url)
        val process = YtDlpProcesses.start(args, null)
        coroutineScope {
            // kills the process on timeout or when the caller is cancelled (window closed);
            // that also unblocks the stream readers below
            var timedOut = false
            val watchdog = launch {
                try {
                    delay(120.seconds)
                    timedOut = true
                } finally {
                    if (process.isAlive) YtDlpProcesses.killTree(process)
                }
            }
            val tail = LineTail()
            val stdout = async { readCapped(process.inputStream, MAX_JSON_BYTES) }
            val stderr = async {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { tail.add(it) }
            }
            val out = runCatching { stdout.await() }
            if (out.isFailure && process.isAlive) YtDlpProcesses.killTree(process)
            stderr.await()
            val exit = process.waitFor()
            watchdog.cancel()
            if (timedOut) throw YtDlpException(YtDlpErrorKind.Network, "yt-dlp timed out")
            out.getOrThrow()
            if (exit != 0) throw YtDlpErrorClassifier.classify(tail.snapshot(), exit)
            val info = YtDlpInfoParser.parse(out.getOrThrow().toString(Charsets.UTF_8))
            if (info.isLive) {
                throw YtDlpException(
                    YtDlpErrorKind.Unknown,
                    "Live streams are not supported yet. / Canlı yayın indirme henüz desteklenmiyor.",
                )
            }
            info
        }
    }

    private fun readCapped(input: InputStream, max: Int): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        input.use {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = it.read(buf)
                if (r < 0) break
                if (out.size() + r > max) throw IllegalStateException("yt-dlp output too large")
                out.write(buf, 0, r)
            }
        }
        return out
    }

    companion object {
        private const val MAX_JSON_BYTES = 32 * 1024 * 1024
    }
}
