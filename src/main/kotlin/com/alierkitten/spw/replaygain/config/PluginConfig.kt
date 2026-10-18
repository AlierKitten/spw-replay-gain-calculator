@file:OptIn(UnstableSpwWorkshopApi::class)

package com.alierkitten.spw.replaygain.config

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.nio.file.Path
import java.nio.file.Paths

object ConfigKeys {
    const val FILE_NAME = "config.json"
    const val REFERENCE_LOUDNESS = "replaygain.referenceLoudness"
    const val AUTO_INDEX_ON_START = "replaygain.autoIndexOnStart"
    const val WRITE_TAGS = "replaygain.writeTags"
}

class PluginConfig private constructor(
    private val manager: ConfigManager,
    private val helper: ConfigHelper
) {
    val referenceLoudness: Double
        get() = helper.get(ConfigKeys.REFERENCE_LOUDNESS, DEFAULT_REFERENCE_LOUDNESS)

    val autoIndexOnStart: Boolean
        get() = helper.get(ConfigKeys.AUTO_INDEX_ON_START, true)

    val writeTags: Boolean
        get() = helper.get(ConfigKeys.WRITE_TAGS, true)

    /**
     * All persistent plugin data lives next to the configuration file, i.e.
     * `workshop/data/<plugin-id>/`.
     */
    val dataDirectory: Path
        get() = helper.getConfigPath().toAbsolutePath().parent
            ?: Paths.get(".").toAbsolutePath()

    fun observeChanges() {
        manager.addConfigChangeListener { changed -> changed.reload() }
    }

    companion object {
        const val DEFAULT_REFERENCE_LOUDNESS = -18.0

        fun create(): PluginConfig {
            val manager = WorkshopApi.manager.createConfigManager()
            val helper = manager.getConfig(ConfigKeys.FILE_NAME)
            helper.reload()
            return PluginConfig(manager, helper)
        }
    }
}
