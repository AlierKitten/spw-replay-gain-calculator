@file:OptIn(UnstableSpwWorkshopApi::class)

package com.alierkitten.spw.replaygain.config

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

object ConfigKeys {
    const val FILE_NAME = "config.json"
    const val REFERENCE_LOUDNESS = "replaygain.referenceLoudness"
    const val AUTO_INDEX_ON_START = "replaygain.autoIndexOnStart"
    const val WRITE_TAGS = "replaygain.writeTags"
    const val FFMPEG_PATH = "replaygain.ffmpegPath"
}

class PluginConfig private constructor(
    private val manager: ConfigManager,
    private val helper: ConfigHelper
) {
    /**
     * Target loudness in LUFS, entered as text.
     *
     * The value is clamped to the declared range and truncated to one decimal place;
     * extra digits are dropped both for computation and in the persisted file.
     */
    val referenceLoudness: Double
        get() = normalizeLoudness(readLoudness() ?: DEFAULT_REFERENCE_LOUDNESS)

    val autoIndexOnStart: Boolean
        get() = helper.get(ConfigKeys.AUTO_INDEX_ON_START, true)

    val writeTags: Boolean
        get() = helper.get(ConfigKeys.WRITE_TAGS, true)

    /**
     * User supplied FFmpeg location. May point at the executable itself or at the
     * directory containing it; blank means "fall back to auto detection".
     */
    val ffmpegPath: String
        get() = runCatching {
            val raw: String = helper.get(ConfigKeys.FFMPEG_PATH, "")
            raw.trim()
        }.getOrDefault("")

    /**
     * All persistent plugin data lives next to the configuration file, i.e.
     * `workshop/data/<plugin-id>/`.
     */
    val dataDirectory: Path
        get() = helper.getConfigPath().toAbsolutePath().parent
            ?: Paths.get(".").toAbsolutePath()

    @Volatile
    private var normalizing = false

    fun observeChanges() {
        manager.addConfigChangeListener { changed ->
            changed.reload()
            normalizeReferenceLoudness()
        }
    }

    /**
     * Rewrites the stored value with digits beyond the first decimal removed
     * (`-17.55` -> `-17.5`).
     *
     * The file is edited directly: [ConfigHelper.set] treats dotted keys as nested
     * objects, which does not necessarily match how the preference UI persists the very
     * same key.
     */
    fun normalizeReferenceLoudness() {
        if (normalizing) return
        val raw = readLoudness() ?: return
        val normalized = normalizeLoudness(raw)
        if (Math.abs(normalized - raw) < EPSILON) return

        val path = configFile()
        if (path == null) {
            println("[ReplayGain] 未找到配置文件，跳过参考响度修正")
            return
        }

        normalizing = true
        try {
            val original = runCatching { Files.readString(path) }.getOrNull() ?: return
            val match = FLAT_KEY_REGEX.find(original) ?: LEAF_KEY_REGEX.find(original)
            if (match == null) {
                println("[ReplayGain] 配置文件中未找到 ${ConfigKeys.REFERENCE_LOUDNESS}")
                return
            }
            val replacement = String.format(Locale.US, "%.1f", normalized)
            val rebuilt = match.groupValues[1] + match.groupValues[2] + replacement +
                match.groupValues[4]
            if (rebuilt == match.value) return

            Files.writeString(path, original.replaceRange(match.range, rebuilt))
            println("[ReplayGain] 参考响度已修正: $raw -> $normalized")
            runCatching { helper.reload() }
        } catch (error: Throwable) {
            println("[ReplayGain] 修正参考响度失败: ${error.message}")
        } finally {
            normalizing = false
        }
    }

    /**
     * Reads the value from the configuration file first: the preference UI owns that
     * file, and reading it avoids depending on how [ConfigHelper] maps dotted keys.
     */
    private fun readLoudness(): Double? {
        diskLoudness()?.let { return it }

        val asText = runCatching {
            val value: String = helper.get(ConfigKeys.REFERENCE_LOUDNESS, "")
            value
        }.getOrDefault("")
        asText.trim().toDoubleOrNull()?.let { return it }

        val asFloat = runCatching {
            val value: Float = helper.get(ConfigKeys.REFERENCE_LOUDNESS, Float.NaN)
            value
        }.getOrNull()
        if (asFloat != null && !asFloat.isNaN()) return asFloat.toDouble()

        val asDouble = runCatching {
            val value: Double = helper.get(ConfigKeys.REFERENCE_LOUDNESS, Double.NaN)
            value
        }.getOrNull()
        return if (asDouble != null && !asDouble.isNaN()) asDouble else null
    }

    private fun diskLoudness(): Double? {
        val path = configFile() ?: return null
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return null
        val match = FLAT_KEY_REGEX.find(text) ?: LEAF_KEY_REGEX.find(text) ?: return null
        return match.groupValues[3].toDoubleOrNull()
    }

    private fun configFile(): Path? {
        val path = runCatching { helper.getConfigPath() }.getOrNull() ?: return null
        return path.takeIf { Files.isRegularFile(it) }
    }

    /** Clamps to the allowed range and drops everything beyond the first decimal. */
    private fun normalizeLoudness(value: Double): Double {
        val clamped = value.coerceIn(MIN_REFERENCE_LOUDNESS, MAX_REFERENCE_LOUDNESS)
        return (clamped * 10.0).toInt() / 10.0
    }

    companion object {
        const val DEFAULT_REFERENCE_LOUDNESS = -18.0
        const val MIN_REFERENCE_LOUDNESS = -30.0
        const val MAX_REFERENCE_LOUDNESS = 0.0

        private const val EPSILON = 0.000001

        /** `"replaygain.referenceLoudness": "-17.55"` (number or quoted number) */
        private val FLAT_KEY_REGEX = Regex(
            """("replaygain\.referenceLoudness"\s*:\s*)("?)(-?\d+(?:\.\d+)?)("?)"""
        )

        /** `"referenceLoudness": "-17.55"` (nested object form) */
        private val LEAF_KEY_REGEX = Regex(
            """("referenceLoudness"\s*:\s*)("?)(-?\d+(?:\.\d+)?)("?)"""
        )

        fun create(): PluginConfig {
            val manager = WorkshopApi.manager.createConfigManager()
            val helper = manager.getConfig(ConfigKeys.FILE_NAME)
            helper.reload()
            return PluginConfig(manager, helper)
        }
    }
}
