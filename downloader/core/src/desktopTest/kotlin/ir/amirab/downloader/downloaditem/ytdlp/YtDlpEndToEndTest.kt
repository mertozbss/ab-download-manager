package ir.amirab.downloader.downloaditem.ytdlp

import ir.amirab.downloader.downloaditem.ytdlp.tools.OkHttpToolHttpClient
import ir.amirab.downloader.downloaditem.ytdlp.tools.YtDlpTool
import ir.amirab.downloader.downloaditem.ytdlp.tools.YtDlpToolManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real end-to-end check against YouTube (network + ~150 MB of helper downloads).
 * Skipped unless ABDM_E2E=1; ABDM_E2E_DIR chooses the working folder.
 *   ABDM_E2E=1 ABDM_E2E_DIR=/tmp/abdm-e2e ./gradlew :downloader:core:desktopTest --tests '*YtDlpEndToEndTest*'
 */
class YtDlpEndToEndTest {
    private val enabled = System.getenv("ABDM_E2E") == "1"
    private val root = File(System.getenv("ABDM_E2E_DIR") ?: "build/ytdlp-e2e").absoluteFile
    private val url = YouTubeUrlNormalizer.normalize(System.getenv("ABDM_E2E_URL") ?: "")
        ?: "https://www.youtube.com/watch?v=BaW_jenozKc"

    private data class RunResult(
        val exit: Int,
        val file: File?,
        val progress: List<YtDlpProgressLine>,
        val percents: List<Int>,
        val output: List<String>,
    )

    private fun runDownload(
        tools: YtDlpToolPaths,
        quality: YtDlpQuality,
        folder: File,
        temp: File,
        name: String,
        limitRate: Long = 0,
        stopAfterBytes: Long? = null,
        aggregator: YtDlpProgressAggregator = YtDlpProgressAggregator(quality.expectedDownloadPhases(), null),
    ): RunResult {
        folder.mkdirs(); temp.mkdirs()
        val args = YtDlpArgsBuilder.downloadArgs(tools, url, quality, folder, temp, name, limitRate)
        println("ARGS: " + args.joinToString(" "))
        val p = YtDlpProcesses.start(args, temp)
        val tail = LineTail(200)
        val errThread = Thread { p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { tail.add("ERR " + it) } }.apply { start() }
        val progress = mutableListOf<YtDlpProgressLine>()
        val percents = mutableListOf<Int>()
        var file: String? = null
        var killed = false
        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val pr = YtDlpOutputParser.parseProgress(line)
            if (pr != null) {
                progress += pr
                percents += aggregator.onProgress(pr).percent
                if (!killed && stopAfterBytes != null && (pr.downloadedBytes ?: 0) >= stopAfterBytes) {
                    killed = true
                    val children = p.toHandle().descendants().toList()
                    println("PAUSE: killing tree, ${children.size} descendants")
                    YtDlpProcesses.killTree(p)
                    assertTrue(children.none { it.isAlive }, "a child process survived the kill")
                }
                return@forEachLine
            }
            YtDlpOutputParser.parseFilePath(line)?.let { file = it; return@forEachLine }
            tail.add(line)
        }
        p.waitFor(60, TimeUnit.SECONDS)
        errThread.join(5000)
        tail.snapshot().takeLast(15).forEach { println("  | $it") }
        return RunResult(p.exitValue(), file?.let(::File), progress, percents, tail.snapshot())
    }

    @Test
    fun `real youtube download end to end`() {
        if (!enabled) return
        root.mkdirs()
        val manager = YtDlpToolManager(File(root, "tools"), OkHttpToolHttpClient())
        val tools = runBlocking { manager.ensureReady() }
        YtDlpTool.entries.forEach { println("TOOL ${it.id}: ${manager.installed(it)}") }

        // 1) quality window info
        val info = runBlocking { YtDlpInfoFetcher(manager).fetch(url) }
        println("INFO: ${info.title} / ${info.channel} / ${info.duration}s / ${info.formats.size} formats")
        val options = YtDlpInfoParser.qualityOptions(info)
        println("OPTIONS: $options")
        assertEquals(YouTubeUrlNormalizer.extractVideoId(url), info.id)
        assertTrue(options.isNotEmpty())

        // 2) each main quality produces exactly one finished file inside the folder
        for (q in listOf(YtDlpQuality.Best1080, YtDlpQuality.AudioM4a, YtDlpQuality.AudioMp3)) {
            val folder = File(root, "out-${q.name}")
            val temp = File(root, "temp-${q.name}")
            folder.deleteRecursively(); temp.deleteRecursively()
            val name = YtDlpFileNames.suggestedFileName(info.title, info.id, q)
            val r = runDownload(tools, q, folder, temp, name)
            assertEquals(0, r.exit, "yt-dlp failed for $q")
            val f = assertNotNull(r.file, "no file path printed for $q")
            println("RESULT $q: ${f.name} ${f.length()} bytes, ${r.progress.size} progress lines, percents=${r.percents.distinct()}")
            assertTrue(f.isFile && f.length() > 0)
            assertTrue(YtDlpFileNames.isInsideFolder(folder, f))
            assertEquals(r.percents.sorted(), r.percents, "percent went backwards")
            assertTrue(r.progress.isNotEmpty(), "no progress lines parsed for $q")
            val expectedExt = if (q == YtDlpQuality.Best1080) setOf("mp4") else setOf(q.expectedExtension)
            assertTrue(f.extension in expectedExt, "unexpected extension ${f.extension} for $q")
            assertEquals(1, folder.listFiles()!!.size, "extra files in output folder: ${folder.list()!!.toList()}")
            val leftovers = temp.walkTopDown().filter { it.isFile }.toList()
            assertTrue(leftovers.isEmpty(), "temp leftovers: $leftovers")
        }

        // 3) pause (kill tree) + resume continues from the .part file
        val q = YtDlpQuality.Best1080
        val folder = File(root, "out-resume")
        val temp = File(root, "temp-resume")
        folder.deleteRecursively(); temp.deleteRecursively()
        val name = YtDlpFileNames.suggestedFileName(info.title, info.id, q)
        val agg = YtDlpProgressAggregator(q.expectedDownloadPhases(), options.firstOrNull { it.quality == q }?.estimatedSize)
        val first = runDownload(tools, q, folder, temp, name, limitRate = 150_000, stopAfterBytes = 300_000, aggregator = agg)
        assertTrue(first.exit != 0, "first run should have been killed")
        val parts = temp.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()
        println("PARTS after pause: ${parts.map { it.name + "=" + it.length() }}")
        if (parts.isEmpty()) fail("no .part file kept for resume")
        val second = runDownload(tools, q, folder, temp, name, aggregator = agg)
        assertEquals(0, second.exit)
        val firstReported = second.progress.firstOrNull { (it.downloadedBytes ?: 0) > 0 }?.downloadedBytes ?: 0
        println("RESUME: first downloaded value after resume = $firstReported, percents=${(first.percents + second.percents).distinct()}")
        assertTrue(firstReported >= 250_000, "resume restarted from scratch")
        assertEquals((first.percents + second.percents).sorted(), first.percents + second.percents, "percent went backwards across resume")
        assertNotNull(second.file)

        // 4) cancel cleanup only touches the item's temp folder
        val dest = YtDlpDownloadDestination(File(folder, "x.mp4"), temp, root, canDeleteOutputFile = { false })
        dest.cleanUpJunkFiles()
        assertTrue(!temp.exists(), "temp folder not deleted")
        assertTrue(second.file!!.exists(), "finished file must not be touched by cleanup")
    }
}
