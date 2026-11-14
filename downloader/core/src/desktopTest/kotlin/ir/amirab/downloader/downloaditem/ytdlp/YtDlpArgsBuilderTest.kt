package ir.amirab.downloader.downloaditem.ytdlp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpArgsBuilderTest {
    private val tools = YtDlpToolPaths(
        ytDlp = File("C:/abdm/tools/yt-dlp/yt-dlp.exe"),
        ffmpegDir = File("C:/abdm/tools/ffmpeg"),
        deno = File("C:/abdm/tools/deno/deno.exe"),
    )
    private val url = "https://www.youtube.com/watch?v=BaW_jenozKc"

    private fun download(link: String = url, name: String = "Video [BaW_jenozKc].mp4", quality: YtDlpQuality = YtDlpQuality.Best1080) =
        YtDlpArgsBuilder.downloadArgs(
            tools = tools,
            url = link,
            quality = quality,
            folder = File("C:/Downloads"),
            tempFolder = File("C:/abdm/downloadData/ytdlp/5"),
            fileName = name,
        )

    @Test
    fun `download args contain the fixed safe flags and end with -- url`() {
        val args = download()
        assertEquals(tools.ytDlp.absolutePath, args.first())
        YtDlpArgsBuilder.SAFE_FIXED_ARGS.forEach { assertTrue(it in args, "missing $it") }
        assertEquals(listOf("--", url), args.takeLast(2))
        // `--` appears exactly once, right before the URL
        assertEquals(1, args.count { it == "--" })
        assertEquals(args.size - 2, args.indexOf("--"))
    }

    @Test
    fun `info args use -J and put the url after --`() {
        val args = YtDlpArgsBuilder.infoArgs(tools, url)
        assertTrue("-J" in args)
        assertTrue("--no-playlist" in args)
        assertEquals(listOf("--", url), args.takeLast(2))
    }

    @Test
    fun `helpers are passed by absolute path and no remote components`() {
        val args = download()
        val js = args.indexOf("--js-runtimes")
        assertTrue(args.indexOf("--no-js-runtimes") in 0 until js)
        assertEquals("deno:" + tools.deno!!.absolutePath, args[js + 1])
        assertEquals(tools.ffmpegDir!!.absolutePath, args[args.indexOf("--ffmpeg-location") + 1])
        assertTrue("--no-remote-components" in args)
        assertEquals("utf-8", args[args.indexOf("--encoding") + 1])
    }

    @Test
    fun `best quality uses the default format selector`() {
        val args = download()
        assertEquals("bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080]", args[args.indexOf("-f") + 1])
    }

    @Test
    fun `mp3 quality extracts audio with ffmpeg`() {
        val args = download(quality = YtDlpQuality.AudioMp3)
        assertTrue("-x" in args)
        assertEquals("mp3", args[args.indexOf("--audio-format") + 1])
        assertEquals("0", args[args.indexOf("--audio-quality") + 1])
    }

    @Test
    fun `output template uses folder, temp folder and the sanitized name`() {
        val args = download(name = "100% ..\\..\\evil:name.mp4")
        val o = args[args.indexOf("-o") + 1]
        assertEquals("100%% ._._evil_name.%(ext)s", o)
        val pIndexes = args.withIndex().filter { it.value == "-P" }.map { it.index }
        assertEquals(2, pIndexes.size)
        assertTrue(args[pIndexes[1] + 1].startsWith("temp:"))
    }

    @Test
    fun `progress template and filepath print are machine readable`() {
        val args = download()
        assertTrue(args[args.indexOf("--progress-template") + 1].startsWith("download:ABDM|"))
        assertEquals("after_move:ABDMFILE|%(filepath)s", args[args.indexOf("--print") + 1])
        assertTrue("--newline" in args)
    }

    @Test
    fun `parallel fragments follow the connection setting`() {
        val args = YtDlpArgsBuilder.downloadArgs(tools, url, YtDlpQuality.Best1080, File("C:/D"), File("C:/T"), "a.mp4", connections = 32)
        assertEquals("youtube:formats=dashy", args[args.indexOf("--extractor-args") + 1])
        assertEquals("32", args[args.indexOf("--concurrent-fragments") + 1]) // capped
        assertEquals(32, YtDlpArgsBuilder.fragmentsFor(8)) // ABDM default thread count
        assertEquals(8, YtDlpArgsBuilder.fragmentsFor(2))
        assertEquals(1, YtDlpArgsBuilder.fragmentsFor(1))
        assertEquals(1, YtDlpArgsBuilder.fragmentsFor(0))
        val single = YtDlpArgsBuilder.downloadArgs(tools, url, YtDlpQuality.Best1080, File("C:/D"), File("C:/T"), "a.mp4", connections = 1)
        assertFalse("--concurrent-fragments" in single)
        // speed limit would be multiplied by the fragment count -> single connection
        val limited = YtDlpArgsBuilder.downloadArgs(tools, url, YtDlpQuality.Best1080, File("C:/D"), File("C:/T"), "a.mp4", speedLimitBytesPerSecond = 1_000_000, connections = 8)
        assertFalse("--concurrent-fragments" in limited)
        assertEquals("1000000", limited[limited.indexOf("--limit-rate") + 1])
        assertEquals(listOf("--", url), args.takeLast(2))
    }

    @Test
    fun `forbidden options are never produced`() {
        val all = download() + YtDlpArgsBuilder.infoArgs(tools, url) +
                YtDlpQuality.entries.flatMap { download(quality = it) }
        listOf("--exec", "--load-info-json", "--cookies-from-browser", "--config-locations", "--cookies", "-U", "--update")
            .forEach { assertFalse(it in all, "$it must not be used") }
    }

    @Test
    fun `url starting with dash is rejected`() {
        assertFailsWith<YtDlpInvalidUrlException> { download(link = "-https://www.youtube.com/watch?v=BaW_jenozKc") }
        assertFailsWith<YtDlpInvalidUrlException> { download(link = "--exec=calc.exe") }
    }

    @Test
    fun `url containing --exec is rejected`() {
        assertFailsWith<YtDlpInvalidUrlException> { download(link = "https://www.youtube.com/watch?v=BaW_jenozKc --exec calc") }
        assertFailsWith<YtDlpInvalidUrlException> { download(link = "https://www.youtube.com/watch --exec=calc.exe") }
    }

    @Test
    fun `only the normalized url reaches yt-dlp`() {
        val args = download(link = "https://youtu.be/BaW_jenozKc?si=abc&t=10&list=PL123")
        assertEquals(url, args.last())
        assertFalse(args.any { "list=" in it || "si=" in it })
    }

    @Test
    fun `foreign host and non-http schemes are rejected`() {
        listOf(
            "https://evil.example/watch?v=BaW_jenozKc",
            "https://youtube.com.evil.example/watch?v=BaW_jenozKc",
            "https://www.youtube.com@evil.example/watch?v=BaW_jenozKc",
            "file:///C:/Windows/System32/calc.exe",
            "ftp://www.youtube.com/watch?v=BaW_jenozKc",
            "javascript:alert(1)",
            "",
        ).forEach { bad ->
            assertFailsWith<YtDlpInvalidUrlException>(bad) { download(link = bad) }
        }
    }
}
