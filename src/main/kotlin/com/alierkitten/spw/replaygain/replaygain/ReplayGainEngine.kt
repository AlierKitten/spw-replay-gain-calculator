package com.alierkitten.spw.replaygain.replaygain

import com.alierkitten.spw.replaygain.replaygain.analyzer.AudioDecoder
import com.alierkitten.spw.replaygain.replaygain.analyzer.LoudnessAnalyzer
import java.io.File

/**
 * Measures integrated loudness (LUFS) and true peak entirely in-process,
 * decoding audio with pure-Java providers and applying an EBU R128 (BS.1770-4)
 * analysis. No FFmpeg binary or external process is involved.
 */
class ReplayGainEngine {

    fun measure(path: String, referenceLoudness: Double): ReplayGainResult {
        val file = File(path)
        require(file.isFile) { "File not found: $path" }

        val source = AudioDecoder.open(path)
        try {
            val (integrated, truePeakDb) = LoudnessAnalyzer.analyze(source)
            return ReplayGainResult(
                integratedLoudness = round2(integrated),
                truePeakDb = round2(truePeakDb),
                trackGainDb = round2(referenceLoudness - integrated),
                trackPeakLinear = round6(Math.pow(10.0, truePeakDb / 20.0)),
                referenceLoudness = referenceLoudness
            )
        } finally {
            runCatching { source.close() }
        }
    }

    companion object {
        fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

        fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
    }
}
