package com.alierkitten.spw.replaygain.replaygain.analyzer

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * In-process EBU R128 (ITU-R BS.1770-4) loudness measurement, done **streaming**
 * so memory use is independent of track length.
 *
 * Pipeline per file:
 * 1. pull PCM chunks from a [PcmChunkSource];
 * 2. per channel, resample to 48 kHz (the rate the K-weighting is defined at);
 * 3. apply the K-weighting filter and accumulate per-400 ms-block energy
 *    (75% overlap) for integrated loudness with absolute + relative gating;
 * 4. 4x-oversample the plain 48 kHz signal to obtain the true peak, tracking
 *    only the maximum magnitude (never buffering the oversampled stream).
 *
 * No external process or native binary is used.
 */
object LoudnessAnalyzer {

    private const val TARGET_RATE = 48000
    private const val TRUE_PEAK_FACTOR = 4
    private const val LOUDNESS_OFFSET = -0.691
    private const val ABSOLUTE_GATE = -70.0
    private const val RELATIVE_GATE = 10.0

    fun analyze(source: PcmChunkSource): LoudnessResult {
        val channels = source.channels
        require(channels >= 1) { "音频至少需包含 1 个声道" }
        val inRate = source.sampleRate
        val processors = Array(channels) { ChannelProcessor(inRate) }

        val chunk = FloatArray(4096)
        val perCh = Array(channels) { FloatArray(4096) }
        while (true) {
            val n = source.read(chunk)
            if (n == 0) break
            for (c in 0 until channels) {
                var k = 0
                var i = c
                while (i < n) {
                    perCh[c][k++] = chunk[i]
                    i += channels
                }
                if (k > 0) processors[c].feed(perCh[c].copyOf(k))
            }
        }
        source.close()

        val results = processors.map { it.finish() }
        val numBlocks = results.first().first.size
        val blocks = (0 until numBlocks).map { b ->
            val e = DoubleArray(channels) { c -> results[c].first[b] }
            BlockEnergy(e, results[0].second[b])
        }

        val integrated = integratedLoudness(blocks)
        var peak = 0.0
        for (p in processors) peak = max(peak, p.peak)
        val truePeakDb = if (peak <= 0.0) -120.0 else 20.0 * log10(peak)
        return LoudnessResult(integrated, truePeakDb)
    }

    /**
     * Windowed-sinc (Lanczos-style) resampler wrapper that feeds input in chunks
     * and emits output samples one by one via [onOutput]. A small overlap with
     * the previous chunk's tail keeps the sinc kernel continuous across chunk
     * boundaries, so no full track buffer is needed.
     */
    private class OverlapResampler(
        private val inRate: Int,
        private val outRate: Int,
        private val onOutput: (Float) -> Unit
    ) {
        private var prevIn = FloatArray(0)

        fun feed(input: FloatArray) {
            val seg = if (prevIn.isEmpty()) input else FloatArray(prevIn.size + input.size) { i ->
                if (i < prevIn.size) prevIn[i] else input[i - prevIn.size]
            }
            val segOut = Resampler.resample(seg, inRate, outRate)
            val skip = if (prevIn.isEmpty()) 0
            else ((prevIn.size.toDouble() * outRate) / inRate).toInt().coerceAtMost(segOut.size)
            for (n in skip until segOut.size) onOutput(segOut[n])
            val tailStart = maxOf(0, input.size - Resampler.HALF)
            prevIn = if (tailStart >= input.size) FloatArray(0) else input.copyOfRange(tailStart, input.size)
        }

        fun flush() {
            // Trailing samples within the last HALF input window are negligibly few.
        }
    }

    /**
     * Per-channel processing: resample inRate -> 48 kHz, K-weight, accumulate
     * 400 ms block energies, and feed the plain 48 kHz signal into a 4x
     * oversampler for true-peak tracking.
     */
    private class ChannelProcessor(private val inRate: Int) {
        private val target = TARGET_RATE
        private val tpRate = TARGET_RATE * TRUE_PEAK_FACTOR
        private val blockLen = (0.4 * target).toInt().coerceAtLeast(1)
        private val hop = (0.1 * target).toInt().coerceAtLeast(1)

