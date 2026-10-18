package com.alierkitten.spw.replaygain.util

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object FfmpegChecker {

    /** Successfully validated candidates, so repeated lookups do not spawn processes. */
    private val validated = ConcurrentHashMap<String, String>()

    /**
     * Locates a usable FFmpeg executable.
     *
     * Resolution order: user-configured path, then `FFMPEG_PATH`, then `ffmpeg` on `PATH`.
     * The configured value may be the executable itself or the directory containing it.
     * Returns `null` when no candidate is usable.
     */
    fun locate(configuredPath: String? = null): String? {
        return candidates(configuredPath).firstOrNull { isAvailable(it) }
    }

    private fun candidates(configuredPath: String?): List<String> {
        val result = LinkedHashSet<String>()
        configuredPath?.let(::normalize)?.let(result::add)
        System.getenv("FFMPEG_PATH")?.let(::normalize)?.let(result::add)
        result += "ffmpeg"
        return result.toList()
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trim('"', '\'')
        if (trimmed.isEmpty()) return null
        val asFile = File(trimmed)
        if (!asFile.isDirectory) return trimmed
        val executableName = if (isWindows()) "ffmpeg.exe" else "ffmpeg"
        return asFile.resolve(executableName).absolutePath
    }

    private fun isAvailable(candidate: String): Boolean {
        validated[candidate]?.let { return true }
        val available = runCatching {
            val process = ProcessBuilder(candidate, "-version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
        if (available) validated[candidate] = candidate
        return available
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
