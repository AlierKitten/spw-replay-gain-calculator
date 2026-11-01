package com.alierkitten.spw.replaygain.index

import com.alierkitten.spw.replaygain.config.PluginConfig
import com.alierkitten.spw.replaygain.replaygain.ReplayGainEngine
import com.alierkitten.spw.replaygain.replaygain.ReplayGainTags
import com.alierkitten.spw.replaygain.replaygain.TagWriter
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates the index pipeline: read tracks -> filter unindexed -> measure ->
 * write tags -> persist.
 *
 * Measurement is CPU-bound (FFmpeg decodes + the ebur128 filter), so tracks are
 * measured concurrently on a fixed pool sized by [PluginConfig.parallelism].
 */
class IndexService(
    private val store: IndexStore,
    private val engine: ReplayGainEngine,
    private val tagWriter: TagWriter,
    private val config: PluginConfig,
    private val ffmpegLocator: () -> String?
) {
    /** Single thread that owns the `busy` flag and serialises index runs. */
    private val controller: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spw-replaygain-indexer").apply { isDaemon = true }
    }

    /** Pool that actually runs concurrent track measurements / tag cleanups. */
    private val workers: ExecutorService = Executors.newFixedThreadPool(config.parallelism) { runnable ->
        Thread(runnable, "spw-replaygain-worker-${workerCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    private val workerCounter = AtomicInteger(0)

    @Volatile
    private var busy = false

    fun indexAll() {
        submit { runIndexAll(force = false) }
    }

    fun reindexAll() {
        submit { runIndexAll(force = true) }
    }

    fun indexCurrent() {
        submit { runIndexCurrent() }
    }

    fun shutdown() {
        // Interrupt the controller (blocked in invokeAll) and every worker so any
        // in-flight FFmpeg measurement is aborted and the SQLite connection is no
        // longer held. Wait for the pools to actually drain before returning so the
        // caller can safely close the store without racing an alive worker.
        controller.shutdownNow()
        workers.shutdownNow()
        runCatching {
            controller.awaitTermination(15, TimeUnit.SECONDS)
            workers.awaitTermination(15, TimeUnit.SECONDS)
        }
    }

    private fun submit(task: () -> Unit) {
        if (busy) {
            toast("ReplayGain 索引任务正在进行中", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        busy = true
        controller.submit {
            try {
                task()
            } catch (error: Throwable) {
                log("索引任务失败: ${error.message}", error)
                toast("ReplayGain 索引失败: ${error.message}", WorkshopApi.Ui.ToastType.Error)
            } finally {
                busy = false
            }
        }
    }

    private fun runIndexAll(force: Boolean) {
        if (!ensureReady()) return

        toast("ReplayGain 开始索引…", WorkshopApi.Ui.ToastType.Success)

        val reference = config.referenceLoudness
        val writeTags = config.writeTags
        val tracks = fetchAllTracks()
        if (tracks.isEmpty()) {
            toast("曲库为空，无法索引", WorkshopApi.Ui.ToastType.Warning)
            return
        }

        // Skip already-indexed tracks up front; the remaining work is what we fan
        // out across the worker pool.
        val pending = if (force) {
            tracks
        } else {
            tracks.filterNot { store.isIndexed(trackKey(it)) }
        }
        val skippedInitial = tracks.size - pending.size

        if (pending.isEmpty()) {
            toast("ReplayGain：全部 $skippedInitial 首已索引，无需处理", WorkshopApi.Ui.ToastType.Success)
            return
        }

        val processed = AtomicInteger(0)
        val skipped = AtomicInteger(skippedInitial)
        val failed = AtomicInteger(0)
        val completed = AtomicInteger(0)

        val tasks = pending.map { item ->
            Callable {
                val outcome = processTrack(item, reference, writeTags)
                when (outcome) {
                    Outcome.DONE -> processed.incrementAndGet()
                    Outcome.SKIPPED -> skipped.incrementAndGet()
                    Outcome.FAILED -> failed.incrementAndGet()
                }
                val done = completed.incrementAndGet()
                if (done % PROGRESS_INTERVAL == 0) {
                    toast("ReplayGain 进度 $done/${pending.size}", WorkshopApi.Ui.ToastType.Success)
                }
            }
        }

        try {
            // invokeAll blocks until every track has been measured (or the pool is
            // shut down). Concurrent FFmpeg processes saturate the CPU instead of
            // decoding 800 files strictly one after another.
            workers.invokeAll(tasks)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            log("索引被中断", error)
            toast("ReplayGain 索引被中断", WorkshopApi.Ui.ToastType.Warning)
            return
        }

        toast(
            "ReplayGain 完成：处理 ${processed.get()}，跳过 ${skipped.get()}，失败 ${failed.get()}",
            WorkshopApi.Ui.ToastType.Success
        )
    }

    private fun runIndexCurrent() {
        if (!ensureReady()) return

        toast("ReplayGain 开始索引当前歌曲…", WorkshopApi.Ui.ToastType.Success)

        val item = try {
            WorkshopApi.playback.getCurrentMediaItem()
        } catch (error: Throwable) {
            val cause = unwrap(error)
            if (cause is PluginPermissionDeniedException) {
                toast("缺少曲库读取权限（LIBRARY_READ）", WorkshopApi.Ui.ToastType.Error)
            } else {
                log("读取当前歌曲失败", cause)
                toast("读取当前歌曲失败: ${cause.message}", WorkshopApi.Ui.ToastType.Error)
            }
            return
        }
        if (item == null) {
            toast("当前没有正在播放的歌曲", WorkshopApi.Ui.ToastType.Warning)
            return
        }

        val reference = config.referenceLoudness
        val writeTags = config.writeTags
        if (store.isIndexed(trackKey(item))) {
            toast("《${item.title}》已索引", WorkshopApi.Ui.ToastType.Success)
            return
        }

        when (processTrack(item, reference, writeTags)) {
            Outcome.DONE -> toast("已索引《${item.title}》", WorkshopApi.Ui.ToastType.Success)
            else -> toast("索引《${item.title}》失败", WorkshopApi.Ui.ToastType.Error)
        }
    }

    fun cleanupReplayGainTags() {
        submit { runCleanupReplayGainTags() }
    }

    private fun runCleanupReplayGainTags() {
        if (!ensureCleanupReady()) return

        toast("ReplayGain 开始清理增益标签…", WorkshopApi.Ui.ToastType.Success)

        val tracks = fetchAllTracks()
        if (tracks.isEmpty()) {
            toast("曲库为空，无需清理", WorkshopApi.Ui.ToastType.Warning)
            return
        }

        val cleaned = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val total = tracks.size

        val tasks = tracks.map { item ->
            Callable {
                val path = item.path
                val result = if (path.isBlank() || !File(path).isFile || !tagWriter.supports(path)) {
                    false
                } else {
                    tagWriter.removeReplayGainTags(path)
                }
                if (result) cleaned.incrementAndGet() else skipped.incrementAndGet()
                val done = completed.incrementAndGet()
                if (done % PROGRESS_INTERVAL == 0) {
                    toast("增益标签清理进度 $done/$total", WorkshopApi.Ui.ToastType.Success)
                }
            }
        }

        try {
            workers.invokeAll(tasks)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            log("标签清理被中断", error)
            toast("ReplayGain 标签清理被中断", WorkshopApi.Ui.ToastType.Warning)
            return
        }

        toast(
            "增益标签清理完成：清理 ${cleaned.get()}，跳过 ${skipped.get()}",
            WorkshopApi.Ui.ToastType.Success
        )
    }

    private fun ensureReady(): Boolean {
        if (ffmpegLocator() == null) {
            toast(
                "未检测到 FFmpeg，无法测量响度；请在插件设置中填写 FFmpeg 路径",
                WorkshopApi.Ui.ToastType.Error
            )
            return false
        }
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) {
            toast("缺少曲库读取权限（LIBRARY_READ）", WorkshopApi.Ui.ToastType.Error)
            return false
        }
        return true
    }

    private fun ensureCleanupReady(): Boolean {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) {
            toast("缺少曲库读取权限（LIBRARY_READ）", WorkshopApi.Ui.ToastType.Error)
            return false
        }
        return true
    }

    private fun fetchAllTracks(): List<MediaItem> {
        return try {
            WorkshopApi.library.getAllTracks().toCompletableFuture().join()
        } catch (error: Throwable) {
            val cause = unwrap(error)
            if (cause is PluginPermissionDeniedException) {
                toast("缺少曲库读取权限（LIBRARY_READ）", WorkshopApi.Ui.ToastType.Error)
            } else {
                log("读取曲库失败", cause)
                toast("读取曲库失败: ${cause.message}", WorkshopApi.Ui.ToastType.Error)
            }
            emptyList()
        }
    }

    private fun processTrack(item: MediaItem, reference: Double, writeTags: Boolean): Outcome {
        val path = item.path
        if (path.isBlank() || !File(path).isFile) {
            log("文件不存在，跳过: $path")
            return Outcome.FAILED
        }
        if (!tagWriter.supports(path)) {
            log("不支持的格式，跳过: $path")
            return Outcome.SKIPPED
        }

        val result = try {
            engine.measure(path, reference)
        } catch (error: InterruptedException) {
            // Cancellation: let it propagate so the run loop reports "已停止"
            // instead of miscounting this track as a measurement failure.
            throw error
        } catch (error: Throwable) {
            log("测量失败: $path", error)
            return Outcome.FAILED
        }

        if (writeTags) {
            val written = tagWriter.write(
                path,
                ReplayGainTags(
                    trackGainDb = result.trackGainDb,
                    trackPeakLinear = result.trackPeakLinear,
                    referenceLoudness = result.referenceLoudness
                )
            )
            if (!written) {
                log("写入标签失败: $path")
                return Outcome.FAILED
            }
        }

        store.markIndexed(trackKey(item), item.id, path, item.album, result)
        return Outcome.DONE
    }

    private fun trackKey(item: MediaItem): String = item.id.ifBlank { item.path }

    private fun unwrap(error: Throwable): Throwable =
        (error as? CompletionException)?.cause ?: error

    private fun toast(message: String, type: WorkshopApi.Ui.ToastType) {
        WorkshopApi.ui.toast(message, type)
    }

    private fun log(message: String, error: Throwable? = null) {
        println("[ReplayGain] $message")
        error?.printStackTrace()
    }

    private enum class Outcome { DONE, SKIPPED, FAILED }

    companion object {
        private const val PROGRESS_INTERVAL = 20
    }
}
