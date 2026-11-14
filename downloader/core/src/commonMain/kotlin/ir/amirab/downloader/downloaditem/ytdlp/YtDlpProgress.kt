package ir.amirab.downloader.downloaditem.ytdlp

/**
 * One machine-readable progress line produced by [YtDlpArgsBuilder.PROGRESS_TEMPLATE]:
 * `ABDM|<format_id>|<downloaded>|<total>|<total_estimate>|<speed>|<eta>` ("NA" = unknown)
 */
data class YtDlpProgressLine(
    val phaseKey: String?,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val totalBytesEstimate: Long?,
    val speed: Double?,
    val eta: Long?,
) {
    val bestTotal: Long? get() = totalBytes ?: totalBytesEstimate
}

object YtDlpOutputParser {
    fun parseProgress(line: String): YtDlpProgressLine? {
        val l = line.trim()
        if (!l.startsWith(YtDlpArgsBuilder.PROGRESS_PREFIX)) return null
        val parts = l.removePrefix(YtDlpArgsBuilder.PROGRESS_PREFIX).split('|')
        if (parts.size != 6) return null
        return YtDlpProgressLine(
            phaseKey = parts[0].takeUnless { isNa(it) },
            downloadedBytes = parts[1].toNumberOrNull()?.toLong(),
            totalBytes = parts[2].toNumberOrNull()?.toLong(),
            totalBytesEstimate = parts[3].toNumberOrNull()?.toLong(),
            speed = parts[4].toNumberOrNull(),
            eta = parts[5].toNumberOrNull()?.toLong(),
        )
    }

    /** final file path printed by `--print after_move:...` */
    fun parseFilePath(line: String): String? {
        val l = line.trimEnd('\r', '\n')
        if (!l.startsWith(YtDlpArgsBuilder.FILE_PREFIX)) return null
        return l.removePrefix(YtDlpArgsBuilder.FILE_PREFIX).trim().takeIf { it.isNotEmpty() && !isNa(it) }
    }

    /** yt-dlp post-processing steps (merging, audio extraction) */
    fun isPostProcessingLine(line: String): Boolean {
        val l = line.trimStart()
        return l.startsWith("[Merger]") ||
                l.startsWith("[ExtractAudio]") ||
                l.startsWith("[VideoConvertor]") ||
                l.startsWith("[VideoRemuxer]") ||
                l.startsWith("[FixupM4a]") ||
                l.startsWith("[FixupM3u8]")
    }

    private fun isNa(s: String) = s.isBlank() || s.equals("NA", ignoreCase = true) || s == "None"

    private fun String.toNumberOrNull(): Double? {
        if (isNa(this)) return null
        return trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }
}

/**
 * Combines the progress of yt-dlp's separate downloads (video + audio) into one value that
 * never goes backwards in the UI.
 *
 * @param expectedPhases how many downloads we expect (2 for video+audio, 1 for audio only)
 * @param expectedTotalBytes size estimate from the quality window (may be null / 0)
 * @param initialDownloaded / [initialPercent] values persisted from a previous run (resume)
 */
class YtDlpProgressAggregator(
    private val expectedPhases: Int,
    private val expectedTotalBytes: Long?,
    initialDownloaded: Long = 0,
    initialPercent: Int = 0,
) {
    data class Snapshot(
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val percent: Int,
        val speed: Double?,
        val eta: Long?,
    )

    private class Phase(var downloaded: Long, var total: Long?, var completed: Boolean = false)

    private val phases = LinkedHashMap<String, Phase>()
    private var currentKey: String? = null
    private var lastDownloaded = initialDownloaded.coerceAtLeast(0)
    private var lastPercent = initialPercent.coerceIn(0, 100)
    private var lastTotal: Long? = expectedTotalBytes?.takeIf { it > 0 }

    var snapshot: Snapshot = Snapshot(lastDownloaded, lastTotal, lastPercent, null, null)
        private set

    fun onProgress(line: YtDlpProgressLine): Snapshot {
        val key = line.phaseKey ?: currentKey ?: "default"
        if (currentKey != null && currentKey != key) {
            phases[currentKey!!]?.let { prev ->
                prev.completed = true
                prev.total?.let { prev.downloaded = maxOf(prev.downloaded, it) }
                if (prev.total == null) prev.total = prev.downloaded
            }
        }
        currentKey = key
        val phase = phases.getOrPut(key) { Phase(0, null) }
        line.downloadedBytes?.let { phase.downloaded = maxOf(phase.downloaded, it) }
        line.bestTotal?.let { phase.total = it }
        phase.total?.let { if (phase.downloaded > it) phase.total = phase.downloaded }

        val sumDownloaded = phases.values.sumOf { it.downloaded }
        val sumKnownTotal = phases.values.sumOf { it.total ?: it.downloaded }
        val phaseCount = maxOf(expectedPhases, phases.size)

        val expected = expectedTotalBytes?.takeIf { it > 0 }
        val percentByPhases = phases.values.sumOf { p ->
            when {
                p.completed -> 1.0
                p.total != null && p.total!! > 0 -> p.downloaded.toDouble() / p.total!!
                else -> 0.0
            }
        } / phaseCount
        val percentByBytes = if (expected != null && expected >= sumKnownTotal && phases.size < phaseCount) {
            sumDownloaded.toDouble() / expected
        } else null

        val computedPercent = ((percentByBytes ?: percentByPhases) * 100).toInt()
        // 100% is only reached when the final file exists (see [onFinished])
        lastPercent = maxOf(lastPercent, computedPercent.coerceIn(0, 99))
        lastDownloaded = maxOf(lastDownloaded, sumDownloaded)

        val total = if (phases.size >= phaseCount) sumKnownTotal else maxOf(expected ?: 0, sumKnownTotal)
        lastTotal = maxOf(total, lastDownloaded).takeIf { it > 0 }

        snapshot = Snapshot(lastDownloaded, lastTotal, lastPercent, line.speed, line.eta)
        return snapshot
    }

    fun onFinished(finalFileSize: Long?): Snapshot {
        val size = finalFileSize?.takeIf { it > 0 }
        lastDownloaded = maxOf(lastDownloaded, size ?: 0)
        lastTotal = size ?: lastTotal
        lastPercent = 100
        snapshot = Snapshot(lastDownloaded, lastTotal, lastPercent, null, 0)
        return snapshot
    }
}
