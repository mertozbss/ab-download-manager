package ir.amirab.downloader.downloaditem.ytdlp.tools

import ir.amirab.downloader.downloaditem.ytdlp.YtDlpErrorKind
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpException
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpToolPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

@Serializable
data class InstalledTool(
    val version: String,
    /** path relative to the tools dir -> sha256 of that file, checked before every run */
    val files: Map<String, String>,
    /** sha256 of the downloaded asset (exe or zip) */
    val sourceSha256: String,
    val installedAt: Long,
)

@Serializable
data class ToolsManifest(
    val tools: Map<String, InstalledTool> = emptyMap(),
)

class ToolVerificationException(message: String) : SecurityException(message)

/**
 * Downloads, verifies (SHA-256) and keeps track of yt-dlp / ffmpeg / deno under `<appData>/tools`.
 *
 * - Nothing is bundled with the installer; components are fetched on first YouTube download.
 * - Sources: official GitHub releases only ([ToolSourcePolicy]).
 * - A binary whose hash does not match is deleted and never executed.
 * - Updates replace the old files atomically; yt-dlp's own `-U` is never used.
 */
class YtDlpToolManager(
    val toolsDir: File,
    private val http: ToolHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Status {
        data object Idle : Status
        data class Working(val message: String, val progress: Float? = null) : Status
        data class Failed(val message: String) : Status
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val manifestFile = File(toolsDir, "manifest.json")

    private val _manifest = MutableStateFlow(readManifest())
    val manifest: StateFlow<ToolsManifest> = _manifest.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    // (path,size,mtime) -> sha256, to avoid re-hashing ffmpeg (~100MB) on every download
    private val hashCache = ConcurrentHashMap<String, String>()

    fun installed(tool: YtDlpTool): InstalledTool? = _manifest.value.tools[tool.id]

    fun isReady(): Boolean = YtDlpTool.entries.all { installed(it) != null }

    /**
     * Installs missing components (pinned defaults) and returns verified paths.
     * @throws YtDlpException on download / verification failure
     */
    suspend fun ensureReady(): YtDlpToolPaths = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                for (release in ToolReleases.defaults()) {
                    if (installed(release.tool) == null || !filesPresent(release.tool)) {
                        installLocked(release)
                    }
                }
                _status.value = Status.Idle
            } catch (e: Exception) {
                _status.value = Status.Failed(e.message ?: e::class.simpleName.orEmpty())
                throw toYtDlpException(e)
            }
        }
        verifiedPaths()
    }

    /**
     * Re-hashes every installed file and compares it with the manifest.
     * @throws YtDlpException(ToolVerificationFailed) if anything does not match
     */
    fun verifiedPaths(): YtDlpToolPaths {
        val m = _manifest.value
        for (tool in YtDlpTool.entries) {
            val info = m.tools[tool.id]
                ?: throw YtDlpException(YtDlpErrorKind.ToolsMissing, "${tool.displayName} is not installed")
            for ((relative, expected) in info.files) {
                val f = resolveInside(relative)
                if (!f.isFile) throw YtDlpException(YtDlpErrorKind.ToolsMissing, "$relative is missing")
                val actual = cachedSha256(f)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw YtDlpException(YtDlpErrorKind.ToolVerificationFailed, "$relative hash mismatch")
                }
            }
        }
        return YtDlpToolPaths(
            ytDlp = File(toolsDir, "yt-dlp/yt-dlp.exe"),
            ffmpegDir = File(toolsDir, "ffmpeg"),
            deno = File(toolsDir, "deno/deno.exe"),
        )
    }

    data class UpdateResult(val updated: List<String>, val upToDate: List<String>)

    /** "Update YouTube components" button: newest stable releases, same verification */
    suspend fun updateAll(): UpdateResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = mutableListOf<String>()
            val upToDate = mutableListOf<String>()
            try {
                _status.value = Status.Working("Checking for updates")
                // yt-dlp
                val ytTag = latestStableTag(ToolReleases.YTDLP_LATEST_API)
                if (!ToolReleases.isValidYtDlpTag(ytTag)) throw IOException("unexpected yt-dlp version: $ytTag")
                if (installed(YtDlpTool.YtDlp)?.version != ytTag || !filesPresent(YtDlpTool.YtDlp)) {
                    installLocked(ToolReleases.ytDlp(ytTag)); updated += "yt-dlp $ytTag"
                } else upToDate += "yt-dlp $ytTag"
                // deno
                val denoTag = latestStableTag(ToolReleases.DENO_LATEST_API)
                if (!ToolReleases.isValidDenoTag(denoTag)) throw IOException("unexpected deno version: $denoTag")
                if (installed(YtDlpTool.Deno)?.version != denoTag || !filesPresent(YtDlpTool.Deno)) {
                    installLocked(ToolReleases.deno(denoTag)); updated += "deno $denoTag"
                } else upToDate += "deno $denoTag"
                // ffmpeg (rolling build: compare the published zip hash)
                val ff = ToolReleases.ffmpeg()
                val remoteZipSha = ChecksumParser.find(http.getText(ff.checksumUrl!!), ff.assetName)
                    ?: throw IOException("ffmpeg checksum not found")
                if (installed(YtDlpTool.Ffmpeg)?.sourceSha256 != remoteZipSha || !filesPresent(YtDlpTool.Ffmpeg)) {
                    val label = ffmpegVersionLabel()
                    installLocked(ff.copy(version = label)); updated += "ffmpeg $label"
                } else upToDate += "ffmpeg"
                _status.value = Status.Idle
            } catch (e: Exception) {
                _status.value = Status.Failed(e.message ?: e::class.simpleName.orEmpty())
                throw toYtDlpException(e)
            }
            UpdateResult(updated, upToDate)
        }
    }

    // ---------------------------------------------------------------------------------------

    /** must be called with [mutex] held */
    internal fun installLocked(release: ToolRelease) {
        ToolSourcePolicy.requireAllowedDownloadUrl(release.assetUrl)
        release.checksumUrl?.let { ToolSourcePolicy.requireAllowedDownloadUrl(it) }
        _status.value = Status.Working("Downloading ${release.tool.displayName} ${release.version}")

        val expected = (release.expectedSha256
            ?: release.checksumUrl?.let { ChecksumParser.find(http.getText(it), release.assetName) })
            ?.lowercase()
            ?: throw ToolVerificationException("no checksum available for ${release.assetName}")

        val tmpDir = File(toolsDir, ".tmp").apply { mkdirs() }
        val downloaded = File(tmpDir, UUID.randomUUID().toString() + ".download")
        try {
            http.download(release.assetUrl, downloaded) { done, total ->
                _status.value = Status.Working(
                    "Downloading ${release.tool.displayName} ${release.version}",
                    total?.let { (done.toFloat() / it).coerceIn(0f, 1f) },
                )
            }
            val actual = sha256(downloaded)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw ToolVerificationException("SHA-256 mismatch for ${release.assetName}")
            }
            val targetDir = File(toolsDir, release.tool.id).apply { mkdirs() }
            val files = if (release.isZip) {
                extractVerifiedZip(downloaded, targetDir, release.extractFiles)
            } else {
                val target = File(targetDir, release.assetName)
                atomicReplace(downloaded, target)
                mapOf(relativeOf(target) to actual)
            }
            val info = InstalledTool(
                version = release.version,
                files = files,
                sourceSha256 = actual,
                installedAt = clock(),
            )
            writeManifest(_manifest.value.copy(tools = _manifest.value.tools + (release.tool.id to info)))
        } finally {
            // a wrong/partial download is never kept
            downloaded.delete()
        }
    }

    private fun extractVerifiedZip(zip: File, targetDir: File, wanted: List<String>): Map<String, String> {
        val staged = mutableMapOf<String, File>()
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // match by last segment only: entry paths are never used to build file paths
                    val leaf = entry.name.replace('\\', '/').substringAfterLast('/')
                    if (leaf !in wanted || leaf in staged) continue
                    val tmp = File(targetDir, ".$leaf.${UUID.randomUUID()}.tmp")
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val r = zin.read(buf)
                            if (r < 0) break
                            total += r
                            if (total > ToolSourcePolicy.MAX_DOWNLOAD_BYTES) throw IOException("zip entry too large")
                            out.write(buf, 0, r)
                        }
                    }
                    staged[leaf] = tmp
                }
            }
            val missing = wanted.filter { it !in staged }
            if (missing.isNotEmpty()) throw IOException("archive does not contain: ${missing.joinToString()}")
            val result = mutableMapOf<String, String>()
            for ((leaf, tmp) in staged) {
                val hash = sha256(tmp)
                val target = File(targetDir, leaf)
                atomicReplace(tmp, target)
                result[relativeOf(target)] = hash
            }
            return result
        } finally {
            staged.values.forEach { it.delete() }
        }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw IOException(
                "Could not replace ${target.name}. Pause running YouTube downloads and try again. (${e.message})",
                e,
            )
        }
        hashCache.keys.removeIf { it.startsWith(target.absolutePath + "|") }
    }

    private fun latestStableTag(apiUrl: String): String {
        val obj = json.parseToJsonElement(http.getText(apiUrl)).jsonObject
        if (obj.bool("prerelease") == true || obj.bool("draft") == true) {
            throw IOException("latest release is a pre-release")
        }
        return obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw IOException("no tag_name")
    }

    private fun ffmpegVersionLabel(): String {
        return runCatching {
            val obj = json.parseToJsonElement(http.getText(ToolReleases.FFMPEG_LATEST_API)).jsonObject
            val date = obj["published_at"]?.jsonPrimitive?.contentOrNull?.take(10)
            if (date != null && Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) "latest ($date)" else "latest"
        }.getOrDefault("latest")
    }

    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull

    private fun filesPresent(tool: YtDlpTool): Boolean {
        val info = installed(tool) ?: return false
        return info.files.keys.all { runCatching { resolveInside(it).isFile }.getOrDefault(false) }
    }

    private fun resolveInside(relative: String): File {
        val f = File(toolsDir, relative).canonicalFile
        val root = toolsDir.canonicalFile
        if (!f.path.startsWith(root.path + File.separator)) {
            throw ToolVerificationException("invalid tool path in manifest")
        }
        return f
    }

    private fun relativeOf(file: File): String {
        return file.canonicalFile.relativeTo(toolsDir.canonicalFile).invariantSeparatorsPath
    }

    private fun cachedSha256(f: File): String {
        val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        return hashCache.getOrPut(key) { sha256(f) }
    }

    private fun readManifest(): ToolsManifest {
        return runCatching {
            if (manifestFile.isFile) json.decodeFromString<ToolsManifest>(manifestFile.readText()) else ToolsManifest()
        }.getOrElse { ToolsManifest() }
    }

    private fun writeManifest(m: ToolsManifest) {
        toolsDir.mkdirs()
        val tmp = File(toolsDir, "manifest.json.tmp")
        tmp.writeText(json.encodeToString(ToolsManifest.serializer(), m))
        atomicReplace(tmp, manifestFile)
        _manifest.update { m }
    }

    private fun toYtDlpException(e: Exception): YtDlpException {
        return when (e) {
            is YtDlpException -> e
            is ToolVerificationException, is ToolSourceNotAllowedException ->
                YtDlpException(YtDlpErrorKind.ToolVerificationFailed, e.message, e)

            else -> YtDlpException(YtDlpErrorKind.ToolsMissing, e.message, e)
        }
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    md.update(buf, 0, r)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
