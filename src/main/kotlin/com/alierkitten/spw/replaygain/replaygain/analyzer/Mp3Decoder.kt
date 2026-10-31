package com.alierkitten.spw.replaygain.replaygain.analyzer

import java.io.File
import java.io.FileInputStream
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer

/**
 * Streaming MP3 decoder using the bundled `jlayer` decoder (pure Java, no
 * javax.sound SPI). Each decoded frame is buffered and served in chunks so the
 * whole file is never held in memory at once.
 */
object Mp3Decoder {
    fun open(file: File): PcmChunkSource = Mp3PcmSource(file)
}

private class Mp3PcmSource(file: File) : PcmChunkSource {
    private val bitstream = Bitstream(FileInputStream(file))
    private val decoder = Decoder()

    private var pending = FloatArray(0)
    private var pos = 0
    private var rate = 44100
    private var ch = 2
    private var closed = false
    private var formatKnown = false

    init { prime() }

    override val sampleRate: Int get() = rate
    override val channels: Int get() = ch

    private fun prime() {
        formatKnown = true
        val header = runCatching { bitstream.readFrame() }.getOrNull() ?: return
        val buffer = runCatching { decoder.decodeFrame(header, bitstream) as SampleBuffer }.getOrNull() ?: return
        rate = buffer.sampleFrequency
        ch = buffer.channelCount
        pending = FloatArray(buffer.buffer.size) { i -> buffer.buffer[i] / 32768f }
        pos = 0
        runCatching { bitstream.closeFrame() }
    }

    override fun read(out: FloatArray): Int {
        if (closed) return 0
        if (!formatKnown) prime()
        var filled = 0
        while (filled < out.size) {
            if (pos >= pending.size) {
                val header = runCatching { bitstream.readFrame() }.getOrNull() ?: break
                val buffer = runCatching { decoder.decodeFrame(header, bitstream) as SampleBuffer }.getOrNull() ?: break
                rate = buffer.sampleFrequency
                ch = buffer.channelCount
                pending = FloatArray(buffer.buffer.size) { i -> buffer.buffer[i] / 32768f }
                pos = 0
                bitstream.closeFrame()
            }
            val take = minOf(pending.size - pos, out.size - filled)
            System.arraycopy(pending, pos, out, filled, take)
            pos += take
            filled += take
        }
        return filled
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { bitstream.close() }
    }
}
