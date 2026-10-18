package com.alierkitten.spw.replaygain.replaygain

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Measures integrated loudness (LUFS) and true peak by delegating to FFmpeg's
 * `ebur128` filter. No audio decoding is performed in-process.
 */
class ReplayGainEngine(
    private val ffmpegExecutable: String?
) {
    fun measure(path: String, referenceLoudness: Double): ReplayGainResult {
        val executable = ffmpegExecutable
            ?: throw IllegalStateException("FFmpeg is not available")
        val file = File(path)
        require(file.isFile) { "File not found: $path" }

        val output = runEbur128(executable, file)
        val integrated = INTEGRATED_REGEX.find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: throw IllegalStateException("Unable to parse integrated loudness for ${file.name}")
        val truePeakDb = TRUE_PEAK_REGEX.find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: 0.0

        return ReplayGainResult(
            integratedLoudness = round2(integrated),
            truePeakDb = round2(truePeakDb),
            trackGainDb = round2(referenceLoudness - integrated),
            trackPeakLinear = round6(Math.pow(10.0, truePeakDb / 20.0)),
            referenceLoudness = referenceLoudness
        )
    }

    private fun runEbur128(executable: String, file: File): String {
        val process = ProcessBuilder(
            executable,
            "-hide_banner",
            "-nostats",
            "-i", file.absolutePath,
            "-af", "ebur128=peak=true",
            "-f", "null", "-"
        ).redirectErrorStream(true).start()

        // Drain output concurrently so a full pipe buffer cannot block FFmpeg.
        val output = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }

        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            output.cancel(true)
            throw IllegalStateException("FFmpeg timed out while measuring ${file.name}")
        }
        val exit = process.exitValue()
        val captured = output.get(30, TimeUnit.SECONDS)
        if (exit != 0) {
            throw IllegalStateException("FFmpeg exited with $exit for ${file.name}: ${captured.takeLast(500)}")
        }
        return captured
    }

    companion object {
        private val INTEGRATED_REGEX = Regex("""I:\s*(-?\d+(?:\.\d+)?)\s*LUFS""")
        private val TRUE_PEAK_REGEX = Regex("""Peak:\s*(-?\d+(?:\.\d+)?)\s*dBFS""")

        fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

        fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
    }
}
