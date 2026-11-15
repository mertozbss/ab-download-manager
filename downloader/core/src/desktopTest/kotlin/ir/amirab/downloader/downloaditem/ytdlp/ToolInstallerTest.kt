package ir.amirab.downloader.downloaditem.ytdlp

import ir.amirab.downloader.downloaditem.ytdlp.tools.ChecksumParser
import ir.amirab.downloader.downloaditem.ytdlp.tools.ToolHttpClient
import ir.amirab.downloader.downloaditem.ytdlp.tools.ToolRelease
import ir.amirab.downloader.downloaditem.ytdlp.tools.ToolReleases
import ir.amirab.downloader.downloaditem.ytdlp.tools.ToolSourceNotAllowedException
import ir.amirab.downloader.downloaditem.ytdlp.tools.ToolSourcePolicy
import ir.amirab.downloader.downloaditem.ytdlp.tools.ToolVerificationException
import ir.amirab.downloader.downloaditem.ytdlp.tools.YtDlpTool
import ir.amirab.downloader.downloaditem.ytdlp.tools.YtDlpToolManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeHttp(private val files: Map<String, ByteArray>) : ToolHttpClient {
    val requested = mutableListOf<String>()
    override fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
        requested += url
        ToolSourcePolicy.requireAllowedDownloadUrl(url)
        destination.parentFile?.mkdirs()
        destination.writeBytes(files[url] ?: error("404 $url"))
    }

    override fun getText(url: String): String {
        requested += url
        return (files[url] ?: error("404 $url")).toString(Charsets.UTF_8)
    }
}

class ToolInstallerTest {
    private val dir: File = Files.createTempDirectory("abdm-tools").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val exeBytes = "MZ fake yt-dlp".toByteArray()
    private val url = "https://github.com/yt-dlp/yt-dlp/releases/download/2099.01.01/yt-dlp.exe"
    private fun release(expected: String?, checksumUrl: String? = null, assetUrl: String = url) = ToolRelease(
        tool = YtDlpTool.YtDlp,
        version = "2099.01.01",
        assetName = "yt-dlp.exe",
        assetUrl = assetUrl,
        checksumUrl = checksumUrl,
        expectedSha256 = expected,
    )

    @Test
    fun `correct hash is accepted and recorded`() {
        val manager = YtDlpToolManager(dir, FakeHttp(mapOf(url to exeBytes)))
        manager.installLocked(release(sha(exeBytes)))
        val exe = File(dir, "yt-dlp/yt-dlp.exe")
        assertTrue(exe.isFile)
        val info = assertNotNull(manager.installed(YtDlpTool.YtDlp))
        assertEquals("2099.01.01", info.version)
        assertEquals(sha(exeBytes), info.files["yt-dlp/yt-dlp.exe"])
    }

    @Test
    fun `hash from the release checksum file is accepted`() {
        val sums = "${sha("other".toByteArray())}  yt-dlp_linux\n${sha(exeBytes)}  yt-dlp.exe\n"
        val sumsUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2099.01.01/SHA2-256SUMS"
        val manager = YtDlpToolManager(dir, FakeHttp(mapOf(url to exeBytes, sumsUrl to sums.toByteArray())))
        manager.installLocked(release(null, sumsUrl))
        assertTrue(File(dir, "yt-dlp/yt-dlp.exe").isFile)
    }

    @Test
    fun `wrong hash is rejected and the file is deleted`() {
        val manager = YtDlpToolManager(dir, FakeHttp(mapOf(url to exeBytes)))
        assertFailsWith<ToolVerificationException> {
            manager.installLocked(release("0".repeat(64)))
        }
        assertFalse(File(dir, "yt-dlp/yt-dlp.exe").exists())
        assertTrue(File(dir, ".tmp").listFiles().orEmpty().isEmpty(), "download leftovers must be deleted")
        assertNull(manager.installed(YtDlpTool.YtDlp))
    }

