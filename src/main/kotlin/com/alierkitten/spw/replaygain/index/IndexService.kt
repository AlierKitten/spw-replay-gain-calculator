package com.alierkitten.spw.replaygain.index

import com.alierkitten.spw.replaygain.config.PluginConfig
import com.alierkitten.spw.replaygain.replaygain.ReplayGainEngine
import com.alierkitten.spw.replaygain.replaygain.ReplayGainResult
import com.alierkitten.spw.replaygain.replaygain.ReplayGainTags
import com.alierkitten.spw.replaygain.replaygain.TagWriter
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Orchestrates the index pipeline: read tracks -> filter unindexed -> measure ->
 * write tags -> persist.
 */
class IndexService(
    private val store: IndexStore,
    private val engine: ReplayGainEngine,
    private val tagWriter: TagWriter,
    private val config: PluginConfig
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spw-replaygain-indexer").apply { isDaemon = true }
    }

    @Volatile
    private var busy = false

    /** First failure reason, surfaced to the user via the summary toast. */
    private var firstError: String? = null

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
        executor.shutdownNow()
    }

    private fun submit(task: () -> Unit) {
        if (busy) {
            toast("ReplayGain 索引任务正在进行中", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        busy = true
        executor.submit {
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

        firstError = null
        val reference = config.referenceLoudness
        val writeTags = config.writeTags
        val tracks = fetchAllTracks()
        if (tracks.isEmpty()) {
            toast("曲库为空，无法索引", WorkshopApi.Ui.ToastType.Warning)
            return
        }

        var processed = 0
        var skipped = 0
        var failed = 0

        tracks.forEachIndexed { index, item ->
            val key = trackKey(item)
            if (!force && store.isIndexed(key)) {
                skipped++
                return@forEachIndexed
            }
            when (processTrack(item, reference, writeTags)) {
                Outcome.DONE -> processed++
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failed++
            }
            if ((index + 1) % PROGRESS_INTERVAL == 0) {
                toast("ReplayGain 进度 ${index + 1}/${tracks.size}", WorkshopApi.Ui.ToastType.Success)
            }
        }

        val summary = buildString {
            append("ReplayGain 完成：处理 $processed，跳过 $skipped，失败 $failed")
            if (failed > 0 && firstError != null) {
                append("；首个错误：$firstError")
            }
        }
        toast(summary, WorkshopApi.Ui.ToastType.Success)
    }

    private fun runIndexCurrent() {
        if (!ensureReady()) return

        firstError = null
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
            else -> toast(
                "索引《${item.title}》失败${if (firstError != null) "：$firstError" else ""}",
                WorkshopApi.Ui.ToastType.Error
            )
        }
    }

    fun cleanupReplayGainTags() {
        submit { runCleanupReplayGainTags() }
    }

    private fun runCleanupReplayGainTags() {
        if (!ensureCleanupReady()) return

        val tracks = fetchAllTracks()
        if (tracks.isEmpty()) {
            toast("曲库为空，无需清理", WorkshopApi.Ui.ToastType.Warning)
            return
        }

        var cleaned = 0
        var skipped = 0
        tracks.forEachIndexed { index, item ->
            val path = item.path
            if (path.isBlank() || !File(path).isFile || !tagWriter.supports(path)) {
                skipped++
                return@forEachIndexed
            }
            if (tagWriter.removeReplayGainTags(path)) {
                cleaned++
            } else {
                skipped++
            }
            if ((index + 1) % PROGRESS_INTERVAL == 0) {
                toast("增益标签清理进度 ${index + 1}/${tracks.size}", WorkshopApi.Ui.ToastType.Success)
            }
        }

        toast(
            "增益标签清理完成：清理 $cleaned，跳过 $skipped",
            WorkshopApi.Ui.ToastType.Success
        )
    }

    private fun ensureReady(): Boolean {
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
            measureWithTimeout(path, reference, 120_000L)
        } catch (error: Throwable) {
            log("测量失败: $path", error)
            if (firstError == null) firstError = concise(error, path)
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

    /**
     * Runs [ReplayGainEngine.measure] on a dedicated thread and aborts it after
     * [timeoutMs] if it has not returned. This guarantees that a pathological
     * decoder/decode loop (which would otherwise hang the single-threaded index
     * job forever and leave `busy` stuck) can never block the whole pipeline:
     * the offending track is simply failed and indexing continues.
     */
    private fun measureWithTimeout(path: String, reference: Double, timeoutMs: Long): ReplayGainResult {
        val box = AtomicReference<ReplayGainResult>()
        val err = AtomicReference<Throwable>()
        val worker = Thread({
            try {
                box.set(engine.measure(path, reference))
            } catch (e: Throwable) {
                err.set(e)
            }
        }, "spw-rg-measure").apply { isDaemon = true; start() }
        worker.join(timeoutMs)
        if (worker.isAlive) {
            worker.interrupt()
            throw RuntimeException("ReplayGain 测量超时（疑似解码死循环）: $path")
        }
        err.get()?.let { throw it }
        return box.get() ?: throw RuntimeException("ReplayGain 测量无结果: $path")
    }

    private fun unwrap(error: Throwable): Throwable =
        (error as? CompletionException)?.cause ?: error

    private fun concise(error: Throwable, path: String): String {
        val cause = unwrap(error)
        val name = cause::class.java.simpleName
        val msg = cause.message?.lineSequence()?.firstOrNull()?.take(120) ?: "未知错误"
        return "$name: $msg"
    }

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
