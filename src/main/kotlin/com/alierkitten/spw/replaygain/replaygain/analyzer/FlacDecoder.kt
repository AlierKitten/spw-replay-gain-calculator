package com.alierkitten.spw.replaygain.replaygain.analyzer

import org.jflac.FLACDecoder
import org.jflac.frame.Frame
import org.jflac.metadata.Metadata
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import java.io.File
import java.io.FileInputStream

/**
 * Streaming FLAC decoder using the bundled `JFLAC` codec (pure Java, no
 * javax.sound SPI). All metadata is read up front to learn the format, then each
 * audio frame is decoded on demand and its PCM is normalized to `[-1, 1]` and
 * served in chunks, so the whole file is never buffered at once.
 *
 * NOTE: `tritonus-all` (previously expected to provide FLAC) does not actually
 * contain a FLAC decoder, and `javax.sound.sampled` SPI discovery cannot see
 * plugin-bundled providers inside the host's class loader. Decoding directly
 * with JFLAC sidesteps both problems.
 */
object FlacDecoder {
    fun open(file: File): PcmChunkSource = FlacPcmSource(file)
}

private class FlacPcmSource(file: File) : PcmChunkSource {
    private val fis = FileInputStream(file)
    private val decoder = FLACDecoder(fis)

    private var pending = FloatArray(0)
    private var pos = 0
    private var rate = 44100
    private var ch = 2
    private var bits = 16
    private var closed = false
    private var eof = false
    private val work = ByteData(65536)

    init {
        val md = runCatching { decoder.readMetadata() }.getOrNull()
        val si = md?.firstOrNull { it is StreamInfo } as StreamInfo?
        if (si != null) {
            rate = si.sampleRate
            ch = si.channels
            bits = si.bitsPerSample
        }
    }

    override val sampleRate: Int get() = rate
    override val channels: Int get() = ch

    override fun read(out: FloatArray): Int {
        if (closed) return 0
        while (pos >= pending.size) {
            // Terminate unconditionally at end-of-stream. JFLAC's readNextFrame()
            // may keep returning non-null frames near EOF on some builds, so we
            // guard with isEOF() and also bail out the moment a frame cannot be
            // decoded. Without this the read loop can spin forever (no exception,
            // no progress) and hang the whole index job.
            if (eof || decoder.isEOF()) { eof = true; break }
            val frame: Frame = runCatching { decoder.readNextFrame() }.getOrNull()
                ?: run { eof = true; break }
            val pcm = runCatching { decoder.decodeFrame(frame, work) }.getOrNull()
                ?: run { eof = true; break }
            append(pcm)
        }
        var filled = 0
        while (filled < out.size && pos < pending.size) {
            val take = minOf(pending.size - pos, out.size - filled)
            System.arraycopy(pending, pos, out, filled, take)
            pos += take
            filled += take
        }
        return filled
    }

    private fun append(pcm: ByteData) {
        val bytes = pcm.getData()
        val len = pcm.getLen()
        val bytesPerSample = (bits + 7) / 8
        val frameBytes = bytesPerSample * ch
        if (frameBytes <= 0 || len < frameBytes) return
        val frames = len / frameBytes
        val leftover = pending.size - pos
        val next = FloatArray(leftover + frames * ch)
        if (leftover > 0) System.arraycopy(pending, pos, next, 0, leftover)
        var k = leftover
        var o = 0
        repeat(frames) {
            repeat(ch) {
                val sample: Float = when (bytesPerSample) {
                    1 -> {
                        val b = bytes[o++].toInt() and 0xFF
                        (b - 128) / 128f
                    }
                    2 -> {
                        val v = (bytes[o++].toInt() and 0xFF) or
                            ((bytes[o++].toInt() and 0xFF) shl 8)
                        val s = if (v and 0x8000 != 0) v - 0x10000 else v
                        s / 32768f
                    }
                    3 -> {
                        val v = ((bytes[o++].toInt() and 0xFF) shl 16) or
                            ((bytes[o++].toInt() and 0xFF) shl 8) or
                            (bytes[o++].toInt() and 0xFF)
                        val s = if (v and 0x800000 != 0) v - 0x1000000 else v
                        s / 8388608f
                    }
                    else -> {
                        val v = ((bytes[o++].toInt() and 0xFF) shl 24) or
                            ((bytes[o++].toInt() and 0xFF) shl 16) or
                            ((bytes[o++].toInt() and 0xFF) shl 8) or
                            (bytes[o++].toInt() and 0xFF)
                        v / 2147483648f
                    }
                }
                next[k++] = sample
            }
        }
        pending = next
        pos = 0
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { fis.close() }
    }
}
