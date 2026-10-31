package com.alierkitten.spw.replaygain.replaygain.analyzer

import com.jcraft.jogg.Page
import com.jcraft.jogg.Packet
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import java.io.File
import java.io.FileInputStream

/**
 * Streaming OGG/Vorbis decoder using the bundled `JOrbis` codec (pure Java, no
 * javax.sound SPI). The `com.googlecode.soundlibs` repackaged JOrbis
 * (0.0.17.4) API quirks are handled here:
 *  - `synthesis_pcmout(float[][][], int[])` writes the decoded PCM into
 *    `pcm[0][channel]`, already normalized to roughly [-1, 1].
 *  - `synthesis_blockin(Block)` (no Packet arg); the packet is first loaded with
 *    `Block.synthesis(Packet)`.
 */
object VorbisDecoder {
    fun open(file: File): PcmChunkSource = VorbisPcmSource(file)
}

private class VorbisPcmSource(file: File) : PcmChunkSource {
    private val name = file.name
    private val fis = FileInputStream(file)
    private val oy = SyncState().apply { init() }
    private val os = StreamState()
    private val og = Page()
    private val op = Packet()
    private val vi = Info()
    private val vc = Comment()
    private val vd = DspState()
    private val vb = Block(vd)

    private var pending = FloatArray(0)
    private var pos = 0
    private var rate = 44100
    private var ch = 2
    private var closed = false
    private var headerStage = 0
    private var osInited = false
    private var pcm: Array<Array<FloatArray>>? = null
    private var eof = false

    init { decodeHeaders() }

    override val sampleRate: Int get() = rate
    override val channels: Int get() = ch

    private fun decodeHeaders() {
        while (headerStage < 3 && !eof) {
            if (oy.pageout(og) != 1) {
                val idx = oy.buffer(2048)
                val bytes = fis.read(oy.data, idx, 2048)
                if (bytes < 0) { eof = true; break }
                oy.wrote(bytes)
                continue
            }
            if (!osInited) { os.init(og.serialno()); osInited = true }
            os.pagein(og)
            while (os.packetout(op) == 1) {
                when (headerStage) {
                    0, 1 -> {
                        if (vi.synthesis_headerin(vc, op) != 0) break
                        headerStage++
                    }
                    2 -> {
                        if (vi.synthesis_headerin(vc, op) != 0) break
                        headerStage = 3
                        rate = vi.rate
                        ch = vi.channels
                        pcm = Array(1) { Array(ch) { FloatArray(0) } }
                        vd.synthesis_init(vi)
                        vb.init(vd)
                    }
                }
            }
        }
        if (headerStage < 3) throw IllegalStateException("OGG/Vorbis 头部解析失败: $name")
    }

    override fun read(out: FloatArray): Int {
        if (closed) return 0
        var filled = 0
        while (filled < out.size) {
            if (pos >= pending.size) {
                if (!pump()) break
            }
            val take = minOf(pending.size - pos, out.size - filled)
            System.arraycopy(pending, pos, out, filled, take)
            pos += take
            filled += take
        }
        return filled
    }

    private fun pump(): Boolean {
        while (pos >= pending.size) {
            if (oy.pageout(og) != 1) {
                if (eof) return pos < pending.size
                val idx = oy.buffer(2048)
                val bytes = fis.read(oy.data, idx, 2048)
                if (bytes < 0) { eof = true; continue }
                oy.wrote(bytes)
                continue
            }
            if (!osInited) { os.init(og.serialno()); osInited = true }
            os.pagein(og)
            while (os.packetout(op) == 1) {
                if (headerStage < 3) {
                    when (headerStage) {
                        0, 1 -> if (vi.synthesis_headerin(vc, op) == 0) headerStage++
                        2 -> if (vi.synthesis_headerin(vc, op) == 0) {
                            headerStage = 3
                            rate = vi.rate
                            ch = vi.channels
                            pcm = Array(1) { Array(ch) { FloatArray(0) } }
                            vd.synthesis_init(vi)
                            vb.init(vd)
                        }
                    }
                    continue
                }
                if (vb.synthesis(op) == 0) {
                    vd.synthesis_blockin(vb)
                    val plane = pcm!![0]
                    while (true) {
                        val n = vd.synthesis_pcmout(pcm, null)
                        if (n <= 0) break
                        appendInterleaved(plane, n)
                        vd.synthesis_read(n)
                    }
                }
            }
        }
        return true
    }

    private fun appendInterleaved(plane: Array<FloatArray>, n: Int) {
        val c = ch
        val leftover = pending.size - pos
        val next = FloatArray(leftover + n * c)
        if (leftover > 0) System.arraycopy(pending, pos, next, 0, leftover)
        var k = leftover
        for (i in 0 until n) {
            for (j in 0 until c) {
                next[k++] = plane[j][i]
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
