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
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Orchestrates the index pipeline: read tracks -> filter unindexed -> measure ->
 * write tags -> persist -> finalize album gain.
 */
class IndexService(
    private val store: IndexStore,
    private val engine: ReplayGainEngine,
    private val tagWriter: TagWriter,
    private val config: PluginConfig,
    private val ffmpegAvailable: Boolean
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spw-replaygain-indexer").apply { isDaemon = true }
    }

    @Volatile
    private var busy = false

    fun indexAll() {
        submit { runIndexAll() }
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

    private fun runIndexAll() {
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
        val touchedAlbums = linkedSetOf<String>()

        tracks.forEachIndexed { index, item ->
            val key = trackKey(item)
            if (store.isIndexed(key)) {
                skipped++
                return@forEachIndexed
            }
            when (processTrack(item, reference, writeTags)) {
                Outcome.DONE -> {
                    processed++
                    if (item.album.isNotBlank()) touchedAlbums += item.album
                }
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failed++
            }
            if ((index + 1) % PROGRESS_INTERVAL == 0) {
                toast("ReplayGain 进度 ${index + 1}/${tracks.size}", WorkshopApi.Ui.ToastType.Success)
            }
        }

        finalizeAlbums(touchedAlbums, reference, writeTags)
        toast(
            "ReplayGain 完成：处理 $processed，跳过 $skipped，失败 $failed",
            WorkshopApi.Ui.ToastType.Success
        )
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

        when (processTrack(item, reference, writeTags)) {
            Outcome.DONE -> {
                if (item.album.isNotBlank()) {
                    finalizeAlbums(setOf(item.album), reference, writeTags)
                }
                toast("已索引《${item.title}》", WorkshopApi.Ui.ToastType.Success)
            }
            else -> toast("索引《${item.title}》失败", WorkshopApi.Ui.ToastType.Error)
        }
    }

    private fun ensureReady(): Boolean {
        if (!ffmpegAvailable) {
            toast("未检测到 FFmpeg，无法测量响度", WorkshopApi.Ui.ToastType.Error)
            return false
        }
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
                    albumGainDb = null,
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

    private fun finalizeAlbums(albums: Set<String>, reference: Double, writeTags: Boolean) {
        albums.asSequence()
            .filter { it.isNotBlank() }
            .forEach { album ->
                val rows = store.getAlbumTracks(album)
                if (rows.isEmpty()) return@forEach

                // Approximate album loudness as the energy average of its tracks.
                val meanEnergy = rows.map { Math.pow(10.0, it.integratedLoudness / 10.0) }.average()
                if (meanEnergy.isNaN() || meanEnergy <= 0.0) return@forEach
                val albumLoudness = 10.0 * Math.log10(meanEnergy)
                val albumGain = ReplayGainEngine.round2(reference - albumLoudness)

                store.updateAlbumGain(album, albumGain)
                if (writeTags) {
                    rows.forEach { tagWriter.writeAlbumGain(it.path, albumGain) }
                }
            }
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