        private val r48 = OverlapResampler(inRate, target) { on48(it) }
        private val tp = OverlapResampler(target, tpRate) { trackPeak(it) }

        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        private var s1 = 0.0; private var s2 = 0.0; private var t1 = 0.0; private var t2 = 0.0

        private val win = FloatArray(blockLen)
        private var winCount = 0
        private val energies = mutableListOf<Double>()
        private val counts = mutableListOf<Int>()
        var peak = 0.0

        private val tpAcc = FloatArray(4096)
        private var tpAccN = 0

        private fun on48(plain: Float) {
            val x = plain.toDouble()
            // Stage 1: high shelf.
            val yS1 = B0 * x + B1 * x1 + B2 * x2 - A1 * y1 - A2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = yS1
            // Stage 2: high pass.
            val w = C0 * yS1 + C1 * s1 + C2 * s2 - D1 * t1 - D2 * t2
            s2 = s1; s1 = yS1; t2 = t1; t1 = w

            win[winCount++] = w.toFloat()
            if (winCount == blockLen) {
                energies.add(sumSq(win, blockLen))
                counts.add(blockLen)
                win.copyInto(win, 0, hop, blockLen)
                winCount = blockLen - hop
            }

            tpAcc[tpAccN++] = plain
            if (tpAccN == tpAcc.size) {
                tp.feed(tpAcc)
                tpAccN = 0
            }
        }

        private fun trackPeak(v: Float) {
            val a = abs(v.toDouble())
            if (a > peak) peak = a
        }

        fun feed(input: FloatArray) {
            r48.feed(input)
        }

        fun finish(): Pair<List<Double>, List<Int>> {
            r48.flush()
            if (winCount > 0) {
                energies.add(sumSq(win, winCount))
                counts.add(winCount)
            }
            if (tpAccN > 0) {
                tp.feed(tpAcc.copyOf(tpAccN))
                tpAccN = 0
            }
            tp.flush()
            return energies to counts
        }

        private fun sumSq(arr: FloatArray, len: Int): Double {
            var s = 0.0
            for (i in 0 until len) {
                val v = arr[i].toDouble()
                s += v * v
            }
            return s
        }

        companion object {
            // K-weighting coefficients fixed for 48 kHz (ITU-R BS.1770-4).
            const val B0 = 1.53512485908763; const val B1 = -2.69169609015443; const val B2 = 1.19839281029951
            const val A1 = -1.69065929318241; const val A2 = 0.73248077421514
            const val C0 = 1.0; const val C1 = -2.0; const val C2 = 1.0
            const val D1 = -1.99004745483398; const val D2 = 0.990072461081968
        }
    }

    private fun integratedLoudness(blocks: List<BlockEnergy>): Double {
        if (blocks.isEmpty()) return ABSOLUTE_GATE
        val absKept = blocks.filter { loudnessFromEnergy(it.energy, it.count) >= ABSOLUTE_GATE }
        if (absKept.isEmpty()) return ABSOLUTE_GATE
        val tmp = pool(absKept)
        val threshold = loudnessFromEnergy(tmp.energy, tmp.count) - RELATIVE_GATE
        val relKept = absKept.filter { loudnessFromEnergy(it.energy, it.count) >= threshold }
        val fin = pool(relKept)
        return loudnessFromEnergy(fin.energy, fin.count)
    }

    private fun loudnessFromEnergy(energy: DoubleArray, count: Int): Double {
        if (count <= 0) return -120.0
        var sum = 0.0
        for (e in energy) sum += e
        if (sum <= 0.0) return -120.0
        return LOUDNESS_OFFSET + 10.0 * log10(sum / count)
    }

    private fun pool(blocks: List<BlockEnergy>): BlockEnergy {
        val channels = blocks.first().energy.size
        val energy = DoubleArray(channels)
        var count = 0
        for (b in blocks) {
            for (c in 0 until channels) energy[c] += b.energy[c]
            count += b.count
        }
        return BlockEnergy(energy, count)
    }

    private data class BlockEnergy(val energy: DoubleArray, val count: Int)
}

data class LoudnessResult(
    val integratedLoudness: Double,
    val truePeakDb: Double
)