    @Test
    fun `tampered binary is never returned as verified`() {
        val manager = YtDlpToolManager(dir, FakeHttp(mapOf(url to exeBytes)))
        manager.installLocked(release(sha(exeBytes)))
        File(dir, "yt-dlp/yt-dlp.exe").writeText("MZ evil")
        val e = assertFailsWith<YtDlpException> { manager.verifiedPaths() }
        // ffmpeg/deno are not installed in this test, so it fails either way; tamper must be detected first
        assertTrue(e.kind == YtDlpErrorKind.ToolVerificationFailed, e.message)
    }

    @Test
    fun `download url outside the allow list is rejected before any request`() {
        val http = FakeHttp(mapOf("https://evil.example/yt-dlp.exe" to exeBytes))
        val manager = YtDlpToolManager(dir, http)
        assertFailsWith<ToolSourceNotAllowedException> {
            manager.installLocked(release(sha(exeBytes), assetUrl = "https://evil.example/yt-dlp.exe"))
        }
        assertTrue(http.requested.isEmpty())
        assertFailsWith<ToolSourceNotAllowedException> {
            manager.installLocked(release(sha(exeBytes), assetUrl = "http://github.com/yt-dlp/yt-dlp/releases/download/x/yt-dlp.exe"))
        }
    }

    @Test
    fun `allow list`() {
        assertTrue(ToolSourcePolicy.isAllowedDownloadUrl("https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.26/yt-dlp.exe"))
        assertTrue(ToolSourcePolicy.isAllowedDownloadUrl("https://objects.githubusercontent.com/x"))
        assertTrue(ToolSourcePolicy.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/x"))
        assertFalse(ToolSourcePolicy.isAllowedDownloadUrl("http://github.com/x"))
        assertFalse(ToolSourcePolicy.isAllowedDownloadUrl("https://github.com.evil.example/x"))
        assertFalse(ToolSourcePolicy.isAllowedDownloadUrl("https://evil.example@github.com/x"))
        assertFalse(ToolSourcePolicy.isAllowedDownloadUrl("https://github.com:8443/x"))
        assertFalse(ToolSourcePolicy.isAllowedDownloadUrl("https://api.github.com/repos/x"))
        assertTrue(ToolSourcePolicy.isAllowedMetadataUrl("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"))
        // every built-in release points to an allowed host
        ToolReleases.defaults().forEach {
            assertTrue(ToolSourcePolicy.isAllowedDownloadUrl(it.assetUrl), it.assetUrl)
            assertTrue(ToolSourcePolicy.isAllowedDownloadUrl(it.checksumUrl!!), it.checksumUrl)
        }
    }

    @Test
    fun `zip assets are verified and only the wanted files are extracted`() {
        val zipBytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("ffmpeg-master/bin/ffmpeg.exe")); z.write("ff".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("ffmpeg-master/bin/ffprobe.exe")); z.write("fp".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("../../evil.exe")); z.write("evil".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val ff = ToolReleases.ffmpeg()
        val sums = "${sha(zipBytes)}  ${ff.assetName}\n"
        val manager = YtDlpToolManager(dir, FakeHttp(mapOf(ff.assetUrl to zipBytes, ff.checksumUrl!! to sums.toByteArray())))
        manager.installLocked(ff)
        assertEquals("ff", File(dir, "ffmpeg/ffmpeg.exe").readText())
        assertEquals("fp", File(dir, "ffmpeg/ffprobe.exe").readText())
        assertFalse(File(dir.parentFile, "evil.exe").exists())
        assertFalse(File(dir, "ffmpeg/evil.exe").exists())
    }

    @Test
    fun `checksum parser`() {
        val h = "a".repeat(64)
        assertEquals(h, ChecksumParser.find("$h  yt-dlp.exe\n${"b".repeat(64)}  yt-dlp", "yt-dlp.exe"))
        assertEquals(h, ChecksumParser.find("$h *yt-dlp.exe", "yt-dlp.exe"))
        // deno publishes PowerShell Get-FileHash output
        val deno = "\nAlgorithm : SHA256\nHash      : ${h.uppercase()}\nPath      : C:\\a\\deno.zip\n"
        assertEquals(h, ChecksumParser.find(deno, "deno-x86_64-pc-windows-msvc.zip"))
        assertNull(ChecksumParser.find("${"b".repeat(64)}  other.exe", "yt-dlp.exe"))
    }
}
