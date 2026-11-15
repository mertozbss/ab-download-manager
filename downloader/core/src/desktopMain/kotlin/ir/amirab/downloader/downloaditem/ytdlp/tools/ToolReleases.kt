package ir.amirab.downloader.downloaditem.ytdlp.tools

enum class YtDlpTool(val id: String, val displayName: String) {
    YtDlp("yt-dlp", "yt-dlp"),
    Ffmpeg("ffmpeg", "ffmpeg"),
    Deno("deno", "Deno"),
}

/**
 * A downloadable release asset.
 * @param expectedSha256 pinned hash; if null the hash is read from [checksumUrl] (same release)
 * @param extractFiles for zip assets: file names to extract (matched by last path segment)
 */
data class ToolRelease(
    val tool: YtDlpTool,
    val version: String,
    val assetName: String,
    val assetUrl: String,
    val checksumUrl: String?,
    val expectedSha256: String?,
    val extractFiles: List<String> = emptyList(),
) {
    val isZip: Boolean get() = extractFiles.isNotEmpty()
}

/**
 * Default (pinned) versions of the YouTube helpers.
 *
 * yt-dlp's official Windows exe bundles yt-dlp-ejs, and since late 2025 YouTube needs an external
 * JS runtime for the "n challenge"; deno is yt-dlp's recommended runtime
 * (see yt-dlp README "Dependencies" / wiki "EJS").
 */
object ToolReleases {
    const val DEFAULT_YTDLP_VERSION = "2026.10.26"
    const val DEFAULT_YTDLP_SHA256 = "f5389d3698ecde4ef06f4e6b1ce0294fe9ac1396228c8990b8d3416d6d177a7d"

    const val DEFAULT_DENO_VERSION = "v2.9.8"
    const val DEFAULT_DENO_ZIP_SHA256 = "cba84b26e0df5ae7f74fc66b86a95b866872a86292ebd84fa1e959927edd93bf"

    /**
     * yt-dlp/FFmpeg-Builds only keeps rolling builds (dated tags are deleted after a while),
     * so ffmpeg is taken from the "latest" release and verified with that release's checksums.sha256.
     */
    const val FFMPEG_TAG = "latest"
    const val FFMPEG_ASSET = "ffmpeg-master-latest-win64-gpl.zip"

    private val ytDlpTagRegex = Regex("^\\d{4}\\.\\d{2}\\.\\d{2}(\\.\\d+)?$")
    private val denoTagRegex = Regex("^v\\d+\\.\\d+\\.\\d+$")

    fun isValidYtDlpTag(tag: String) = ytDlpTagRegex.matches(tag)
    fun isValidDenoTag(tag: String) = denoTagRegex.matches(tag)

    fun ytDlp(version: String = DEFAULT_YTDLP_VERSION, pinnedSha256: String? = null): ToolRelease {
        require(isValidYtDlpTag(version)) { "invalid yt-dlp version" }
        val base = "https://github.com/yt-dlp/yt-dlp/releases/download/$version"
        return ToolRelease(
            tool = YtDlpTool.YtDlp,
            version = version,
            assetName = "yt-dlp.exe",
            assetUrl = "$base/yt-dlp.exe",
            checksumUrl = "$base/SHA2-256SUMS",
            expectedSha256 = pinnedSha256 ?: if (version == DEFAULT_YTDLP_VERSION) DEFAULT_YTDLP_SHA256 else null,
        )
    }

    fun deno(version: String = DEFAULT_DENO_VERSION, pinnedSha256: String? = null): ToolRelease {
        require(isValidDenoTag(version)) { "invalid deno version" }
        val asset = "deno-x86_64-pc-windows-msvc.zip"
        val base = "https://github.com/denoland/deno/releases/download/$version"
        return ToolRelease(
            tool = YtDlpTool.Deno,
            version = version,
            assetName = asset,
            assetUrl = "$base/$asset",
            checksumUrl = "$base/$asset.sha256sum",
            expectedSha256 = pinnedSha256 ?: if (version == DEFAULT_DENO_VERSION) DEFAULT_DENO_ZIP_SHA256 else null,
            extractFiles = listOf("deno.exe"),
        )
    }

    fun ffmpeg(versionLabel: String = FFMPEG_TAG): ToolRelease {
        val base = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/$FFMPEG_TAG"
        return ToolRelease(
            tool = YtDlpTool.Ffmpeg,
            version = versionLabel,
            assetName = FFMPEG_ASSET,
            assetUrl = "$base/$FFMPEG_ASSET",
            checksumUrl = "$base/checksums.sha256",
            expectedSha256 = null,
            extractFiles = listOf("ffmpeg.exe", "ffprobe.exe"),
        )
    }

    fun defaults(): List<ToolRelease> = listOf(ytDlp(), ffmpeg(), deno())

    const val YTDLP_LATEST_API = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    const val DENO_LATEST_API = "https://api.github.com/repos/denoland/deno/releases/latest"
    const val FFMPEG_LATEST_API = "https://api.github.com/repos/yt-dlp/FFmpeg-Builds/releases/tags/$FFMPEG_TAG"
}

object ChecksumParser {
    private val hex64 = Regex("\\b[A-Fa-f0-9]{64}\\b")
    private val sumLine = Regex("^([A-Fa-f0-9]{64})\\s+\\*?(.+)$")

    /**
     * Supports `sha256sum` style ("<hash>  <name>") files with many entries, and single-hash files
     * (e.g. deno's PowerShell Get-FileHash output).
     */
    fun find(text: String, assetName: String): String? {
        for (raw in text.lineSequence()) {
            val m = sumLine.matchEntire(raw.trim()) ?: continue
            val name = m.groupValues[2].trim().substringAfterLast('/')
            if (name == assetName) return m.groupValues[1].lowercase()
        }
        val all = hex64.findAll(text).map { it.value.lowercase() }.distinct().toList()
        // a lone hash is only accepted if the file has no "<hash> <name>" entries for other files
        val hasNamedEntries = text.lineSequence().any { sumLine.matches(it.trim()) }
        return if (all.size == 1 && !hasNamedEntries) all.single() else null
    }
}
