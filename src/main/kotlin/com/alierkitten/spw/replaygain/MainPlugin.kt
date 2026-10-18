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

class MainPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    private var store: IndexStore? = null
    private var indexService: IndexService? = null

    override fun start() {
        instance = this

        val pluginConfig = PluginConfig.create()
        pluginConfig.observeChanges()
        pluginConfig.normalizeReferenceLoudness()
        val reference = pluginConfig.referenceLoudness
        println("[ReplayGain] 参考响度: $reference LUFS")

        val ffmpegLocator = { FfmpegChecker.locate(pluginConfig.ffmpegPath) }
        val ffmpeg = ffmpegLocator()
        if (ffmpeg == null) {
            WorkshopApi.ui.toast(
                "未检测到 FFmpeg，ReplayGain 测量不可用；请在插件设置中填写 FFmpeg 路径",
                WorkshopApi.Ui.ToastType.Warning
            )
        }

        val newStore = IndexStore(pluginConfig.dataDirectory.resolve("replaygain.db"))
        newStore.init()
        store = newStore

        val service = IndexService(
            store = newStore,
            engine = ReplayGainEngine(ffmpegLocator),
            tagWriter = TagWriter(),
            config = pluginConfig,
            ffmpegLocator = ffmpegLocator
        )
        indexService = service

        if (pluginConfig.autoIndexOnStart && ffmpeg != null) {
            service.indexAll()
        }

        WorkshopApi.ui.toast(
            "ReplayGain Calculator 已启动（参考响度 $reference LUFS）",
            WorkshopApi.Ui.ToastType.Success
        )
    }

    override fun stop() {
        indexService?.shutdown()
        store?.close()
        indexService = null
        store = null
        if (instance === this) instance = null
        WorkshopApi.ui.toast("ReplayGain Calculator 已停止", WorkshopApi.Ui.ToastType.Warning)
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
        @JvmName("onIndexCurrentButtonClick")
        fun onIndexCurrentButtonClick() {
            val service = instance?.indexService
            if (service == null) {
                WorkshopApi.ui.toast("插件尚未启动", WorkshopApi.Ui.ToastType.Warning)
                return
            }
            service.indexCurrent()
        }
    }
}
