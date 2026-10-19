package com.alierkitten.spw.replaygain.index

import com.alierkitten.spw.replaygain.config.PluginConfig
import com.alierkitten.spw.replaygain.host.HostLibraryRefresher
import com.alierkitten.spw.replaygain.host.RefreshResult
import com.alierkitten.spw.replaygain.replaygain.ReplayGainEngine
import com.alierkitten.spw.replaygain.replaygain.ReplayGainTags
import com.alierkitten.spw.replaygain.replaygain.TagWriter
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the index pipeline: read tracks -> filter unindexed -> measure ->
 * write tags -> persist -> (optionally) trigger a host library refresh so the
 * newly written tags are picked up immediately by Salt Player's "volume balance".
 */
class IndexService(
    private val store: IndexStore,
    private val engine: ReplayGainEngine,
    private val tagWriter: TagWriter,
    private val config: PluginConfig,
    private val ffmpegLocator: () -> String?,
    private val hostRefresher: HostLibraryRefresher,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spw-replaygain-indexer").apply { isDaemon = true }
    }

    @Volatile
    private var busy = AtomicBoolean(false)

    /**
     * Independent guard for the standalone "trigger host refresh" entry. It is checked
     * before submitting so repeated button presses cannot enqueue multiple rebuild calls
     * on the indexer executor.
     */
    @Volatile
    private var refreshInFlight = AtomicBoolean(false)

    fun indexAll() {
        submit { runIndexAll(force = false) }
    }

    fun reindexAll() {
        submit { runIndexAll(force = true) }
    }

    fun indexCurrent() {
        submit { runIndexCurrent() }
    }

    fun triggerHostRefresh() {
        // Deduplicate: rapid button clicks would otherwise queue N reflective calls on
        // the executor, each of which triggers a full host library rebuild.
        if (!refreshInFlight.compareAndSet(false, true)) {
            toast("宿主曲库刷新请求已在执行中", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        executor.submit {
            try {
                runTriggerHostRefresh()
            } catch (error: Throwable) {
                log("刷新任务失败: ${error.message}", error)
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    fun cleanupReplayGainTags() {
        submit { runCleanupReplayGainTags() }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun submit(task: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            toast("ReplayGain 索引任务正在进行中", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        executor.submit {
            try {
                task()
            } catch (error: Throwable) {
                log("索引任务失败: ${error.message}", error)
                toast("ReplayGain 索引失败: ${error.message}", WorkshopApi.Ui.ToastType.Error)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun runIndexAll(force: Boolean) {
        if (!ensureReady()) return

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

        toast(
            "ReplayGain 完成：处理 $processed，跳过 $skipped，失败 $failed",
            WorkshopApi.Ui.ToastType.Success
        )
        finishWithHostRefresh(processed)
    }

    private fun runIndexCurrent() {
        if (!ensureReady()) return

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

        val processed = when (processTrack(item, reference, writeTags)) {
            Outcome.DONE -> {
                toast("已索引《${item.title}》", WorkshopApi.Ui.ToastType.Success)
                1
            }
            else -> {
                toast("索引《${item.title}》失败", WorkshopApi.Ui.ToastType.Error)
                0
            }
        }
        finishWithHostRefresh(processed)
    }

    private fun runTriggerHostRefresh() {
        when (val result = hostRefresher.trigger()) {
            RefreshResult.Triggered ->
                toast("已请求宿主刷新曲库", WorkshopApi.Ui.ToastType.Success)
            RefreshResult.Unavailable -> {
                log("宿主扫描入口未配置或不可用")
                toast("当前 Salt Player 版本未提供反射入口，请手动刷新音乐库", WorkshopApi.Ui.ToastType.Warning)
            }
            is RefreshResult.Failed -> {
                log("宿主扫描入口调用失败: ${result.reason}")
                toast("未能触发宿主曲库刷新，请手动刷新音乐库", WorkshopApi.Ui.ToastType.Warning)
            }
        }
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

    /**
     * After a measurement run, optionally trigger a host-side library refresh so Salt
     * Player re-reads the freshly written ReplayGain tags. Skipped when nothing was
     * actually processed (no point kicking off a full rebuild) or when the user opted
     * out via [PluginConfig.triggerHostScan]. Always degrades gracefully: a failed
     * reflection call only emits one log line and one toast.
     */
    private fun finishWithHostRefresh(processed: Int) {
        if (!config.triggerHostScan) return
        if (processed <= 0) return
        when (val result = hostRefresher.trigger()) {
            RefreshResult.Triggered ->
                toast("已请求宿主刷新曲库（ReplayGain 即将生效）", WorkshopApi.Ui.ToastType.Success)
            RefreshResult.Unavailable -> {
                log("宿主扫描入口不可用，请手动刷新音乐库")
                toast("未能触发宿主曲库刷新，请手动刷新音乐库", WorkshopApi.Ui.ToastType.Warning)
            }
            is RefreshResult.Failed -> {
                log("宿主扫描入口调用失败: ${result.reason}")
                toast("未能触发宿主曲库刷新，请手动刷新音乐库", WorkshopApi.Ui.ToastType.Warning)
            }
        }
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