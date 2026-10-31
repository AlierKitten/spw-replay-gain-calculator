package com.alierkitten.spw.replaygain.replaygain.analyzer

/**
 * Band-limited resampler based on a windowed-sinc (Lanczos-style) interpolation
 * kernel. Used to bring arbitrary sample rates to the 48 kHz the EBU R128
 * K-weighting is specified at, and to 4x-oversample for true-peak detection.
 *
 * The kernel cutoff tracks `min(inRate, outRate)`, so downsampling applies the
 * correct anti-alias low-pass while upsampling preserves amplitude.
 */
object Resampler {

    /** Half window length, in input samples. Larger = sharper cutoff, slower. */
    internal const val HALF = 16

    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate) return input.copyOf()
        val nIn = input.size
        val nOut = ((nIn.toDouble() * outRate) / inRate).toInt()
        if (nOut == 0) return FloatArray(0)

        val fc = 0.5 * minOf(inRate, outRate) / inRate // normalized cutoff
        val out = FloatArray(nOut)

        for (n in 0 until nOut) {
            val pos = n.toDouble() * inRate / outRate // position in input index space
            val center = pos.toInt()
            var sum = 0.0
            for (i in -HALF..HALF) {
                val idx = center + i
                if (idx < 0 || idx >= nIn) continue
                val x = pos - idx
                sum += input[idx] * kernel(x, fc)
            }
            out[n] = sum.toFloat()
        }
        return out
    }

    /** `2*fc * sinc(2*fc*x) * hann(x)`, Hann window zero at `|x| = HALF`. */
    private fun kernel(x: Double, fc: Double): Double {
        val w = 0.5 + 0.5 * kotlin.math.cos(Math.PI * x / HALF)
        if (w == 0.0) return 0.0
        val u = 2.0 * fc * x
        val sinc = if (u == 0.0) 1.0 else kotlin.math.sin(Math.PI * u) / (Math.PI * u)
        return 2.0 * fc * sinc * w
    }
}
