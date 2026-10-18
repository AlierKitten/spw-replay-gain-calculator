package com.alierkitten.spw.replaygain

import com.alierkitten.spw.replaygain.config.PluginConfig
import com.alierkitten.spw.replaygain.index.IndexService
import com.alierkitten.spw.replaygain.index.IndexStore
import com.alierkitten.spw.replaygain.replaygain.ReplayGainEngine
import com.alierkitten.spw.replaygain.replaygain.TagWriter
import com.alierkitten.spw.replaygain.util.FfmpegChecker
import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.event.KeyEvent

class MainPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    private var store: IndexStore? = null
    private var indexService: IndexService? = null

    override fun start() {
        instance = this

        val pluginConfig = PluginConfig.create()
        pluginConfig.observeChanges()

        val ffmpeg = FfmpegChecker.locate()
        if (ffmpeg == null) {
            WorkshopApi.ui.toast(
                "未检测到 FFmpeg，ReplayGain 测量不可用，请先安装 FFmpeg",
                WorkshopApi.Ui.ToastType.Warning
            )
        }

        val newStore = IndexStore(pluginConfig.dataDirectory.resolve("replaygain.db"))
        newStore.init()
        store = newStore

        val service = IndexService(
            store = newStore,
            engine = ReplayGainEngine(ffmpeg),
            tagWriter = TagWriter(),
            config = pluginConfig,
            ffmpegAvailable = ffmpeg != null
        )
        indexService = service

        registerKeyBinding()

        if (pluginConfig.autoIndexOnStart && ffmpeg != null) {
            service.indexAll()
        }

        WorkshopApi.ui.toast("ReplayGain Calculator 已启动", WorkshopApi.Ui.ToastType.Success)
    }

    override fun stop() {
        runCatching {
            if (WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) {
                WorkshopApi.manager.keyBindingManager.unregister(actionId = ACTION_INDEX_CURRENT)
            }
        }
        indexService?.shutdown()
        store?.close()
        indexService = null
        store = null
        if (instance === this) instance = null
        WorkshopApi.ui.toast("ReplayGain Calculator 已停止", WorkshopApi.Ui.ToastType.Warning)
    }

    private fun registerKeyBinding() {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.KEY_BINDINGS)) return
        runCatching {
            WorkshopApi.manager.keyBindingManager.register(
                actionId = ACTION_INDEX_CURRENT,
                title = "ReplayGain: Index current song",
                defaultShortcut = ActionShortcut(
                    KeyEvent.VK_R,
                    ActionShortcut.CONTROL or ActionShortcut.ALT
                ),
                hasGlobal = false,
                handler = { indexService?.indexCurrent() }
            )
        }.onFailure { error ->
            println("[ReplayGain] 注册快捷键失败: ${error.message}")
        }
    }

    companion object {
        private const val ACTION_INDEX_CURRENT = "replaygain-index-current"

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
