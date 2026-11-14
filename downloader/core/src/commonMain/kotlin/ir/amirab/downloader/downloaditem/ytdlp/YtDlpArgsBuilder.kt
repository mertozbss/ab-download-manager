package ir.amirab.downloader.downloaditem.ytdlp

import java.io.File

class YtDlpInvalidUrlException(reason: String) : IllegalArgumentException("Invalid YouTube URL: $reason")

/** absolute paths of the verified helper binaries */
data class YtDlpToolPaths(
    val ytDlp: File,
    val ffmpegDir: File?,
    val deno: File?,
)

/**
 * Builds yt-dlp argument lists (mertozbss fork).
 *
 * Security rules:
 * - always an argument LIST for ProcessBuilder, never a shell string
 * - fixed safe flags ([SAFE_FIXED_ARGS]) on every call; no config files / plugins / --exec
 * - the URL is validated against the host allow-list and is always placed after `--`
 * - helper binaries are passed by absolute path, PATH is never trusted
 */
object YtDlpArgsBuilder {
    val SAFE_FIXED_ARGS: List<String> = listOf(
        "--ignore-config",
        "--no-plugin-dirs",
        "--no-exec",
        "--no-playlist",
        "--windows-filenames",
        "--no-mtime",
    )

    /** options that must never appear in anything we build */
    val FORBIDDEN_OPTIONS: Set<String> = setOf(
        "--exec",
        "--exec-before-download",
        "--load-info-json",
        "--cookies",
        "--cookies-from-browser",
        "--config-locations",
        "--config-location",
        "--plugin-dirs",
        "--batch-file",
        "-a",
        "--update",
        "-U",
        "--update-to",
    )

    const val DEFAULT_CONNECTIONS = 8
    const val MAX_CONNECTIONS = 16

    const val PROGRESS_PREFIX = "ABDM|"
    const val FILE_PREFIX = "ABDMFILE|"

    val PROGRESS_TEMPLATE =
        "download:" + PROGRESS_PREFIX +
                "%(info.format_id)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
                "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s"

    val FILEPATH_PRINT_TEMPLATE = "after_move:$FILE_PREFIX%(filepath)s"

    /**
     * @return the canonical watch URL, throws [YtDlpInvalidUrlException] otherwise
     */
    fun validateUrl(url: String): String {
        val t = url.trim()
        if (t.startsWith("-")) throw YtDlpInvalidUrlException("must not start with '-'")
        val lower = t.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw YtDlpInvalidUrlException("only http(s) is allowed")
        }
        return YouTubeUrlNormalizer.normalize(t)
            ?: throw YtDlpInvalidUrlException("host is not allowed or video id is invalid")
    }

    private fun commonArgs(tools: YtDlpToolPaths): List<String> = buildList {
        add(tools.ytDlp.absolutePath)
        addAll(SAFE_FIXED_ARGS)
        add("--no-remote-components")
        // use only the JS runtime we downloaded & verified (YouTube "n challenge")
        add("--no-js-runtimes")
        tools.deno?.let {
            add("--js-runtimes")
            add("deno:" + it.absolutePath)
        }
        tools.ffmpegDir?.let {
            add("--ffmpeg-location")
            add(it.absolutePath)
        }
        add("--no-color")
        // the Windows exe ignores PYTHONIOENCODING and prints in the ANSI code page otherwise,
        // which would corrupt non-ASCII file paths reported by --print
        add("--encoding")
        add("utf-8")
    }

    /** `yt-dlp -J` : metadata + formats for the quality window */
    fun infoArgs(tools: YtDlpToolPaths, url: String): List<String> {
        val safeUrl = validateUrl(url)
        return finish(
            commonArgs(tools) + listOf("-J", "--no-warnings"),
            safeUrl,
        )
    }

    fun downloadArgs(
        tools: YtDlpToolPaths,
        url: String,
        quality: YtDlpQuality,
        folder: File,
        tempFolder: File,
        fileName: String,
        speedLimitBytesPerSecond: Long = 0,
        connections: Int = DEFAULT_CONNECTIONS,
    ): List<String> {
        val safeUrl = validateUrl(url)
        val args = buildList {
            addAll(commonArgs(tools))
            addAll(quality.formatArgs())
            add("-P"); add(folder.absoluteFile.path)
            // partial / fragment files go to a per-download temp folder (easy, safe cleanup)
            add("-P"); add("temp:" + tempFolder.absoluteFile.path)
            add("-o"); add(YtDlpFileNames.outputTemplateFor(fileName))
            add("--continue")
            add("--newline")
            add("--progress")
            add("--progress-template"); add(PROGRESS_TEMPLATE)
            add("--print"); add(FILEPATH_PRINT_TEMPLATE)
            // one HTTP connection per stream is slow; let yt-dlp fetch YouTube's streams as DASH
            // fragments and download several of them in parallel (~1.8x faster in tests)
            // --limit-rate applies per connection in fragment mode, so a speed limit means 1 connection
            val n = if (speedLimitBytesPerSecond > 0) 1 else connections.coerceIn(1, MAX_CONNECTIONS)
            if (n > 1) {
                add("--extractor-args"); add("youtube:formats=dashy")
                add("--concurrent-fragments"); add(n.toString())
            }
            if (speedLimitBytesPerSecond > 0) {
                add("--limit-rate"); add(speedLimitBytesPerSecond.toString())
            }
        }
        return finish(args, safeUrl)
    }

    private fun finish(options: List<String>, safeUrl: String): List<String> {
        // defense in depth: nothing forbidden may sneak into the option part
        options.forEach { arg ->
            val name = arg.substringBefore('=')
            require(name !in FORBIDDEN_OPTIONS) { "forbidden yt-dlp option: $name" }
        }
        return options + listOf("--", safeUrl)
    }
}
