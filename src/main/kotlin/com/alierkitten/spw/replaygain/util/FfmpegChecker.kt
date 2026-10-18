package com.alierkitten.spw.replaygain.util

import java.util.concurrent.TimeUnit

object FfmpegChecker {

    @Volatile
    private var resolved: String? = null

    /**
     * Locates a usable FFmpeg executable.
     *
     * Uses `FFMPEG_PATH` when set, otherwise relies on `ffmpeg` being available on `PATH`.
     * Returns `null` when FFmpeg is not installed.
     */
    fun locate(): String? {
        resolved?.let { return it }
        val candidate = System.getenv("FFMPEG_PATH")?.takeIf { it.isNotBlank() } ?: "ffmpeg"
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

        return if (available) {
            candidate.also { resolved = it }
        } else {
            null
        }
    }
}
