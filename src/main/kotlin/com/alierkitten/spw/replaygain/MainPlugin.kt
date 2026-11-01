package com.alierkitten.spw.replaygain

import com.alierkitten.spw.replaygain.config.PluginConfig
import com.alierkitten.spw.replaygain.index.IndexService
import com.alierkitten.spw.replaygain.index.IndexStore
import com.alierkitten.spw.replaygain.replaygain.ReplayGainEngine
import com.alierkitten.spw.replaygain.replaygain.TagWriter
import com.alierkitten.spw.replaygain.util.FfmpegChecker
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.net.URLClassLoader

class MainPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    private var store: IndexStore? = null
    private var indexService: IndexService? = null
    private var pluginConfig: PluginConfig? = null

    override fun start() {
        instance = this

        val config = PluginConfig.create()
        pluginConfig = config
        config.observeChanges()
        config.normalizeReferenceLoudness()
        val reference = config.referenceLoudness
        println("[ReplayGain] 参考响度: $reference LUFS")

        val ffmpegLocator = { FfmpegChecker.locate(config.ffmpegPath) }
        val ffmpeg = ffmpegLocator()
        if (ffmpeg == null) {
            WorkshopApi.ui.toast(
                "未检测到 FFmpeg，ReplayGain 测量不可用；请在插件设置中填写 FFmpeg 路径",
                WorkshopApi.Ui.ToastType.Warning
            )
        }

        // Connection is opened lazily on first use and closed on idle, so the
        // bundled sqlite-jdbc jar is not held open while the plugin is inactive
        // (which otherwise blocks deleting/upgrading the plugin on Windows).
        val newStore = IndexStore(config.dataDirectory.resolve("replaygain.db"))
        store = newStore

        val service = IndexService(
            store = newStore,
            engine = ReplayGainEngine(ffmpegLocator),
            tagWriter = TagWriter(),
            config = config,
            ffmpegLocator = ffmpegLocator
        )
        indexService = service

        if (config.autoIndexOnStart && ffmpeg != null) {
            service.indexAll()
        }

        WorkshopApi.ui.toast(
            "ReplayGain Calculator 已启动（参考响度 $reference LUFS）",
            WorkshopApi.Ui.ToastType.Success
        )
    }

    override fun stop() {
        // Must run before releasing anything else: the host's ConfigManager keeps a
        // strong reference to the change listener, which pins this plugin's classloader
        // (and its bundled jars) and blocks deleting/upgrading the plugin on Windows.
        pluginConfig?.dispose()
        pluginConfig = null
        indexService?.shutdown()
        store?.shutdown()
        indexService = null
        store = null
        if (instance === this) instance = null
        WorkshopApi.ui.toast("ReplayGain Calculator 已停止", WorkshopApi.Ui.ToastType.Warning)
        // The host keeps the plugin classloader alive after stop(), which holds every
        // bundled jar in `lib/` (sqlite-jdbc, jaudiotagger) open and blocks deleting or
        // upgrading the plugin folder on Windows. Close it explicitly; PF4J's later
        // unload will close it again (idempotent). Classes already loaded stay valid in
        // memory, so re-enabling works as long as the plugin has been used once.
        releaseClassLoader()
    }

    private fun releaseClassLoader() {
        val loader = MainPlugin::class.java.classLoader as? URLClassLoader ?: return
        runCatching { loader.close() }
    }

    companion object {
        @Volatile
        private var instance: MainPlugin? = null

        @JvmStatic
        @JvmName("onIndexAllButtonClick")
        fun onIndexAllButtonClick() {
            val service = instance?.indexService
            if (service == null) {
                WorkshopApi.ui.toast("插件尚未启动", WorkshopApi.Ui.ToastType.Warning)
                return
            }
            service.indexAll()
        }

        @JvmStatic
        @JvmName("onReindexAllButtonClick")
        fun onReindexAllButtonClick() {
            val service = instance?.indexService
            if (service == null) {
                WorkshopApi.ui.toast("插件尚未启动", WorkshopApi.Ui.ToastType.Warning)
                return
            }
            service.reindexAll()
        }

        @JvmStatic
        @JvmName("onIndexCurrentButtonClick")
        fun onIndexCurrentButtonClick() {
            val service = instance?.indexService
            if (service == null) {
                WorkshopApi.ui.toast("插件尚未启动", WorkshopApi.Ui.ToastType.Warning)
                return
            }
            service.indexCurrent()
        }

        @JvmStatic
        @JvmName("onCleanupReplayGainTagsButtonClick")
        fun onCleanupReplayGainTagsButtonClick() {
            val service = instance?.indexService
            if (service == null) {
                WorkshopApi.ui.toast("插件尚未启动", WorkshopApi.Ui.ToastType.Warning)
                return
            }
            service.cleanupReplayGainTags()
        }
    }
}
