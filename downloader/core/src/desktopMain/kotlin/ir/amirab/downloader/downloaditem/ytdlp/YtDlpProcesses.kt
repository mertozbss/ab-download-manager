package ir.amirab.downloader.downloaditem.ytdlp

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Starting / stopping yt-dlp processes (desktop only).
 * Always an argument list, never a shell (`cmd /c`) or a single command string.
 */
object YtDlpProcesses {
    fun start(args: List<String>, workDir: File?): Process {
        require(args.isNotEmpty())
        val pb = ProcessBuilder(args)
        workDir?.let { pb.directory(it) }
        val env = pb.environment()
        // make yt-dlp print UTF-8 regardless of the Windows code page (non-ASCII titles)
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        // don't let user-level python env vars change yt-dlp's behaviour
        env.remove("PYTHONPATH")
        env.remove("PYTHONSTARTUP")
        env.remove("PYTHONHOME")
        return pb.start()
    }

    /**
     * Kills yt-dlp and all its children (ffmpeg, deno).
     */
    fun killTree(process: Process, timeoutMillis: Long = 5_000) {
        val root = process.toHandle()
        val descendants = runCatching { root.descendants().toList() }.getOrDefault(emptyList())
        descendants.forEach { runCatching { it.destroy() } }
        runCatching { root.destroy() }
        val deadline = System.currentTimeMillis() + timeoutMillis
        runCatching { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
        (descendants + root).forEach { h ->
            if (h.isAlive) {
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                runCatching { h.onExit().get(left, TimeUnit.MILLISECONDS) }
            }
            if (h.isAlive) runCatching { h.destroyForcibly() }
        }
    }
}

/** keeps the last N output lines for error messages */
class LineTail(private val max: Int = 40) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (line.isBlank()) return
        if (lines.size >= max) lines.removeFirst()
        lines.addLast(line.take(500))
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
