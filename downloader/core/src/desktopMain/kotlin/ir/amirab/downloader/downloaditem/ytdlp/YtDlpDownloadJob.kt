package ir.amirab.downloader.downloaditem.ytdlp

import ir.amirab.downloader.DownloadManager
import ir.amirab.downloader.destination.DownloadDestination
import ir.amirab.downloader.downloaditem.DownloadJob
import ir.amirab.downloader.downloaditem.DownloadJobExtraConfig
import ir.amirab.downloader.downloaditem.DownloadJobStatus
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.IDownloadItem
import ir.amirab.downloader.downloaditem.ytdlp.tools.YtDlpToolManager
import ir.amirab.downloader.utils.ExceptionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs one yt-dlp process per download (desktop only).
 *
 * - pause: kills the whole process tree (yt-dlp + ffmpeg + deno)
 * - resume: same arguments again; yt-dlp continues from the `.part` files in the temp folder
 * - cancel/delete: kills the tree and deletes only this item's temp folder
 */
class YtDlpDownloadJob(
    override val downloadItem: YtDlpDownloadItem,
    downloadManager: DownloadManager,
    private val toolManager: YtDlpToolManager,
) : DownloadJob(downloadManager) {
    private val listDb by downloadManager::dlListDb
    private lateinit var destination: YtDlpDownloadDestination

    private val tempRoot: File get() = downloadManager.downloadDataFolder.resolve("ytdlp")
    private val tempDir: File get() = tempRoot.resolve(id.toString())

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopRequested = false
    private var aggregator: YtDlpProgressAggregator? = null
    // output of the current run only (a fresh one per run, so old errors never leak into a new run)
    @Volatile
    private var tail = LineTail()

    // YouTube sometimes answers the parallel DASH-fragment requests with 403; then we fall back to
    // the slower single-connection mode for the rest of this job's life
    private var singleConnectionFallback = false
    private var lastRunUsedFragments = false

    private var failedTries = 0
    private var bytesBeforeRetry = 0L
    private var retryJob: Job? = null
    private val delayForEachRetry = 3_000L

    override fun getDestination(): DownloadDestination = destination

    override suspend fun actualBoot() {
        initializeDestination()
    }

    override fun initializeDestination() {
        destination = YtDlpDownloadDestination(
            outputFile = downloadManager.calculateOutputFile(downloadItem),
            tempDir = tempDir,
            tempRoot = tempRoot,
            canDeleteOutputFile = { downloadItem.status == DownloadStatus.Completed },
        )
    }

    override suspend fun resume() {
        if (isDownloadActive.value) return
        _isDownloadActive.update { true }
        failedTries = 0
        start(newActiveScope(), isFirstAttempt = true)
    }

    private fun newActiveScope(): CoroutineScope {
        return newScopeBasedOn(scope).also { activeDownloadScope = it }
    }

    private suspend fun start(activeScope: CoroutineScope, isFirstAttempt: Boolean) {
        activeScope.launch {
            boot()
            if (downloadItem.status == DownloadStatus.Completed && destination.outputFile.exists()) {
                onDownloadFinished()
                return@launch
            }
            onDownloadResuming(isFirstAttempt)
            try {
                // installs missing components on first use and re-verifies every hash
                val tools = toolManager.ensureReady()
                val folder = File(downloadItem.folder).absoluteFile
                DownloadDestination.prepareDestinationFolder(destination.outputFile)
                tempDir.mkdirs()
                val args = YtDlpArgsBuilder.downloadArgs(
                    tools = tools,
                    url = downloadItem.link,
                    quality = downloadItem.quality,
                    folder = folder,
                    tempFolder = tempDir,
                    fileName = downloadItem.name,
                    speedLimitBytesPerSecond = downloadItem.speedLimit,
                    connections = connectionsForThisRun(),
                )
                lastRunUsedFragments = "--concurrent-fragments" in args
                tail = LineTail()
                stopRequested = false
                aggregator = YtDlpProgressAggregator(
                    expectedPhases = downloadItem.quality.expectedDownloadPhases(),
                    expectedTotalBytes = downloadItem.estimatedSize,
                    initialDownloaded = downloadItem.downloadedBytes,
                    initialPercent = downloadItem.progressPercent,
                )
                bytesBeforeRetry = downloadItem.downloadedBytes
                val p = withContext(Dispatchers.IO) { YtDlpProcesses.start(args, tempDir) }
                process = p
                downloadItem.status = DownloadStatus.Downloading
                if (downloadItem.startTime == null) {
                    downloadItem.startTime = System.currentTimeMillis()
                }
                saveState()
                onDownloadResumed()
                startAutoSaver()
                launch(Dispatchers.IO) { readStderr(p) }
                launch(Dispatchers.IO) { watchProcess(p) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val toReport = if (e is IllegalArgumentException && e !is YtDlpException) {
                    YtDlpException(YtDlpErrorKind.InvalidUrl, e.message, e)
                } else e
                // leave the active scope so pause() doesn't cancel itself
                scope.launch { failed(toReport, isFirstAttempt) }
            }
        }.join()
    }

    private fun connectionsForThisRun(): Int {
        if (singleConnectionFallback) return 1
        return downloadItem.preferredConnectionCount ?: downloadManager.settings.defaultThreadCount
    }

    private fun readStderr(p: Process) {
        runCatching {
            p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { tail.add(it) }
        }
    }

    private suspend fun watchProcess(p: Process) {
        var filePath: String? = null
        runCatching {
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val progress = YtDlpOutputParser.parseProgress(line)
                if (progress != null) {
                    onProgress(progress)
                    return@forEachLine
                }
                val path = YtDlpOutputParser.parseFilePath(line)
                if (path != null) {
                    filePath = path
                    return@forEachLine
                }
                if (YtDlpOutputParser.isPostProcessingLine(line)) {
                    _status.value = DownloadJobStatus.PreparingFile(null)
                }
                tail.add(line)
            }
        }
        val exit = runCatching { p.waitFor() }.getOrNull()
        if (stopRequested) return // pause() takes care of the state
        val reported = filePath
        scope.launch {
            process = null
            if (exit == 0) {
                val file = reported?.let(::File) ?: findOutputFallback()
                if (file == null) {
                    failed(YtDlpException(YtDlpErrorKind.Unknown, "yt-dlp did not report an output file"), false)
                } else {
                    completed(file)
                }
            } else {
                failed(YtDlpErrorClassifier.classify(tail.snapshot(), exit), false)
            }
        }
    }

    private fun onProgress(line: YtDlpProgressLine) {
        val agg = aggregator ?: return
        val s = agg.onProgress(line)
        downloadItem.downloadedBytes = s.downloadedBytes
        downloadItem.progressPercent = s.percent
        downloadItem.contentLength = s.totalBytes ?: IDownloadItem.LENGTH_UNKNOWN
        if (_status.value !is DownloadJobStatus.Downloading) {
            _status.value = DownloadJobStatus.Downloading
        }
        if (s.downloadedBytes > bytesBeforeRetry) {
            failedTries = 0
        }
    }

    /** if `--print after_move` wasn't seen (e.g. the file already existed) */
    private fun findOutputFallback(): File? {
        val folder = File(downloadItem.folder)
        val base = YtDlpFileNames.sanitizeBaseName(YtDlpFileNames.stripKnownExtension(downloadItem.name))
        return listOf("mp4", "mkv", "webm", "m4a", "mp3", "opus")
            .map { File(folder, "$base.$it") }
            .singleOrNull { it.isFile }
    }

    private suspend fun completed(reportedFile: File) {
        val folder = File(downloadItem.folder)
        val file = reportedFile.absoluteFile
        if (!YtDlpFileNames.isInsideFolder(folder, file) || !file.isFile) {
            pause(YtDlpException(YtDlpErrorKind.UnsafeOutputPath, file.path.take(300)))
            return
        }
        cancelActiveScope()
        val canonical = file.canonicalFile
        downloadItem.name = canonical.name
        initializeDestination()
        aggregator?.onFinished(canonical.length())
        downloadItem.downloadedBytes = canonical.length()
        downloadItem.progressPercent = 100
        onDownloadFinished()
    }

    override fun onDownloadFinishedBeforeSave() {
        downloadItem.contentLength = destination.outputFile.length()
    }

    private suspend fun failed(e: Throwable, isFirstAttempt: Boolean) {
        if (!stopRequested && shouldFallBackToSingleConnection(e)) {
            singleConnectionFallback = true
            // fragment files can't be continued in the other mode; start this item over (UI progress stays clamped)
            withContext(Dispatchers.IO) { destination.deleteTempDir() }
            retry(isFirstAttempt)
            return
        }
        val retryable = e is YtDlpException && !e.isCritical()
        if (retryable && !stopRequested) {
            if (downloadItem.downloadedBytes > bytesBeforeRetry) failedTries = 0 else failedTries++
            bytesBeforeRetry = downloadItem.downloadedBytes
            val maxRetries = downloadManager.settings.maxDownloadRetryCount
            if (failedTries <= maxRetries) {
                retry(isFirstAttempt)
                return
            }
        }
        pause(e)
    }

    private fun shouldFallBackToSingleConnection(e: Throwable): Boolean {
        return YtDlpFallbackPolicy.shouldFallBackToSingleConnection(
            error = e,
            usedFragments = lastRunUsedFragments,
            alreadyFellBack = singleConnectionFallback,
            outputTail = tail.snapshot(),
        )
    }

    private fun retry(isFirstAttempt: Boolean) {
        retryJob?.cancel()
        retryJob = scope.launch {
            cancelActiveScope()
            _status.update { DownloadJobStatus.Retrying(delayForEachRetry) }
            delay(delayForEachRetry.milliseconds)
            if (stopRequested) return@launch
            start(newActiveScope(), isFirstAttempt)
        }
    }

    private suspend fun cancelActiveScope() {
        val s = activeDownloadScope
        activeDownloadScope = null
        s?.coroutineContext?.job?.cancelAndJoin()
    }

    private suspend fun stopProcess() {
        stopRequested = true
        val p = process
        process = null
        if (p != null) {
            withContext(Dispatchers.IO) { YtDlpProcesses.killTree(p) }
        }
    }

    override suspend fun pause(throwable: Throwable) {
        boot()
        retryJob?.cancel()
        retryJob = null
        stopProcess()
        cancelActiveScope()
        if (!ExceptionUtils.isNormalCancellation(throwable)) {
            throwable.printStackTrace()
        }
        onDownloadCanceled(throwable)
    }

    override suspend fun reset() {
        pause()
        withContext(Dispatchers.IO) { destination.deleteTempDir() }
        downloadItem.downloadedBytes = 0
        downloadItem.progressPercent = 0
        downloadItem.contentLength = downloadItem.estimatedSize ?: IDownloadItem.LENGTH_UNKNOWN
        downloadItem.status = DownloadStatus.Added
        downloadItem.startTime = null
        downloadItem.completeTime = null
        saveState()
        downloadManager.onDownloadItemChange(downloadItem)
    }

    override fun getDownloadedSize(): Long = downloadItem.downloadedBytes

    override fun reloadSettings() {}

    override suspend fun changeConfig(
        updater: (IDownloadItem) -> Unit,
        extraConfig: DownloadJobExtraConfig?
    ): IDownloadItem {
        boot()
        val previous = downloadItem.copy()
        val updated = previous.copy().apply(updater)
        val previousFile = downloadManager.calculateOutputFile(previous)
        val newFile = downloadManager.calculateOutputFile(updated)
        val wasActive = isDownloadActive.value
        val needsRestart = previous.link != updated.link ||
                previous.quality != updated.quality ||
                previous.speedLimit != updated.speedLimit ||
                previousFile != newFile
        if (wasActive && needsRestart) {
            pause()
        }
        if (previousFile != newFile && previous.status == DownloadStatus.Completed) {
            destination.moveOutput(newFile)
        }
        downloadItem.applyFrom(updated)
        initializeDestination()
        saveState()
        if (wasActive && needsRestart) {
            scope.launch { resume() }
        }
        return downloadItem
    }

    override suspend fun extraConfigsReceived(config: DownloadJobExtraConfig) {}

    private val itemSaveLock = Mutex()
    private var lastSaved: YtDlpDownloadItem? = null

    override suspend fun saveState() {
        itemSaveLock.withLock {
            val copy = downloadItem.copy()
            if (lastSaved != copy) {
                listDb.update(copy)
                lastSaved = copy
            }
        }
    }
}
