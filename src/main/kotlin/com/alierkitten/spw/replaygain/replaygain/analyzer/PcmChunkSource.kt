package com.alierkitten.spw.replaygain.replaygain.analyzer

/**
 * A pull-based, streaming source of decoded PCM. Samples are interleaved across
 * channels and normalized to `[-1.0, 1.0]`. Decoders implement this so the
 * analyzer never has to hold an entire song in memory at once.
 */
interface PcmChunkSource {
    val sampleRate: Int
    val channels: Int

    /**
     * Fills [out] (interleaved) with up to `out.size` samples.
     * @return the number of samples actually read; `0` means end of stream.
     */
    fun read(out: FloatArray): Int

    fun close()
}
