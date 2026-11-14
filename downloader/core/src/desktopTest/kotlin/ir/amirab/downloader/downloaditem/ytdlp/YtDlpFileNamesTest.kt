package ir.amirab.downloader.downloaditem.ytdlp

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpFileNamesTest {
    private fun s(x: String) = YtDlpFileNames.sanitizeBaseName(x)

    @Test
    fun `path traversal and separators are removed`() {
        listOf(
            "../../Windows/System32/evil",
            "..\\..\\evil",
            "a/../b",
            "....//....//x",
        ).forEach { input ->
            val out = s(input)
            assertFalse(".." in out, "$input -> $out")
            assertFalse('/' in out || '\\' in out, "$input -> $out")
        }
    }

    @Test
    fun `drive letters and reserved characters are removed`() {
        val out = s("C:\\Users\\x\\evil")
        assertFalse(':' in out)
        assertFalse('\\' in out)
        assertEquals("a_b_c_d_e_f_g", s("a<b>c\"d|e?f*g"))
    }

    @Test
    fun `windows reserved names and empty names`() {
        assertEquals("_CON", s("CON"))
        assertEquals("_nul.txt", s("nul.txt"))
        assertEquals("video", s(""))
        assertEquals("video", s("..."))
        assertEquals("video", s("   "))
    }

    @Test
    fun `long names are truncated by utf8 bytes`() {
        val out = s("ğ".repeat(200))
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= YtDlpFileNames.MAX_NAME_BYTES)
    }

    @Test
    fun `template percent is escaped`() {
        assertEquals("50%% off.%(ext)s", YtDlpFileNames.outputTemplateFor("50% off.mp4"))
        assertEquals("%%(title)s.%(ext)s", YtDlpFileNames.outputTemplateFor("%(title)s"))
    }

    @Test
    fun `suggested name looks like yt-dlp default`() {
        assertEquals(
            "youtube-dl test video _'__ä↭𝕐 [BaW_jenozKc].mp4",
            YtDlpFileNames.suggestedFileName("youtube-dl test video \"'/\\ä↭𝕐", "BaW_jenozKc", YtDlpQuality.Best1080),
        )
        assertEquals("song [BaW_jenozKc].mp3", YtDlpFileNames.suggestedFileName("song", "BaW_jenozKc", YtDlpQuality.AudioMp3))
    }

    @Test
    fun `output must stay inside the target folder`() {
        val root = Files.createTempDirectory("ytdlp-test").toFile()
        try {
            val folder = File(root, "downloads").apply { mkdirs() }
            val inside = File(folder, "video.mp4").apply { writeText("x") }
            assertTrue(YtDlpFileNames.isInsideFolder(folder, inside))
            assertTrue(YtDlpFileNames.isInsideFolder(folder, File(folder, "sub/v.mp4")))
            assertFalse(YtDlpFileNames.isInsideFolder(folder, File(folder, "../outside.mp4")))
            assertFalse(YtDlpFileNames.isInsideFolder(folder, File(root, "downloads-evil/v.mp4")))
            assertFalse(YtDlpFileNames.isInsideFolder(folder, File(root, "outside.mp4")))
            assertFalse(YtDlpFileNames.isInsideFolder(folder, folder))
        } finally {
            root.deleteRecursively()
        }
    }
}
